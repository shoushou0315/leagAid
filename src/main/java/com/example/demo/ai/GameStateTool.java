package com.example.demo.ai;

import com.example.demo.model.GameState;
import com.example.demo.service.GameStateService;
import com.example.demo.service.HexHistoryService;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 游戏实时状态工具：AI 需要了解当前对局时调用。
 *
 * 覆盖：阶段、自己英雄、板凳英雄、我方/对方阵容、玩家熟练度与装备、已选海克斯。
 * 结合 queryDb 可查板凳英雄胜率做推荐。
 */
@Component
public class GameStateTool {

    private final GameStateService gameStateService;
    private final HexHistoryService hexHistoryService;

    public GameStateTool(GameStateService gameStateService, HexHistoryService hexHistoryService) {
        this.gameStateService = gameStateService;
        this.hexHistoryService = hexHistoryService;
    }

    /**
     * 获取当前对局的实时状态：阶段、我玩的英雄、板凳可选英雄、我方与对方阵容（含英雄熟练度/当前装备）、已选海克斯。
     * 用于回答"哪些英雄好赢/选哪个/出什么装备/怎么打对面/后续选什么海克斯"等对局相关问题。
     */
    @Tool("获取当前对局的实时状态：阶段、我玩的英雄、板凳可选英雄、我方/对方阵容（含熟练度与装备）、已选海克斯。问'选人/哪些好赢/选哪个英雄/出什么装备/怎么打对面/选海克斯'等问题时先调用本工具了解当前对局")
    public String getGameState(@dev.langchain4j.agent.tool.ToolMemoryId String sessionId) {
        try {
            GameState state = gameStateService.getLatest();
            StringBuilder sb = new StringBuilder();
            if (state == null) {
                sb.append("无对局数据（可能未进入游戏）\n");
            } else {
                sb.append("阶段：").append(state.getPhase() == null ? "未知" : state.getPhase()).append("\n");
                if (state.getMyChampion() != null && !state.getMyChampion().isEmpty()) {
                    sb.append("我玩的英雄：").append(state.getMyChampion()).append("\n");
                }
                // 板凳（选人阶段可选英雄）
                if (state.getBench() != null && !state.getBench().isEmpty()) {
                    sb.append("板凳可选英雄：").append(String.join("、", state.getBench())).append("\n");
                }
                // 双方阵容
                List<GameState.GamePlayer> players = state.getPlayers();
                if (players != null && !players.isEmpty()) {
                    List<String> myTeam = players.stream()
                            .filter(p -> !"对面".equals(p.getTeam()))
                            .map(GameStateTool::describe)
                            .collect(Collectors.toList());
                    List<String> enemyTeam = players.stream()
                            .filter(p -> "对面".equals(p.getTeam()))
                            .map(GameStateTool::describe)
                            .collect(Collectors.toList());
                    if (!myTeam.isEmpty()) sb.append("我方阵容：\n").append(String.join("\n", myTeam)).append("\n");
                    if (!enemyTeam.isEmpty()) sb.append("对方阵容：\n").append(String.join("\n", enemyTeam)).append("\n");
                }
            }
            // 已选海克斯（当前局）
            if (sessionId != null && !sessionId.isBlank()) {
                List<String> hexHistory = hexHistoryService.get(sessionId);
                if (!hexHistory.isEmpty()) {
                    sb.append("本局已选海克斯：").append(String.join("、", hexHistory)).append("\n");
                }
            }
            return sb.toString();
        } catch (Exception e) {
            // 降级：Redis 挂/反序列化失败时不抛异常，返回友好提示，让对话继续
            return "对局数据暂时不可用（" + e.getMessage() + "），请稍后再试。";
        }
    }

    /**
     * 记录玩家本局已选的海克斯强化。用户确认选择后调用（如用户说"我选了XX"），后续推荐会结合已选海克斯。
     */
    @Tool("记录玩家本局已选的海克斯强化名称。当用户明确说'我选了XX/拿了XX/选了哪个海克斯'时，调用本工具保存，避免重复推荐同一海克斯")
    public String saveHex(@dev.langchain4j.agent.tool.ToolMemoryId String sessionId,
                          @P("已选的海克斯名称，如：超凡邪恶") String hexName) {
        if (sessionId == null || sessionId.isBlank()) return "会话ID缺失，无法保存";
        List<String> history = hexHistoryService.add(sessionId, hexName);
        return "已记录你选了海克斯【" + hexName + "】，本局已选：" + String.join("、", history);
    }

    private static String describe(GameState.GamePlayer p) {
        StringBuilder sb = new StringBuilder();
        if (p.getName() != null && !p.getName().isEmpty()) sb.append(p.getName()).append(" ");
        if (p.getChampion() != null && !p.getChampion().isEmpty()) sb.append("英雄:").append(p.getChampion());
        if (p.getMastery() != null && !p.getMastery().isEmpty()) sb.append(" 熟练度:").append(p.getMastery());
        if (p.getGames() > 0) sb.append(" 近").append(p.getGames()).append("把胜率:").append((int)p.getWinRate()).append("%");
        if (p.getKda() != null && !p.getKda().isEmpty()) sb.append(" KDA:").append(p.getKda());
        if (p.getStyle() != null && !p.getStyle().isEmpty()) sb.append(" 风格:").append(p.getStyle());
        if (p.getItems() != null && !p.getItems().isEmpty()) sb.append(" 装备:").append(p.getItems());
        if (p.getLevel() > 0) sb.append(" 等级:").append(p.getLevel());
        return sb.toString().trim();
    }
}
