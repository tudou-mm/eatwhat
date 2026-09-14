package com.eatwhat.controller;

import com.eatwhat.common.R;
import com.eatwhat.entity.AppUser;
import com.eatwhat.service.AppUserService;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 通用接口：健康检查、登录。
 */
@RestController
@RequestMapping("/api")
public class CommonController {

    private final AppUserService userService;

    public CommonController(AppUserService userService) {
        this.userService = userService;
    }

    @GetMapping("/health")
    public R<String> health() {
        return R.ok("ok");
    }

    /** 客户端登录：手机号 + 验证码（演示环境验证码固定 6 位数字均可） */
    @PostMapping("/login")
    public R<Map<String, Object>> login(@RequestBody Map<String, Object> body) {
        String phone = String.valueOf(body.get("phone"));
        String code = body.get("code") == null ? "" : body.get("code").toString();

        if (!phone.matches("^1\\d{10}$")) return R.fail(400, "手机号格式不正确");
        if (!code.matches("^\\d{6}$")) return R.fail(400, "验证码为 6 位数字");

        AppUser u = userService.loginByPhone(phone);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("user", u);
        m.put("token", "mock-token-" + u.getId());   // 演示用，正式应发 JWT
        return R.ok(m);
    }

    /** 商家端登录（演示：任意账号密码通过，返回固定店铺） */
    @PostMapping("/merchant/login")
    public R<Map<String, Object>> merchantLogin(@RequestBody Map<String, Object> body) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("merchantId", "m_001");
        m.put("shopId", "s_001");
        m.put("token", "mock-merchant-token");
        return R.ok(m);
    }

    /** 平台端登录（演示：固定超管） */
    @PostMapping("/admin/login")
    public R<Map<String, Object>> adminLogin(@RequestBody Map<String, Object> body) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("adminId", "a_001");
        m.put("name", "平台运营");
        m.put("role", "super");
        m.put("token", "mock-admin-token");
        return R.ok(m);
    }
}
