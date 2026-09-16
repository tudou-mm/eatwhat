package com.eatwhat.auth;

/**
 * 短信发送。**接真实网关时只需要换掉这个接口的实现**。
 *
 * 原型阶段用 {@link DevSmsSender} —— 只打日志不发短信，
 * 配合 `eatwhat.auth.echoSmsCode=true` 把验证码回显给前端，方便调试。
 * 上线时新写一个 `AliyunSmsSender` / `TencentSmsSender` 即可，调用方不用改。
 *
 * @param scene 场景：login（登录）/ bind（绑定手机号）/ reset（重置密码）
 */
public interface SmsSender {

    void send(String phone, String code, String scene);
}
