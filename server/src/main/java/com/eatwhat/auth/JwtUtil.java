package com.eatwhat.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Date;

/**
 * JWT 签发与校验（HS256）。
 *
 * 三个角色：
 *   admin    —— 平台运营，能打 /api/admin/**
 *   merchant —— 商户，只能打 /api/merchant/**，且只能碰自己那家店
 *   client   —— 食客，目前不拦任何接口（客户端免登录浏览），留着备用
 *
 * 密钥从 `eatwhat.jwt.secret` 读，但配置里写的是
 * `${EATWHAT_JWT_SECRET:<开发默认值>}` —— **密钥本身不进代码仓库**。
 *
 * ⚠️ 两条硬规则：
 * 1. 启动时校验至少 32 字节（HS256 要求），不够直接起不来。
 * 2. 如果**跑在 prod profile 下却还在用开发默认密钥**，直接拒绝启动。
 *    密钥泄漏 = 任何人都能自己签一张 admin token，这比启动失败严重得多。
 */
@Slf4j
@Component
public class JwtUtil {

    public static final String CLAIM_ROLE = "role";
    public static final String CLAIM_SHOP_ID = "shopId";
    public static final String CLAIM_NAME = "name";

    /**
     * 登录态版本号。签发的 token 里带着它，每次请求与库里的当前值比对。
     *
     * JWT 天生是「签发了就管不了」的 —— 校验只看签名和过期时间，服务端说不上话。
     * 带上版本号之后就补上了这个短板：封店 / 改密 / 登出时把库里的值 +1，
     * 所有旧 token 下一毫秒就作废，不用等它自然过期。
     */
    public static final String CLAIM_TV = "tv";

    /** HS256 要求密钥至少 256 bit，短了直接启动失败，别等到线上才发现 */
    private static final int MIN_SECRET_BYTES = 32;

    /**
     * 开发用默认密钥。**必须与 application.yml 里的默认值一字不差**，
     * 否则 prod 校验形同虚设。
     */
    public static final String DEV_SECRET = "eatwhat-dev-only-secret-please-override-32b";

    private final SecretKey key;
    private final long expireMillis;

    public JwtUtil(@Value("${eatwhat.jwt.secret}") String secret,
                   @Value("${eatwhat.jwt.expireHours:168}") long expireHours,
                   Environment env) {
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "eatwhat.jwt.secret 至少需要 " + MIN_SECRET_BYTES + " 字节（HS256 要求），当前 "
                            + bytes.length + " 字节。请改长一点，并用环境变量 EATWHAT_JWT_SECRET 注入。");
        }

        boolean prod = Arrays.asList(env.getActiveProfiles()).contains("prod");
        if (DEV_SECRET.equals(secret)) {
            if (prod) {
                throw new IllegalStateException(
                        "生产环境（prod profile）仍在用开发默认 JWT 密钥。"
                                + "请设置环境变量 EATWHAT_JWT_SECRET 为一段随机的 32 字节以上字符串。"
                                + "用默认密钥上线，等于任何人都能自己签一张 admin token。");
            }
            log.warn("──────────────────────────────────────────────────────────");
            log.warn(" eatwhat.jwt.secret 用的是【开发默认密钥】，仅供本地调试。");
            log.warn(" 部署前务必注入环境变量 EATWHAT_JWT_SECRET。");
            log.warn("──────────────────────────────────────────────────────────");
        }

        this.key = Keys.hmacShaKeyFor(bytes);
        this.expireMillis = expireHours * 3600_000L;
    }

    /**
     * 签发。
     *
     * @param subject      主体（admin 用 a_001，商家用 m_&lt;shopId&gt;，食客用 u_xxx）
     * @param role         admin / merchant / client
     * @param shopId       商家专属，其他角色传 null
     * @param name         展示名
     * @param tokenVersion 当前登录态版本号，见 {@link #CLAIM_TV}
     */
    public String sign(String subject, String role, String shopId, String name, int tokenVersion) {
        long now = System.currentTimeMillis();
        var builder = Jwts.builder()
                .subject(subject)
                .claim(CLAIM_ROLE, role)
                .claim(CLAIM_TV, tokenVersion)
                .issuedAt(new Date(now))
                .expiration(new Date(now + expireMillis));
        // 空值不塞进 payload —— 塞了会多出一对无意义的声明，也让 token 变长
        if (shopId != null) builder.claim(CLAIM_SHOP_ID, shopId);
        if (name != null) builder.claim(CLAIM_NAME, name);
        return builder.signWith(key, Jwts.SIG.HS256).compact();
    }

    /**
     * 从 payload 里取登录态版本号。
     * 老 token（v1.4 之前签发的）没有这个声明，一律当 0 —— 库里默认值也是 0，
     * 所以老 token 不会因为「缺声明」被误判失效。
     */
    public static int tokenVersionOf(Claims claims) {
        Object v = claims.get(CLAIM_TV);
        if (v instanceof Number n) return n.intValue();
        if (v != null) {
            try { return Integer.parseInt(String.valueOf(v)); } catch (NumberFormatException ignored) { }
        }
        return 0;
    }

    /**
     * 校验并解出 payload。
     * 签名不对 / 已过期 / 结构损坏都会抛 JwtException，由调用方翻译成 401。
     */
    public Claims verify(String token) {
        Jws<Claims> jws = Jwts.parser().verifyWith(key).build().parseSignedClaims(token);
        return jws.getPayload();
    }

    public long getExpireSeconds() {
        return expireMillis / 1000;
    }
}
