package com.example.demo.service.lcu;

import com.github.kwhat.jnativehook.GlobalScreen;
import com.github.kwhat.jnativehook.keyboard.NativeKeyEvent;
import com.github.kwhat.jnativehook.keyboard.NativeKeyListener;
import org.springframework.stereotype.Component;

/**
 * 全局热键语音触发（全屏游戏也可用，JNativeHook 捕获键盘事件不受窗口焦点影响）。
 *
 * F6 按下 → voiceActive=true；F6 松开 → voiceActive=false。
 * 前端轮询 /api/voice/state 读取该标志，控制浏览器录音开始/停止。
 *
 * 静态单例：AutoWatcher 采集启动时通过 startStatic() 拉起。
 */
@Component
public class VoiceHotkeyService {

    private static final VoiceHotkeyService INSTANCE = new VoiceHotkeyService();
    private static volatile boolean started = false;

    private volatile boolean voiceActive = false;

    public static VoiceHotkeyService getInstance() { return INSTANCE; }

    /** 启动全局热键监听（幂等） */
    public static void startStatic() {
        INSTANCE.start();
    }

    private synchronized void start() {
        if (started) return;
        started = true;
        try {
            GlobalScreen.registerNativeHook();
            GlobalScreen.addNativeKeyListener(new NativeKeyListener() {
                @Override
                public void nativeKeyPressed(NativeKeyEvent e) {
                    if (e.getKeyCode() == NativeKeyEvent.VC_F6) {
                        voiceActive = true;
                    }
                }

                @Override
                public void nativeKeyReleased(NativeKeyEvent e) {
                    if (e.getKeyCode() == NativeKeyEvent.VC_F6) {
                        voiceActive = false;
                    }
                }
            });
            System.out.println("[语音热键] F6 全局热键已注册（全屏可用）");
        } catch (Exception e) {
            System.out.println("[语音热键] 注册失败: " + e.getMessage());
        }
    }

    /** F6 当前是否按住 */
    public boolean isVoiceActive() { return voiceActive; }
}
