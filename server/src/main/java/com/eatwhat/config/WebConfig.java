package com.eatwhat.config;

import com.eatwhat.auth.AuthInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Web 层装配：鉴权拦截器 + 上传目录的静态托管。
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final AuthInterceptor authInterceptor;
    private final String uploadDir;
    private final String publicPath;

    public WebConfig(AuthInterceptor authInterceptor,
                     @Value("${eatwhat.upload.dir:./uploads}") String uploadDir,
                     @Value("${eatwhat.upload.publicPath:/uploads}") String publicPath) {
        this.authInterceptor = authInterceptor;
        this.uploadDir = uploadDir;
        this.publicPath = publicPath;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/api/admin/**", "/api/merchant/**", "/api/upload/**")
                // 入驻申请必须是公开的 —— 新商户此刻还没有账号，
                // 拦掉就变成「想入驻先登录」，死锁。
                .excludePathPatterns("/api/merchant/apply");
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        Path dir = Paths.get(uploadDir).toAbsolutePath().normalize();
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException("上传目录创建失败：" + dir, e);
        }
        String prefix = publicPath.replaceAll("/+$", "");
        registry.addResourceHandler(prefix + "/**")
                // toUri() 会补上末尾斜杠，缺了它 Spring 拼不出正确路径
                .addResourceLocations(dir.toUri().toString())
                .setCachePeriod(3600);
    }
}
