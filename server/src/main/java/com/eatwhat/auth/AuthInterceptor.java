package com.eatwhat.auth;

import com.eatwhat.common.BizException;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 统一鉴权拦截器。
 *
 * 规则：
 *   /api/admin/**     → 需要 admin
 *   /api/merchant/**  → 需要 merchant（且只能碰自己那家店）
 *   /api/upload/**    → admin 或 merchant 都行
 *   其余（/api/client/**、/api/auth/**、/api/login、/api/health）→ 公开
 *
 * 三道闸，顺序不能换：
 * 1. **角色与路径必须对应**。拿着商家 token 打平台端接口直接 403，
 *    不能靠「前端不会这么调」来兜底。
 * 2. **商家只能操作自己那家店**。这里先按 URL 里的 shopId 拦一道，
 *    Controller 再按 @AuthContext 兜一道 —— 双层，是因为漏一道就是越权。
 * 3. **会话是否仍然有效**（{@link SessionGuard}）。签名对、没过期，
 *    不代表还算数 —— 店可能已经被封了，token 也可能已被主动作废。
 *    这一道必须放在最后，因为它要查库，前面能挡掉的先挡掉，少一次查询。
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    /** 从路径里抠出 shopId：/api/merchant/dashboard/s_001 */
    private static final Pattern MERCHANT_PATH_SHOP = Pattern.compile(
            "^/api/merchant/(?:dashboard|dishes|shop|comments|can-publish)/([^/]+)");

    private static final String BEARER = "Bearer ";

    private final JwtUtil jwt;
    private final SessionGuard sessionGuard;

    public AuthInterceptor(JwtUtil jwt, SessionGuard sessionGuard) {
        this.jwt = jwt;
        this.sessionGuard = sessionGuard;
    }

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse resp, Object handler) {
        // 预检请求不带 Authorization，必须放行，否则跨域直接全挂
        if (HttpMethod.OPTIONS.matches(req.getMethod())) return true;

        String uri = req.getRequestURI();
        String need = requiredRole(uri);
        if (need == null) return true;                 // 公开接口

        String token = bearerToken(req);
        if (token == null) {
            throw new BizException(401, "未登录：请在请求头带上 Authorization: Bearer <token>");
        }

        Claims claims;
        try {
            claims = jwt.verify(token);
        } catch (ExpiredJwtException e) {
            throw new BizException(401, "登录已过期，请重新登录");
        } catch (JwtException | IllegalArgumentException e) {
            throw new BizException(401, "登录凭证无效");
        }

        String role = claims.get(JwtUtil.CLAIM_ROLE, String.class);
        String shopId = claims.get(JwtUtil.CLAIM_SHOP_ID, String.class);
        String name = claims.get(JwtUtil.CLAIM_NAME, String.class);

        boolean allowed = "staff".equals(need)
                ? ("admin".equals(role) || "merchant".equals(role))
                : need.equals(role);
        if (!allowed) {
            // 这条回 403 而不是 401：身份是真的、token 也有效，只是不该走这个门
            throw new BizException(403, "无权访问该接口（需要 " + need + " 身份，当前 " + role + "）");
        }

        if ("merchant".equals(role)) {
            String target = targetShopId(req, uri);
            if (target != null && !target.equals(shopId)) {
                throw new BizException(403, "只能操作本店数据");
            }
        }

        // 最后一道：签名对、没过期，也不代表还算数 —— 店可能已被封、token 可能已被作废
        sessionGuard.check(role, claims.getSubject(), shopId, JwtUtil.tokenVersionOf(claims));

        AuthContext.set(new AuthContext.Principal(role, claims.getSubject(), shopId, name));
        return true;
    }

    /** ThreadLocal 一定要清，线程池复用下不清会串号 */
    @Override
    public void afterCompletion(HttpServletRequest req, HttpServletResponse resp,
                                Object handler, Exception ex) {
        AuthContext.clear();
    }

    /** 返回 null 表示公开；"staff" 表示 admin / merchant 任一 */
    private static String requiredRole(String uri) {
        if (uri.startsWith("/api/admin/")) return "admin";
        if (uri.startsWith("/api/merchant/")) return "merchant";
        if (uri.startsWith("/api/upload")) return "staff";
        // 客户端整体是免登录浏览的，**只有发表评论必须带身份**。
        // 原先这个接口从请求体里取 userId，等于谁都能以别人的名义评论；
        // 被封号的用户换个 userId 也照样发 —— 封号形同虚设。
        // 现在身份一律从 token 取，请求体里那个字段直接不看了。
        if (uri.startsWith("/api/client/dish/") && uri.endsWith("/comment")) return "client";
        return null;
    }

    private static String bearerToken(HttpServletRequest req) {
        String h = req.getHeader("Authorization");
        if (h == null) return null;
        String v = h.trim();
        if (v.regionMatches(true, 0, BEARER, 0, BEARER.length())) {
            v = v.substring(BEARER.length()).trim();
        }
        return v.isEmpty() ? null : v;
    }

    /** 请求里声明的目标店铺：查询参数优先，其次路径变量 */
    private static String targetShopId(HttpServletRequest req, String uri) {
        String q = req.getParameter("shopId");
        if (q != null && !q.isBlank()) return q;
        Matcher m = MERCHANT_PATH_SHOP.matcher(uri);
        return m.find() ? m.group(1) : null;
    }
}
