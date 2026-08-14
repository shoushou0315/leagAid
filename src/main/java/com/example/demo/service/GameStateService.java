package com.example.demo.service;

import com.example.demo.model.GameState;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import redis.clients.jedis.Jedis;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 游戏实时快照服务：每 2s 读 Redis leagaid:state，缓存最新 GameState。
 * 同时管理"一轮对话"的会话锚点：新一局自动生成 sessionId。
 *
 * 会话锚定规则：
 *   - 进入 InProgress 且 gameId 与当前会话绑定的不同（或无绑定）→ 新一局 → 新 sessionId
 *   - 否则（选人阶段/同一局重连）→ 复用当前 sessionId
 */
@Service
public class GameStateService {

    private final String redisHost;
    private final int redisPort;
    private final String gameStateKey;
    private final HexHistoryService hexHistoryService;
    private final ObjectMapper mapper = new ObjectMapper();

    private volatile GameState latest = new GameState();
    private volatile long lastLoad = 0;
    private volatile String currentSessionId = "";
    private volatile String boundGameId = "";   // 当前会话绑定的局内 gameId
    private volatile boolean prevPhaseIsChampSelect = false;

    public GameStateService(@Value("${app.redis.host:127.0.0.1}") String redisHost,
                            @Value("${app.redis.port:6379}") int redisPort,
                            @Value("${app.game-state-key:leagaid:state}") String gameStateKey,
                            HexHistoryService hexHistoryService) {
        this.redisHost = redisHost;
        this.redisPort = redisPort;
        this.gameStateKey = gameStateKey;
        this.hexHistoryService = hexHistoryService;
    }

    @Scheduled(fixedDelay = 2000)
    public void refresh() {
        try (Jedis jedis = new Jedis(redisHost, redisPort)) {
            String json = jedis.get(gameStateKey);
            if (json == null || json.isBlank()) return;
            GameState state = mapper.readValue(json, GameState.class);
            if (state.getUpdatedAt() >= lastLoad) {
                latest = state;
                lastLoad = state.getUpdatedAt();
                resolveSession(state);
            }
        } catch (Exception e) {
            // Redis 不可用时保留旧快照
        }
    }

    /** 会话锚定：
     *  选人阶段 = 一局对话起点，生成 sessionId（时间戳）一局到底，不换 id
     *  局内 = gameId 绑定到当前 sessionId（仅用于重连识别/新局校验），不改 id
     *  中途进入局内（没经过选人）→ 用 gameId 补建 sessionId
     */
    private synchronized void resolveSession(GameState state) {
        String phase = state.getPhase();
        String gameId = state.getCurrentGameId() == null ? "" : state.getCurrentGameId();
        boolean inGame = "InProgress".equals(phase) || "GameStart".equals(phase);

        if ("ChampSelect".equals(phase)) {
            // 新一局起点：上一阶段不是选人，或还没有会话 → 建新 sessionId
            if (currentSessionId.isEmpty() || !prevPhaseIsChampSelect) {
                String oldSessionId = currentSessionId;
                currentSessionId = newSessionId();
                latest.setSessionId(currentSessionId);
                System.out.println("[会话] 选人开始 → sessionId=" + currentSessionId);
                // 清理上一局已选海克斯历史
                if (!oldSessionId.isEmpty() && !oldSessionId.equals(currentSessionId)) {
                    hexHistoryService.clear(oldSessionId);
                }
            }
            prevPhaseIsChampSelect = true;
        } else {
            prevPhaseIsChampSelect = false;
        }

        if (inGame) {
            if (currentSessionId.isEmpty()) {
                // 中途进入局内（未经过选人）：补建会话并绑定 gameId
                currentSessionId = newSessionId();
                latest.setSessionId(currentSessionId);
                boundGameId = gameId;
                System.out.println("[会话] 中途进入局内 → sessionId=" + currentSessionId);
            } else {
                // 已有会话：绑定 gameId（不换 id，只记录归属）
                if (!gameId.isEmpty()) boundGameId = gameId;
            }
        }

        if (!inGame && !"ChampSelect".equals(phase)) {
            // 回大厅/结算：清空绑定，下一局选人重新触发
            boundGameId = "";
        }
    }

    private String newSessionId() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
    }

    public GameState getLatest() {
        GameState s = latest;
        if (s != null && s.getSessionId() == null) s.setSessionId(currentSessionId);
        return s;
    }

    /** 当前对话会话 ID（无游戏时也返回，前端一直用它） */
    public String getCurrentSessionId() {
        return currentSessionId.isEmpty() ? newSessionId() : currentSessionId;
    }

    /** 当前对局锚点 gameId（无对局时为空） */
    public String getCurrentGameId() {
        GameState s = latest;
        return s == null ? "" : (s.getCurrentGameId() == null ? "" : s.getCurrentGameId());
    }
}
