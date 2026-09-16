package com.eatwhat.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 跨域配置。
 *
 * ⚠️ 这里**不再是 `*`**。之前开发期全放开，等于任何网站都能带着用户的
 * 浏览器去调我们的接口，配合 Cookie/Authorization 就是 CSRF 的温床。
 * 现在改成从 `eatwhat.cors.allowedOrigins` 读白名单。
 *
 * 关于 `file://` 直开：那种情况 Origin 是 `null`，不在白名单里会被拦。
 * 原型阶段请统一用 `python dev.py` 起本地静态服务（5173），
 * 三个端都在白名单里，不会有这个问题。
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    private final String[] allowedOrigins;

    public CorsConfig(@Value("${eatwhat.cors.allowedOrigins}") String[] allowedOrigins) {
        this.allowedOrigins = allowedOrigins;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns(allowedOrigins)
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                // Authorization 必须显式允许，否则跨域请求过不了预检
                .allowedHeaders("Content-Type", "Authorization", "Accept", "X-Requested-With")
                .exposedHeaders("Authorization")
                .allowCredentials(true)
                .maxAge(3600);

        // 上传后的文件也要能被前端页面加载（<img src> 走的是这个前缀）
        registry.addMapping("/uploads/**")
                .allowedOriginPatterns(allowedOrigins)
                .allowedMethods("GET", "HEAD", "OPTIONS")
                .maxAge(3600);
    }
}
