package com.example.demo.service.lcu;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Spring 启动后拉起 leagAid 采集（后台，不阻塞容器）。
 *
 * 处理边界：项目先启动、LOL 客户端后打开的情况。
 * 每隔 5s 尝试连接 LCU，连上才启动采集；后续掉线也由内部重试。
 *
 * 语音热键（F6）与 LCU 解耦：Spring 启动即注册，不依赖游戏客户端——
 * 游戏没开也能按 F6 语音对话，游戏开了采集才补充对局数据。
 */
@Component
public class LeagAidRunner implements ApplicationRunner {

    @Override
    public void run(ApplicationArguments args) {
        // 全局热键独立启动（全屏游戏可用），不依赖 LCU 连接
        VoiceHotkeyService.startStatic();
        new Thread(() -> {
            System.out.println("[采集] 等待 LOL 客户端...（游戏未开则静默重试）");
            boolean firstFail = true;
            while (true) {
                if (AutoWatcher.connectAndStart()) {
                    return;
                }
                if (firstFail) {
                    System.out.println("[采集] LCU 未就绪，后台每 5s 重试，游戏打开后自动连接");
                    firstFail = false;
                }
                try { Thread.sleep(5000); } catch (InterruptedException e) { return; }
            }
        }, "leagAid-bootstrap").start();
    }
}
