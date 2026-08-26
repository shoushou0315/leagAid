package com.example.demo.ai;

import com.example.demo.service.HexHistoryService;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 代码前置取数：按意图先调 getGameState 拿到实时对局数据，并注入"用户已拥有海克斯"（口头确认即固化），
 * 拼成注入前缀喂给模型。目标是"漏调工具也有数据、不凭记忆编造、前提不健忘"。
 */
@Component
public class GameContextInjector {

    private final GameStateTool gameStateTool;
    private final HexHistoryService hexHistoryService;

    public GameContextInjector(GameStateTool gameStateTool, HexHistoryService hexHistoryService) {
        this.gameStateTool = gameStateTool;
        this.hexHistoryService = hexHistoryService;
    }

    /** 识别用户口述的"我拿到了/我选了 XX海克斯"，写入已拥有槽（只存海克斯，先验证机制） */
    public void recordOwned(String sessionId, String message) {
        if (sessionId == null || sessionId.isBlank() || message == null) return;
        String[] triggers = {"我拿到", "我拿了", "我选到", "我选了", "拿到了", "拿到", "选到", "选了"};
        for (String tr : triggers) {
            int idx = message.indexOf(tr);
            if (idx < 0) continue;
            String after = message.substring(idx + tr.length());
            for (String part : after.split("[，,、；;和及\\s]+")) {
                String name = part.replaceAll("[吗啊呢吧的了]+$", "").trim();
                if (name.length() >= 2 && name.length() <= 8) {
                    hexHistoryService.add(sessionId, name);
                }
            }
            return; // 只处理首个触发词，够用
        }
    }

    /** 返回可注入的前缀（可能为空字符串）。按意图叠加、去重，并注入已拥有海克斯前提。 */
    public String inject(String sessionId, List<Intent> intents) {
        Set<String> injected = new LinkedHashSet<>();
        // 已拥有海克斯：口头确认即固化，作为每轮确定性前提（无游戏时也生效）
        List<String> owned = hexHistoryService.get(sessionId);
        if (!owned.isEmpty()) {
            injected.add("用户已拥有海克斯：" + String.join("、", owned));
        }
        for (Intent it : intents) {
            switch (it) {
                case HEX_PICK, BUILD, COUNTER, SYNERGY -> {
                    String state = gameStateTool.getGameState(sessionId);
                    if (state != null && !state.isBlank()) injected.add(state);
                }
                default -> { }
            }
        }
        if (injected.isEmpty()) return "";
        return "【以下数据为确定性依据，请直接基于它回答；对局数据无需重复调用 getGameState/recognizeHex 获取同一信息】\n"
                + String.join("\n", injected);
    }
}
