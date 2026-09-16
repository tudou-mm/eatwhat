package com.eatwhat.auth;

import com.eatwhat.common.BizException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;

/**
 * 短信验证码存储与校验。
 *
 * 四条规则，少一条都不行：
 * 1. **60 秒内不能重复发** —— 否则接口就成了免费短信轰炸器（真发短信是要花钱的）
 * 2. **5 分钟过期** —— 码一直有效等于把风险窗口拉长
 * 3. **错误次数上限** —— 不限次数的话 6 位码可以暴力试出来（100 万种，机器几分钟跑完）
 * 4. **用后即焚** —— 校验通过立刻删，防重放
 *
 * 内存实现，重启即清空；多实例部署要换 Redis（见 docs/05 §八）。
 */
@Slf4j
@Component
public class SmsCodeStore {

    private static final class Entry {
        String code;
        long sentAt;
        long expireAt;
        int tries;
    }

    private static final class SendWindow {
        long windowStart;
        int count;
    }

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final long IP_WINDOW_MILLIS = 3600_000L;

    private final int expireSeconds;
    private final int resendSeconds;
    private final int maxTries;
    private final int ipMaxPerHour;

    private final Map<String, Entry> store = new HashMap<>();
    private final Map<String, SendWindow> sendIps = new HashMap<>();

    public SmsCodeStore(@Value("${eatwhat.auth.smsCodeExpireSeconds:300}") int expireSeconds,
                        @Value("${eatwhat.auth.smsResendSeconds:60}") int resendSeconds,
                        @Value("${eatwhat.auth.smsMaxTries:5}") int maxTries,
                        @Value("${eatwhat.auth.smsIpMaxPerHour:10}") int ipMaxPerHour) {
        this.expireSeconds = expireSeconds;
        this.resendSeconds = resendSeconds;
        this.maxTries = maxTries;
        this.ipMaxPerHour = ipMaxPerHour;
    }

    /**
     * 发一个新码并返回明文（由调用方交给 {@link SmsSender}）。
     *
     * 两道闸：同号 60 秒内不能重发，同 IP 一小时上限 {@code ipMaxPerHour} 次。
     * **只限手机号是不够的** —— 换个号就绕过了，真发短信是要花钱的，
     * 被人拿去刷一遍就是一笔账单。
     */
    public synchronized String issue(String phone, String ip) {
        long now = System.currentTimeMillis();

        if (ip != null && !ip.isEmpty()) {
            SendWindow w = sendIps.computeIfAbsent(ip, k -> new SendWindow());
            if (now - w.windowStart >= IP_WINDOW_MILLIS) {
                w.windowStart = now;
                w.count = 0;
            }
            if (w.count >= ipMaxPerHour) {
                throw new BizException(429, "该网络获取验证码过于频繁，请稍后再试");
            }
            w.count++;
        }

        Entry old = store.get(phone);
        if (old != null) {
            long wait = (old.sentAt + resendSeconds * 1000L - now + 999) / 1000;
            if (wait > 0) {
                throw new BizException(429, "请求过于频繁，请 " + wait + " 秒后再获取验证码");
            }
        }

        String code = String.format("%06d", RANDOM.nextInt(1_000_000));
        Entry e = new Entry();
        e.code = code;
        e.sentAt = now;
        e.expireAt = now + expireSeconds * 1000L;
        e.tries = 0;
        store.put(phone, e);

        // 顺手清掉过期条目，避免只增不减
        store.entrySet().removeIf(x -> x.getValue().expireAt < now);
        sendIps.entrySet().removeIf(x -> now - x.getValue().windowStart >= IP_WINDOW_MILLIS);
        return code;
    }

    /**
     * 校验。不通过直接抛异常，通过则**立刻作废**。
     */
    public synchronized void verify(String phone, String code) {
        long now = System.currentTimeMillis();
        Entry e = store.get(phone);
        if (e == null) {
            throw new BizException(400, "请先获取验证码");
        }
        if (now > e.expireAt) {
            store.remove(phone);
            throw new BizException(400, "验证码已过期，请重新获取");
        }
        if (!e.code.equals(code)) {
            e.tries++;
            if (e.tries >= maxTries) {
                store.remove(phone);
                throw new BizException(429, "验证码错误次数过多，请重新获取");
            }
            throw new BizException(400, "验证码不正确，还可尝试 " + (maxTries - e.tries) + " 次");
        }
        store.remove(phone);   // 用后即焚，防重放
    }

    public int expireSeconds() {
        return expireSeconds;
    }

    public int resendSeconds() {
        return resendSeconds;
    }

    /** 该号是否已有一个待校验的码（给测试用，别暴露成接口） */
    public synchronized boolean hasPending(String phone) {
        Entry e = store.get(phone);
        return e != null && e.expireAt >= System.currentTimeMillis();
    }

    /** 丢掉某个号的码（测试收尾用） */
    public synchronized void clear(String phone) {
        store.remove(phone);
    }
}
