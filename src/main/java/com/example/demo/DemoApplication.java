package com.example.demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class DemoApplication {
    public static void main(String[] args) {
        // 禁用 headless：需要 Robot 截图（海克斯识别）访问屏幕
        System.setProperty("java.awt.headless", "false");
        SpringApplication app = new SpringApplication(DemoApplication.class);
        app.setDefaultProperties(java.util.Map.of(
                "server.port", "8080"
        ));
        app.run(args);
    }
}