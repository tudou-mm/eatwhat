package com.eatwhat.controller;

import com.eatwhat.auth.JwtUtil;
import com.eatwhat.common.BizException;
import com.eatwhat.common.R;
import com.eatwhat.common.Views;
import com.eatwhat.entity.Shop;
import com.eatwhat.repository.ShopRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 登录发证。
 *
 * 放在 /api/auth/** 下是有意的：这个前缀**不被 AuthInterceptor 拦**，
 * 否则「登录也要先登录」，死循环。
 *
 * 原型阶段的账号体系：
 *   平台端  账号 admin / 密码 admin123（配置项 eatwhat.auth.*）
 *   商家端  账号 = 店铺 ID（s_001）或店内电话，密码统一 123456
 * ⚠️ 真实系统商家必须走手机号 + 短信验证码，这里用配置密码只是为了演示方便，
 *    见 docs/05 的「已知简化」一节。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final ShopRepository shopRepo;
    private final JwtUtil jwt;
    private final Views views;

    private final String adminAccount;
    private final String adminPassword;
    private final String merchantPassword;

    public AuthController(ShopRepository shopRepo, JwtUtil jwt, Views views,
                          @Value("${eatwhat.auth.adminAccount:admin}") String adminAccount,
                          @Value("${eatwhat.auth.adminPassword:admin123}") String adminPassword,
                          @Value("${eatwhat.auth.merchantPassword:123456}") String merchantPassword) {
        this.shopRepo = shopRepo;
        this.jwt = jwt;
        this.views = views;
        this.adminAccount = adminAccount;
        this.adminPassword = adminPassword;
        this.merchantPassword = merchantPassword;
    }

    /**
     * 统一登录入口。
     * body: { role: "admin"|"merchant", account, password }
     */
    @PostMapping("/login")
    public R<Map<String, Object>> login(@RequestBody Map<String, Object> body) {
        String role = str(body.get("role"));
        if (role.isEmpty()) role = "merchant";
        // 兼容前台传 phone 而不是 account
        String account = str(body.get("account"));
        if (account.isEmpty()) account = str(body.get("phone"));
        String password = str(body.get("password"));

        if (account.isEmpty()) throw new BizException(400, "账号不能为空");
        if (password.isEmpty()) throw new BizException(400, "密码不能为空");

        return switch (role) {
            case "admin" -> R.ok(loginAdmin(account, password));
            case "merchant" -> R.ok(loginMerchant(account, password));
            default -> throw new BizException(400, "不支持的角色：" + role);
        };
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

    private Map<String, Object> loginMerchant(String account, String password) {
        if (!merchantPassword.equals(password)) {
            throw new BizException(401, "密码不正确");
        }
        // 先按店铺 ID 找，再按店内电话找
        Shop s = shopRepo.findById(account).orElse(null);
        if (s == null) s = shopRepo.findFirstByPhone(account).orElse(null);
        if (s == null) {
            throw new BizException(401, "该账号还没有入驻记录，请先提交入驻申请");
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
