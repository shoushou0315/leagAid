package com.example.demo.controller;

import com.example.demo.model.GameState;
import com.example.demo.service.ConsultantService;
import com.example.demo.service.GameStateService;
import com.example.demo.service.QwenVisionService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

/**
 * 游戏实时接口：状态快照 + 海克斯一键识别（识图 → LLM 推荐）
 */
@RestController
@RequestMapping("/api/game")
public class GameController {

    private final GameStateService gameStateService;
    private final QwenVisionService visionService;
    private final ConsultantService consultantService;

    public GameController(GameStateService gameStateService,
                          QwenVisionService visionService,
                          ConsultantService consultantService) {
        this.gameStateService = gameStateService;
        this.visionService = visionService;
        this.consultantService = consultantService;
    }

    /** 当前游戏状态快照 */
    @GetMapping("/state")
    public GameState state() {
        return gameStateService.getLatest();
    }

    /** 一键识别海克斯：识图 → 拼进问题 → LLM 推荐 */
    @GetMapping(value = "/hex/recognize", produces = "text/event-stream;charset=UTF-8")
    public Flux<String> recognizeHex(@RequestParam(required = false) String sessionId) {
        // 未传 sessionId 时用游戏会话（一局一轮对话）
        String sid = (sessionId == null || sessionId.isBlank())
                ? gameStateService.getCurrentSessionId()
                : sessionId;
        List<String> options = visionService.recognizeHexOptions();
        if (options.isEmpty()) {
            return Flux.just("【识别失败】未能识别海克斯选项，请确认屏幕可见海克斯三选一。");
        }
        String question = "屏幕上的三个海克斯是：" + String.join(" / ", options)
                + "。请分析这三个海克斯，推荐最优选择并说明理由。";
        return consultantService.chat(sid, question);
    }
}
