package com.eatwhat.auth;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 开发用短信发送器：只打日志，不真发短信。
 *
 * **不要在生产用这个** —— 用户永远收不到验证码。
 * 生产换成真实网关实现（见 {@link SmsSender}），
 * 并把 `eatwhat.auth.echoSmsCode` 关掉（否则验证码会随接口返回给前端，等于没有验证）。
 */
@Slf4j
@Component
public class DevSmsSender implements SmsSender {

    @Override
    public void send(String phone, String code, String scene) {
        log.info("┌─【开发短信·未真实发送】───────────────────────");
        log.info("│ 场景 {}  手机号 {}  验证码 {}", scene, mask(phone), code);
        log.info("│ 接入真实网关后此日志会被替换成实际下发");
        log.info("└──────────────────────────────────────────");
    }

    /** 日志里手机号中间四位打码，避免日志文件泄漏完整号码 */
    static String mask(String phone) {
        if (phone == null || phone.length() < 7) return String.valueOf(phone);
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }
}
