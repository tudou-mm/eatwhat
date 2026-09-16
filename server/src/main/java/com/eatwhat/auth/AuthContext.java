package com.eatwhat.auth;

import com.eatwhat.common.BizException;

/**
 * 当前请求的登录身份（ThreadLocal）。
 *
 * 由 {@link AuthInterceptor} 校验完 token 后写入，请求结束清理。
 * Controller / Service 需要「我是谁」时从这里取，**不要再信任前端传来的 shopId**。
 */
public final class AuthContext {

    /** @param role admin / merchant / client */
    public record Principal(String role, String subject, String shopId, String name) {}

    private static final ThreadLocal<Principal> HOLDER = new ThreadLocal<>();

    private AuthContext() {}

    public static void set(Principal p) { HOLDER.set(p); }

    /** 未登录时为 null（公开接口就是这种情况，属正常） */
    public static Principal get() { return HOLDER.get(); }

    public static void clear() { HOLDER.remove(); }

    public static String role() {
        Principal p = HOLDER.get();
        return p == null ? null : p.role();
    }

    public static String shopId() {
        Principal p = HOLDER.get();
        return p == null ? null : p.shopId();
    }

    /**
     * 断言「这家店就是我」。商家端所有带 shopId 的操作都要过这一关。
     *
     * 为什么不能只靠前端传对参数：改一个 URL 里的 id 就能操作别家店的数据，
     * 这类漏洞（IDOR）光靠前端约束是拦不住的。
     */
    public static void assertSelf(String shopId) {
        if (shopId == null || shopId.isBlank()) return;
        String me = shopId();
        if (me == null) return;                       // 未登录走到这说明是公开接口，交给业务层自己判断
        if (!me.equals(shopId)) {
            throw new BizException(403, "只能操作本店数据");
        }
    }
}
