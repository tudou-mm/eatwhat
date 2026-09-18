package com.eatwhat.config;

import com.eatwhat.auth.AuthInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;

/**
 * Web 层装配：鉴权拦截器 + 上传目录静态托管 + **前端三端静态托管**。
 *
 * <p>为什么后端要顺手托管前端（v1.9）：
 * 之前前端是 `python dev.py` 另起一个 5173 的静态服务，手机上就得记两个地址
 * —— 而手机上的 `localhost` 指的是手机自己，根本连不上你的电脑。
 * 让后端一起发，就变成「一个地址搞定」，顺带把跨域也消灭了。
 *
 * <p>⚠️ 注意静态资源路径（`/client/**` 等）与接口路径（`/api/**`）**完全不重叠**，
 * 所以 {@link AuthInterceptor} 只挂 `/api/**` 是安全的 —— 前端页面不需要登录。
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final AuthInterceptor authInterceptor;
    private final String uploadDir;
    private final String publicPath;
    private final String webDir;

    public WebConfig(AuthInterceptor authInterceptor,
                     @Value("${eatwhat.upload.dir:./uploads}") String uploadDir,
                     @Value("${eatwhat.upload.publicPath:/uploads}") String publicPath,
                     @Value("${eatwhat.web.dir:..}") String webDir) {
        this.authInterceptor = authInterceptor;
        this.uploadDir = uploadDir;
        this.publicPath = publicPath;
        this.webDir = webDir;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor)
                // /api/client/** 也挂上，但里面绝大多数路径是公开的 ——
                // 具体哪些要身份由 AuthInterceptor.requiredRole 决定，
                // 目前只有「发表评论」需要 client 身份。挂整段是为了
                // 以后加客户端写操作时不用再改这里。
                .addPathPatterns("/api/admin/**", "/api/merchant/**",
                        "/api/upload/**", "/api/client/**")
                // 入驻申请必须是公开的 —— 新商户此刻还没有账号，
                // 拦掉就变成「想入驻先登录」，死锁。
                .excludePathPatterns("/api/merchant/apply");
    }

    /**
     * 访问根路径直接进客户端首页。
     *
     * <p>手机用户输入域名后看到的应该是「刷附近新菜」，而不是一个 404 或目录列表。
     * 没有这条，`https://你的域名/` 会返回 404 —— 演示时最尴尬的一环。
     */
    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addRedirectViewController("/", "/client/feed.html");
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // ---------- 上传的文件（用户产出，运行时才有） ----------
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

        // ---------- 前端三端页面（仓库里的静态文件） ----------
        mountStatic(registry, webDir);
    }

    /**
     * 把仓库根的 `client/`、`merchant/`、`admin/`、`assets/` 挂成静态资源。
     *
     * <p>⚠️ 三条必须守住的规矩：
     *
     * <p><b>① 只挂这四个前缀，绝不写 `/**`。</b>
     * 挂 `/**` 会把 `/api/**` 也吃进去，接口全部变成 404 —— 而且因为
     * 静态处理器优先级低于 Controller，可能「一半接口正常、一半 404」，
     * 排查起来非常费劲。**只挂确定的目录，永远不兜底整个根。**
     *
     * <p><b>② HTML 不能长缓存。</b>
     * 静态资源（css/js/图片）给 1 小时缓存是安全的，但 HTML 一旦被缓存，
     * 你改了页面、手机上却还是旧的 —— 而且刷新也没用（浏览器根本不发请求）。
     * 今天刚在测试的 Chrome profile 上踩过同类问题（见 `fresh_profile()`），
     * 这里直接给 0，让浏览器每次都回源校验。
     *
     * <p><b>③ 目录不存在时不能启动失败。</b>
     * 打包成 jar 部署时，前端文件可能不在 jar 旁边（或者走别的托管方式）。
     * 那种情况下应该只是「没有前端可发」，而不是整个后端起不来。
     */
    private void mountStatic(ResourceHandlerRegistry registry, String dir) {
        Path root = Paths.get(dir).toAbsolutePath().normalize();
        Path[] subs = { root.resolve("client"), root.resolve("merchant"),
                        root.resolve("admin"), root.resolve("assets") };

        boolean any = false;
        for (Path p : subs) {
            if (Files.isDirectory(p)) {
                any = true;
                // 目录名即 URL 前缀，天生长得一样，不用维护映射表
                String name = p.getFileName().toString();
                boolean isAsset = "assets".equals(name);
                registry.addResourceHandler("/" + name + "/**")
                        .addResourceLocations(p.toUri().toString())
                        // ⚠️ 用 setCacheControl 而不是 setCachePeriod。
                        // setCachePeriod(0) 实测会落到 `no-store`（完全不缓存），
                        // 那不是我们想要的「HTML 每次都回源校验、但可以带 ETag」。
                        // 显式写 Cache-Control 语义最清楚，也不受 Spring 版本默认值影响。
                        //
                        //   assets → 1 小时（css/js/图片，内容基本不变，放心缓存）
                        //   页面   → no-cache（不是「不缓存」，是「每次回源校验」。
                        //            配 Last-Modified/ETag 后，没改就回 304，很快）
                        .setCacheControl(isAsset
                                ? CacheControl.maxAge(1, TimeUnit.HOURS)
                                : CacheControl.noCache());
            }
        }

        if (!any) {
            // 不是错误，只是这次部署没带前端。日志里说清楚，免得以为静态托管没生效。
            System.out.println("[WebConfig] 未找到前端静态目录，跳过托管"
                    + "（期望在 " + root + " 下有 client/ merchant/ admin/ assets/）");
            return;
        }
        System.out.println("[WebConfig] 前端静态托管已启用：" + root
                + "  →  /client/** /merchant/** /admin/** /assets/**");
    }
}
