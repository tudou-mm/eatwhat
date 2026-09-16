package com.eatwhat.auth;

import com.eatwhat.common.BizException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * 登录守卫：账号锁定 + IP 限速。
 *
 * 没有这两道之前，`admin / admin123` 这种弱口令可以被无限次撞 ——
 * 每秒试几十个密码，很快就能撞开。加锁之后单账号只能试 5 次。
 *
 * 两道规则：
 * 1. **账号维度**：连续失败 {@code maxLoginAttempts} 次 → 锁定 {@code lockMinutes} 分钟。
 *    锁定期间**即使密码正确也拒绝**，否则攻击者可以靠「猜中了就进」绕过计数。
 *    登录成功清零。任何一次成功都会把失败计数抹掉，避免"历史失败"累积误伤。
 * 2. **IP 维度**：同一 IP 每分钟最多 {@code ipMaxPerMinute} 次登录尝试。
 *    账号锁定挡的是「盯着一家撞」，IP 限速挡的是「一个密码撞一片」。
 *
 * ⚠️ **只认 remoteAddr，不认 X-Forwarded-For**。原型阶段前面没有网关，
 * 信 XFF 等于把限流开关交给调用方（请求头自己填就行）。上生产要么在网关
 * 层做限流，要么由网关**覆盖**写 XFF 之后再让这里读。
 *
 * 内存实现，重启即清空；多实例部署要换成 Redis（见 docs/05 §八）。
 */
@Slf4j
@Component
public class LoginGuard {

    private static final class Account {
        int fails;
        long lockedUntil;
    }

    private static final class IpWindow {
        long windowStart;
        int count;
    }

    private final int maxAttempts;
    private final long lockMillis;
    private final int ipMaxPerMinute;

    private final Map<String, Account> accounts = new HashMap<>();
    private final Map<String, IpWindow> ips = new HashMap<>();
    private long lastSweep;

    public LoginGuard(@Value("${eatwhat.auth.maxLoginAttempts:5}") int maxAttempts,
                      @Value("${eatwhat.auth.lockMinutes:15}") long lockMinutes,
                      @Value("${eatwhat.auth.ipMaxPerMinute:30}") int ipMaxPerMinute) {
        this.maxAttempts = maxAttempts;
        this.lockMillis = lockMinutes * 60_000L;
        this.ipMaxPerMinute = ipMaxPerMinute;
    }

    /**
     * 每次登录尝试**之前**调用。被拦时抛 429，附带还要等多久。
     */
    public synchronized void beforeAttempt(String account, String ip) {
        long now = System.currentTimeMillis();
        sweep(now);

        Account a = account == null ? null : accounts.get(account);
        if (a != null && a.lockedUntil > now) {
            long leftSec = (a.lockedUntil - now + 999) / 1000;
            long leftMin = (leftSec + 59) / 60;
            throw new BizException(429, "登录失败次数过多，账号已锁定，请 "
                    + leftMin + " 分钟后再试");
        }

        if (ip != null && !ip.isEmpty()) {
            IpWindow w = ips.computeIfAbsent(ip, k -> new IpWindow());
            if (now - w.windowStart >= 60_000L) {
                w.windowStart = now;
                w.count = 0;
            }
            w.count++;
            if (w.count > ipMaxPerMinute) {
                throw new BizException(429, "登录尝试过于频繁，请 1 分钟后再试");
            }
        }
    }

    /** 密码错 / 验证码错 —— 记一次失败，够数就锁 */
    public synchronized void onFailure(String account) {
        if (account == null || account.isEmpty()) return;
        Account a = accounts.computeIfAbsent(account, k -> new Account());
        a.fails++;
        if (a.fails >= maxAttempts) {
            a.lockedUntil = System.currentTimeMillis() + lockMillis;
            log.warn("账号 {} 连续失败 {} 次，已锁定 {} 分钟", account, a.fails, lockMillis / 60_000L);
        }
    }

    /** 登录成功 —— 清零，不给"历史失败"累积误伤的机会 */
    public synchronized void onSuccess(String account) {
        if (account == null || account.isEmpty()) return;
        accounts.remove(account);
    }

    /** 还剩几秒解锁（0 表示没锁）。给测试和后台排查用 */
    public synchronized long lockedSeconds(String account) {
        Account a = accounts.get(account);
        if (a == null) return 0;
        long left = a.lockedUntil - System.currentTimeMillis();
        return left > 0 ? (left + 999) / 1000 : 0;
    }

    /** 手动解锁。管理员帮用户解封、或测试收尾用 */
    public synchronized void reset(String account) {
        accounts.remove(account);
    }

    /**
     * 顺手清理，防止长期运行内存只增不减。
     * 每分钟最多扫一遍 —— 登录不是高频操作，这个开销可以忽略。
     */
    private void sweep(long now) {
        if (now - lastSweep < 60_000L) return;
        lastSweep = now;

        Iterator<Map.Entry<String, Account>> it = accounts.entrySet().iterator();
        while (it.hasNext()) {
            Account a = it.next().getValue();
            // 已解锁且没有失败计数 → 这条记录没用了
            if (a.lockedUntil <= now && a.fails == 0) it.remove();
        }
        ips.entrySet().removeIf(e -> now - e.getValue().windowStart >= 60_000L);
    }
}
