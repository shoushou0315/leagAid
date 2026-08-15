package com.example.demo.controller;

import com.example.demo.service.lcu.VoiceHotkeyService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * 语音辅助接口。
 * 语音识别主流程走 WebSocket（/voice-ws）；热键状态通过 SSE 实时推送驱动录音开始/停止。
 */
@RestController
public class VoiceController {

    /** 全局热键当前状态（兼容查询，新前端用 SSE 推送） */
    @GetMapping("/api/voice/state")
    public Map<String, Object> voiceState() {
        boolean active = VoiceHotkeyService.getInstance().isVoiceActive();
        return Map.of("active", active);
    }

    /** SSE 实时推送 F6 热键状态变化（true=按住/开始录音，false=松开/停止）。连接建立时先推一次当前值。 */
    @GetMapping("/api/voice/events")
    public SseEmitter voiceEvents() {
        SseEmitter emitter = new SseEmitter(0L);  // 0 = 不超时，长连接
        VoiceHotkeyService hotkey = VoiceHotkeyService.getInstance();

        AtomicReference<Consumer<Boolean>> listenerRef = new AtomicReference<>();
        Consumer<Boolean> listener = active -> {
            try {
                emitter.send(SseEmitter.event().data(Map.of("active", active)));
            } catch (Exception e) {
                // 推送失败 = 连接已断开，依赖下方 onCompletion/onTimeout 清理
            }
        };
        listenerRef.set(listener);

        hotkey.addListener(listener);
        emitter.onCompletion(() -> hotkey.removeListener(listenerRef.get()));
        emitter.onTimeout(() -> hotkey.removeListener(listenerRef.get()));
        emitter.onError(t -> hotkey.removeListener(listenerRef.get()));

        // 连接建立先推一次当前值（前端初始化，防丢边沿）
        try {
            emitter.send(SseEmitter.event().data(Map.of("active", hotkey.isVoiceActive())));
        } catch (Exception e) {
            hotkey.removeListener(listener);
        }
        return emitter;
    }
}
