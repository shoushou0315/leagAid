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
    private volatile long sessionCreatedAt = System.currentTimeMillis();

    /** 选人阶段会话防抖窗口：窗口内 phase 抖动（ChampSelect↔其他）不重复新建，只有真正新一局才切换 */
    private static final long SESSION_RETENTION_MS = 5 * 60 * 1000;

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
     *  同一局 gameId 恒定 → 复用同一 sessionId；只有"真正新一局"才切换。
     *  局内 gameId 变化 → 真新局 → 换新会话并清理上一局海克斯历史；
     *  选人阶段：无会话 或 上一个会话绑定的局已结束（超窗）才新建；否则复用，防 LCU 抖动导致反复新建。
     *  大厅/结算等非对局阶段：保留当前会话，不回退、不新建。
     */
    private synchronized void resolveSession(GameState state) {
        String phase = state.getPhase();
        String gameId = state.getCurrentGameId() == null ? "" : state.getCurrentGameId();
        boolean inGame = "InProgress".equals(phase) || "GameStart".equals(phase);
        boolean inChampSelect = "ChampSelect".equals(phase);
        long now = System.currentTimeMillis();

        if (inGame) {
            if (currentSessionId.isEmpty()) {
                // 中途进入局内（未经过选人）：补建会话并绑定 gameId
                currentSessionId = newSessionId();
                latest.setSessionId(currentSessionId);
                boundGameId = gameId;
                System.out.println("[会话] 中途进入局内 → sessionId=" + currentSessionId);
            } else if (!gameId.isEmpty() && !gameId.equals(boundGameId)) {
                // 真·新一局：进入局内且 gameId 变化 → 新会话，清理上一局已选海克斯
                String oldSessionId = currentSessionId;
                currentSessionId = newSessionId();
                latest.setSessionId(currentSessionId);
                boundGameId = gameId;
                System.out.println("[会话] 新一局(局内gameId变化) → sessionId=" + currentSessionId);
                if (!oldSessionId.isEmpty() && !oldSessionId.equals(currentSessionId)) {
                    hexHistoryService.clear(oldSessionId);
                }
            } else if (!gameId.isEmpty()) {
                boundGameId = gameId;   // 同一局，仅记录归属，不换 id
            }
        } else if (inChampSelect) {
            prevPhaseIsChampSelect = true;
            // 选人：无会话，或上一局已结束且超防抖窗口 → 新建；否则复用当前（防阶段抖动）
            boolean fresh = currentSessionId.isEmpty()
                    || (now - sessionCreatedAt > SESSION_RETENTION_MS);
            if (fresh) {
                String oldSessionId = currentSessionId;
                currentSessionId = newSessionId();
                latest.setSessionId(currentSessionId);
                System.out.println("[会话] 选人开始 → sessionId=" + currentSessionId);
                if (!oldSessionId.isEmpty() && !oldSessionId.equals(currentSessionId)) {
                    hexHistoryService.clear(oldSessionId);
                }
            }
        } else {
            prevPhaseIsChampSelect = false;
            boundGameId = "";   // 非对局/结算：保留会话 id，但解除 gameId 绑定，等下一局
        }
    }

    private String newSessionId() {
        sessionCreatedAt = System.currentTimeMillis();
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
