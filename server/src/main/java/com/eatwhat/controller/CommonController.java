package com.eatwhat.controller;

import com.eatwhat.auth.JwtUtil;
import com.eatwhat.common.R;
import com.eatwhat.common.Views;
import com.eatwhat.entity.AppUser;
import com.eatwhat.service.AppUserService;
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

    public CommonController(AppUserService userService, JwtUtil jwt, Views views) {
        this.userService = userService;
        this.jwt = jwt;
        this.views = views;
    }

    @GetMapping("/health")
    public R<Map<String, Object>> health() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", "ok");
        m.put("name", "eatwhat-server");
        return R.ok(m);
    }

    /**
     * 客户端登录：手机号 + 验证码（演示环境任意 6 位数字均可）。
     * 客户端目前免登录浏览，这个接口暂时没有接口依赖它，留作后续做「我的」页用。
     */
    @PostMapping("/login")
    public R<Map<String, Object>> login(@RequestBody Map<String, Object> body) {
        String phone = String.valueOf(body.get("phone"));
        String code = body.get("code") == null ? "" : body.get("code").toString();

        if (!phone.matches("^1\\d{10}$")) return R.fail(400, "手机号格式不正确");
        if (!code.matches("^\\d{6}$")) return R.fail(400, "验证码为 6 位数字");

        AppUser u = userService.loginByPhone(phone);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("user", views.user(u));                 // 出参一律过 Views，不返回裸实体
        m.put("token", jwt.sign(u.getId(), "client", null, u.getName()));
        m.put("role", "client");
        return R.ok(m);
    }
}
