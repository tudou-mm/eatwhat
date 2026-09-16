package com.eatwhat.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * JWT 签发与校验（HS256）。
 *
 * 三个角色：
 *   admin    —— 平台运营，能打 /api/admin/**
 *   merchant —— 商户，只能打 /api/merchant/**，且只能碰自己那家店
 *   client   —— 食客，目前不拦任何接口（客户端免登录浏览），留着备用
 *
 * 密钥从 eatwhat.jwt.secret 读，**上线必须换掉**（或用环境变量
 * EATWHAT_JWT__SECRET 覆盖）。
 */
@Component
public class JwtUtil {

    public static final String CLAIM_ROLE = "role";
    public static final String CLAIM_SHOP_ID = "shopId";
    public static final String CLAIM_NAME = "name";

    /** HS256 要求密钥至少 256 bit，短了直接启动失败，别等到线上才发现 */
    private static final int MIN_SECRET_BYTES = 32;

    private final SecretKey key;
    private final long expireMillis;

    public JwtUtil(@Value("${eatwhat.jwt.secret}") String secret,
                   @Value("${eatwhat.jwt.expireHours:168}") long expireHours) {
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "eatwhat.jwt.secret 至少需要 " + MIN_SECRET_BYTES + " 字节（HS256 要求），当前 "
                            + bytes.length + " 字节。请改长一点，或用环境变量 EATWHAT_JWT__SECRET 覆盖。");
        }
        this.key = Keys.hmacShaKeyFor(bytes);
        this.expireMillis = expireHours * 3600_000L;
    }

    /**
     * 签发。
     *
     * @param subject 主体（admin 用 a_001，商家用 m_&lt;shopId&gt;）
     * @param role    admin / merchant / client
     * @param shopId  商家专属，其他角色传 null
     */
    public String sign(String subject, String role, String shopId, String name) {
        long now = System.currentTimeMillis();
        var builder = Jwts.builder()
                .subject(subject)
                .claim(CLAIM_ROLE, role)
                .issuedAt(new Date(now))
                .expiration(new Date(now + expireMillis));
        // 空值不塞进 payload —— 塞了会多出一对无意义的声明，也让 token 变长
        if (shopId != null) builder.claim(CLAIM_SHOP_ID, shopId);
        if (name != null) builder.claim(CLAIM_NAME, name);
        return builder.signWith(key, Jwts.SIG.HS256).compact();
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
