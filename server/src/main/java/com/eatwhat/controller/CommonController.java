package com.eatwhat.controller;

import com.eatwhat.auth.JwtUtil;
import com.eatwhat.auth.LoginGuard;
import com.eatwhat.auth.SmsCodeStore;
import com.eatwhat.common.BizException;
import com.eatwhat.common.R;
import com.eatwhat.common.Views;
import com.eatwhat.entity.AppUser;
import com.eatwhat.service.AppUserService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 通用接口：健康检查、客户端登录。
 *
 * 平台端与商家端的登录已经收敛到 {@link AuthController}（发真 JWT），
 * 这里原先那两个「返回 mock-admin-token」的桩接口已删除 —— 留着的话
 * 前端只要调它就能拿到一张假通行证，等于鉴权没做。
 */
@RestController
@RequestMapping("/api")
public class CommonController {

    private final AppUserService userService;
    private final JwtUtil jwt;
    private final Views views;
    private final LoginGuard guard;
    private final SmsCodeStore smsStore;

    public CommonController(AppUserService userService, JwtUtil jwt, Views views,
                            LoginGuard guard, SmsCodeStore smsStore) {
        this.userService = userService;
        this.jwt = jwt;
        this.views = views;
        this.guard = guard;
        this.smsStore = smsStore;
    }

    @GetMapping("/health")
    public R<Map<String, Object>> health() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", "ok");
        m.put("name", "eatwhat-server");
        return R.ok(m);
    }

    /**
     * 客户端登录：手机号 + 短信验证码。
     *
     * **验证码现在是真校验的**（原先任意 6 位都算过）。码由
     * `POST /api/auth/sms-code` 下发，60 秒内不能重发、5 分钟过期、
     * 错 5 次作废、用后即焚（见 {@link SmsCodeStore}）。
     * 手机号不存在则自动注册。
     */
    @PostMapping("/login")
    public R<Map<String, Object>> login(@RequestBody Map<String, Object> body,
                                        HttpServletRequest req) {
        String phone = String.valueOf(body.get("phone"));
        String code = body.get("code") == null ? "" : body.get("code").toString();

        if (!phone.matches("^1\\d{10}$")) throw new BizException(400, "手机号格式不正确");
        if (!code.matches("^\\d{6}$")) throw new BizException(400, "验证码为 6 位数字");

        guard.beforeAttempt(phone, req.getRemoteAddr());
        smsStore.verify(phone, code);
        guard.onSuccess(phone);

        AppUser u = userService.loginByPhone(phone);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("user", views.user(u));                 // 出参一律过 Views，不返回裸实体
        m.put("token", jwt.sign(u.getId(), "client", null, u.getName(), u.getTokenVersion()));
        m.put("role", "client");
        return R.ok(m);
    }
}
