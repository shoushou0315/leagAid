package com.example.demo.controller;

import com.example.demo.service.lcu.VoiceHotkeyService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 语音辅助接口。
 * 语音识别主流程走 WebSocket（/voice-ws）；热键状态由前端轮询 /api/voice/state 驱动录音。
 * 注：曾用 SSE 推送（/api/voice/events），但断线/重连场景触发 Tomcat async ERROR，改回轮询更稳定。
 */
@RestController
public class VoiceController {

    /** 全局热键当前状态（F6 是否按住），前端轮询驱动录音（全屏游戏可用） */
    @GetMapping("/api/voice/state")
    public Map<String, Object> voiceState() {
        boolean active = VoiceHotkeyService.getInstance().isVoiceActive();
        return Map.of("active", active);
    }
}
