package com.example.demo.controller;

import com.example.demo.service.lcu.VoiceHotkeyService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 语音辅助接口（当前仅热键状态）。
 * 语音识别主流程走 WebSocket（/voice-ws），前端轮询此接口驱动录音开始/停止。
 */
@RestController
public class VoiceController {

    /** 全局热键状态（F6 是否按住），前端轮询驱动录音（全屏游戏可用） */
    @GetMapping("/api/voice/state")
    public Map<String, Object> voiceState() {
        boolean active = VoiceHotkeyService.getInstance().isVoiceActive();
        return Map.of("active", active);
    }
}
