package com.example.demo.websocket;

import com.example.demo.service.QwenAsrService;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.BinaryWebSocketHandler;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 语音识别 WebSocket 处理
 *
 * 客户端连 ws://.../voice-ws：
 *   - 发二进制帧（PCM 16kHz 单声道 16bit）→ 识别中间结果实时推回
 *   - 发文本 "end" → 结束识别，推最终结果
 *   - 发文本 "cancel" → 取消
 */
public class VoiceWebSocketHandler extends BinaryWebSocketHandler {

    private final QwenAsrService qwenAsrService;
    private final ConcurrentHashMap<String, QwenAsrService.AsrStream> streams = new ConcurrentHashMap<>();

    public VoiceWebSocketHandler(QwenAsrService qwenAsrService) {
        this.qwenAsrService = qwenAsrService;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        System.out.println(">>> [WS] 语音连接建立: " + session.getId());
        QwenAsrService.AsrStream stream = qwenAsrService.startStreaming(
                intermediate -> sendText(session, "{\"intermediate\":\"" + jsonEscape(intermediate) + "\"}"),
                finalText -> {
                    sendText(session, "{\"final\":\"" + jsonEscape(finalText) + "\"}");
                    closeSession(session);
                },
                err -> {
                    sendText(session, "{\"error\":\"" + jsonEscape(err.getMessage()) + "\"}");
                    closeSession(session);
                });
        streams.put(session.getId(), stream);
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) throws Exception {
        QwenAsrService.AsrStream stream = streams.get(session.getId());
        if (stream != null) {
            stream.sendFrame(message.getPayload().array());
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        String text = message.getPayload();
        QwenAsrService.AsrStream stream = streams.get(session.getId());
        if (stream == null) return;
        if ("end".equals(text)) {
            stream.end();
            streams.remove(session.getId());
            // 兜底：3秒后若 final 没推回，强制关闭（避免连接挂着）
            new Thread(() -> {
                try { Thread.sleep(3000); } catch (InterruptedException ignored) { }
                try { if (session.isOpen()) session.close(); } catch (Exception ignored) { }
            }).start();
        } else if ("cancel".equals(text)) {
            stream.end();
            streams.remove(session.getId());
            closeSession(session);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, org.springframework.web.socket.CloseStatus status) throws Exception {
        QwenAsrService.AsrStream stream = streams.remove(session.getId());
        if (stream != null) stream.end();
    }

    private void sendText(WebSocketSession session, String text) {
        try {
            if (session.isOpen()) {
                synchronized (session) {
                    session.sendMessage(new TextMessage(text));
                }
            }
        } catch (Exception e) {
            System.out.println(">>> [WS] 发送失败: " + e.getMessage());
        }
    }

    private void closeSession(WebSocketSession session) {
        try {
            if (session.isOpen()) session.close();
        } catch (Exception ignored) { }
    }

    private static String jsonEscape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "");
    }
}
