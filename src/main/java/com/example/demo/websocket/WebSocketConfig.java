package com.example.demo.websocket;

import com.example.demo.service.QwenAsrService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final QwenAsrService qwenAsrService;

    public WebSocketConfig(QwenAsrService qwenAsrService) {
        this.qwenAsrService = qwenAsrService;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(voiceWebSocketHandler(), "/voice-ws").setAllowedOrigins("*");
    }

    @Bean
    public VoiceWebSocketHandler voiceWebSocketHandler() {
        return new VoiceWebSocketHandler(qwenAsrService);
    }
}
