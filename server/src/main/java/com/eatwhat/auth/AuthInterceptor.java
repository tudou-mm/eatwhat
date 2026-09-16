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
 * 两个刻意的设计：
 * 1. **角色与路径必须对应**。拿着商家 token 打平台端接口直接 403，
 *    不能靠「前端不会这么调」来兜底。
 * 2. **商家只能操作自己那家店**。这里先按 URL 里的 shopId 拦一道，
 *    Controller 再按 @AuthContext 兜一道 —— 双层，是因为漏一道就是越权。
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    /** 从路径里抠出 shopId：/api/merchant/dashboard/s_001 */
    private static final Pattern MERCHANT_PATH_SHOP = Pattern.compile(
            "^/api/merchant/(?:dashboard|dishes|shop|comments|can-publish)/([^/]+)");

    private static final String BEARER = "Bearer ";

    private final JwtUtil jwt;

    public AuthInterceptor(JwtUtil jwt) {
        this.jwt = jwt;
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
            throw new BizException(403, "无权访问该接口（需要 " + need + " 身份，当前 " + role + "）");
        }

        if ("merchant".equals(role)) {
            String target = targetShopId(req, uri);
            if (target != null && !target.equals(shopId)) {
                throw new BizException(403, "只能操作本店数据");
            }
        }

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
