package com.eatwhat;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 「吃什么」本地生活推荐平台 · 后端启动类
 */
@SpringBootApplication
public class EatWhatApplication {

    public static void main(String[] args) {
        SpringApplication.run(EatWhatApplication.class, args);
        System.out.println("""

                ============================================
                  吃什么 · 后端已启动
                  API 根路径 : http://localhost:8080/api
                  健康检查   : http://localhost:8080/api/health
                  H2 控制台  : http://localhost:8080/h2-console
                ============================================
                """);
    }
}
