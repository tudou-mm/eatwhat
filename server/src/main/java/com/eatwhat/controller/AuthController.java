package com.eatwhat.controller;

import com.eatwhat.auth.JwtUtil;
import com.eatwhat.auth.LoginGuard;
import com.eatwhat.auth.SmsCodeStore;
import com.eatwhat.auth.SmsSender;
import com.eatwhat.common.BizException;
import com.eatwhat.common.R;
import com.eatwhat.common.Views;
import com.eatwhat.entity.Shop;
import com.eatwhat.repository.ShopRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 登录发证 + 短信验证码。
 *
 * 放在 /api/auth/** 下是有意的：这个前缀**不被 AuthInterceptor 拦**，
 * 否则「登录也要先登录」，死循环。
 *
 * 支持的登录方式：
 *   admin      账号 + 密码（内部账号，不走短信）
 *   merchant   ① 账号（店铺 ID / 店内电话）+ 密码
 *              ② 手机号 + 短信验证码  ← 商家正式上线后应走这条
 *
 * 三道防撞库的闸，都在这里收口：
 *   1. {@link LoginGuard} —— 连续失败锁定账号 + 同 IP 尝试频率
 *   2. {@link SmsCodeStore} —— 同号 60 秒重发限制 + 同 IP 每小时发码上限
 *   3. 账号/密码错误统一 401、**同一句提示**，不区分「账号不存在」和「密码错」
 *
 * ⚠️ 生产环境必须设 `eatwhat.auth.echoSmsCode=false`，否则验证码会随接口
 *    返回给前端，等于没验证。JWT 密钥见 {@link JwtUtil}。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final ShopRepository shopRepo;
    private final JwtUtil jwt;
    private final Views views;
    private final LoginGuard guard;
    private final SmsCodeStore smsStore;
    private final SmsSender smsSender;

    private final String adminAccount;
    private final String adminPassword;
    private final String merchantPassword;
    private final boolean echoSmsCode;

    public AuthController(ShopRepository shopRepo, JwtUtil jwt, Views views,
                          LoginGuard guard, SmsCodeStore smsStore, SmsSender smsSender,
                          @Value("${eatwhat.auth.adminAccount:admin}") String adminAccount,
                          @Value("${eatwhat.auth.adminPassword:admin123}") String adminPassword,
                          @Value("${eatwhat.auth.merchantPassword:123456}") String merchantPassword,
                          @Value("${eatwhat.auth.echoSmsCode:true}") boolean echoSmsCode) {
        this.shopRepo = shopRepo;
        this.jwt = jwt;
        this.views = views;
        this.guard = guard;
        this.smsStore = smsStore;
        this.smsSender = smsSender;
        this.adminAccount = adminAccount;
        this.adminPassword = adminPassword;
        this.merchantPassword = merchantPassword;
        this.echoSmsCode = echoSmsCode;
    }

    /**
     * 获取短信验证码。
     * body: { phone, scene? }   scene 默认 login
     *
     * 开发环境把码回显在 `devCode` 里方便调试；echoSmsCode=false 时不返回，
     * 那时码只能从短信里拿。
     */
    @PostMapping("/sms-code")
    public R<Map<String, Object>> smsCode(@RequestBody Map<String, Object> body,
                                          HttpServletRequest req) {
        String phone = str(body.get("phone"));
        String scene = str(body.get("scene"));
        if (scene.isEmpty()) scene = "login";
        if (!phone.matches("^1\\d{10}$")) {
            throw new BizException(400, "手机号格式不正确");
        }

        String code = smsStore.issue(phone, req.getRemoteAddr());
        smsSender.send(phone, code, scene);

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sent", true);
        m.put("expiresIn", smsStore.expireSeconds());
        m.put("resendAfter", smsStore.resendSeconds());
        if (echoSmsCode) m.put("devCode", code);
        return R.ok(m);
    }

    /**
     * 统一登录入口。
     * body: { role: "admin"|"merchant", account, password? , code? }
     *
     * password 与 code 二选一：给 code 就走短信验证码登录。
     */
    @PostMapping("/login")
    public R<Map<String, Object>> login(@RequestBody Map<String, Object> body,
                                        HttpServletRequest req) {
        String role = str(body.get("role"));
        if (role.isEmpty()) role = "merchant";
        // 兼容前台传 phone 而不是 account
        String account = str(body.get("account"));
        if (account.isEmpty()) account = str(body.get("phone"));
        String password = str(body.get("password"));
        String code = str(body.get("code"));

        if (account.isEmpty()) throw new BizException(400, "账号不能为空");
        if (password.isEmpty() && code.isEmpty()) {
            throw new BizException(400, "请填写密码或短信验证码");
        }

        guard.beforeAttempt(account, req.getRemoteAddr());
        try {
            Map<String, Object> data = switch (role) {
                case "admin" -> loginAdmin(account, password);
                case "merchant" -> loginMerchant(account, password, code);
                default -> throw new BizException(400, "不支持的角色：" + role);
            };
            guard.onSuccess(account);
            return R.ok(data);
        } catch (BizException e) {
            // 只有凭证错误才计入失败次数。参数不合法 / 被封禁不算 ——
            // 否则用户手滑打错格式也能把自己锁了。
            if (e.getCode() == 401) guard.onFailure(account);
            throw e;
        }
    }

    private Map<String, Object> loginAdmin(String account, String password) {
        // 账号密码错误统一回 401，不区分「账号不存在」和「密码错」，避免被枚举
        if (!adminAccount.equals(account) || !adminPassword.equals(password)) {
            throw new BizException(401, "账号或密码不正确");
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("token", jwt.sign("a_001", "admin", null, "平台运营"));
        m.put("role", "admin");
        m.put("subject", "a_001");
        m.put("name", "平台运营");
        m.put("shopId", null);
        m.put("expiresIn", jwt.getExpireSeconds());
        return m;
    }

    private Map<String, Object> loginMerchant(String account, String password, String code) {
        Shop s;
        if (!code.isEmpty()) {
            // ---- 短信验证码登录：账号必须是手机号（码是发到手机上的）----
            if (!account.matches("^1\\d{10}$")) {
                throw new BizException(400, "验证码登录请填写手机号");
            }
            smsStore.verify(account, code);
            s = shopRepo.findFirstByPhone(account).orElse(null);
            if (s == null) {
                throw new BizException(401, "该手机号还没有入驻记录，请先提交入驻申请");
            }
        } else {
            // ---- 密码登录 ----
            if (!merchantPassword.equals(password)) {
                throw new BizException(401, "密码不正确");
            }
            s = shopRepo.findById(account).orElse(null);
            if (s == null) s = shopRepo.findFirstByPhone(account).orElse(null);
            if (s == null) {
                throw new BizException(401, "该账号还没有入驻记录，请先提交入驻申请");
            }
        }

        if ("banned".equals(s.getStatus())) {
            throw new BizException(403, "该店铺已被封禁，请联系平台");
        }

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("token", jwt.sign("m_" + s.getId(), "merchant", s.getId(), s.getName()));
        m.put("role", "merchant");
        m.put("subject", "m_" + s.getId());
        m.put("shopId", s.getId());
        m.put("name", s.getName());
        m.put("expiresIn", jwt.getExpireSeconds());
        // 顺带把店铺视图带上，登录页可以直接显示「XX 小馆 · 审核中」
        m.put("shop", views.shop(s));
        return m;
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }
}
