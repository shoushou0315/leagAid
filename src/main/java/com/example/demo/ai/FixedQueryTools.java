package com.example.demo.ai;

import com.example.demo.mapper.HeroMapper;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 固定查询工具（MyBatis）
 *
 * AI 收到问题时先尝试此工具：
 *  - 命中固定查询（英雄胜率/海克斯排名/出装/玩法/排行榜/组合）→ 返回结构化结果
 *  - 未命中 → 返回固定格式的"未命中"提示，AI 再走自由查询
 */
@Component
public class FixedQueryTools {

    private final HeroMapper heroMapper;

    public FixedQueryTools(HeroMapper heroMapper) {
        this.heroMapper = heroMapper;
    }

    @Tool("尝试用固定查询回答。命中英雄后一次性返回该英雄的完整数据包：胜率/Tier + 海克斯排名 + 出装方案 + 玩法档案 + 三连组合。也支持'英雄有了/拿到/刷到 某海克斯'的组合查询。命中返回数据；未命中返回【未命中固定查询，请使用自由查询】")
    public String tryFixedQuery(@P("用户的问题") String question) {
        System.out.println(">>> [Tool] tryFixedQuery 调用: " + question);
        return query(question);
    }

    /** 固定查询公共入口（@Tool 与硬路由 QueryRouter 共用同一份逻辑） */
    public String query(String question) {
        // 1. 英雄排行榜
        if (question.matches(".*(英雄排行|胜率排行|TOP\\d+|最强英雄|哪些英雄强).*")) {
            List<Map<String, Object>> top = heroMapper.getTopHeroes(10);
            if (!top.isEmpty()) return formatTopHeroes(top);
        }

        // 2. 组合查询：英雄 + 有了/拿到/刷到 某海克斯
        String combo = tryHeroAugmentCombo(question);
        if (combo != null) return combo;

        // 3. 提取英雄名（先找英雄）
        String heroName = extractHeroName(question);
        if (heroName == null) {
            return "【未命中固定查询，请使用自由查询】";
        }
        Map<String, Object> hero = heroMapper.findHero(heroName);
        if (hero == null) {
            return "【未命中固定查询，请使用自由查询】";
        }
        Object heroId = hero.get("id");
        String heroDbName = String.valueOf(hero.get("name"));

        // 4. 一次性返回完整数据包（不再按关键词单返回）
        StringBuilder sb = new StringBuilder("【" + heroDbName + " 完整数据】\n");

        // 胜率/Tier
        Map<String, Object> stats = heroMapper.getHeroStats((Integer) heroId);
        if (stats != null && !stats.isEmpty()) {
            sb.append("【数据】").append(formatStats(stats)).append("\n");
        }
        // 海克斯排名
        List<Map<String, Object>> augs = heroMapper.getHeroAugments((Integer) heroId, 5);
        if (!augs.isEmpty()) {
            sb.append("\n").append(formatAugments(heroDbName, augs)).append("\n");
        }
        // 出装方案
        List<Map<String, Object>> builds = heroMapper.getBuilds((Integer) heroId);
        if (!builds.isEmpty()) {
            sb.append("\n").append(formatBuilds(heroDbName, builds)).append("\n");
        }
        // 三连组合
        List<Map<String, Object>> combos = heroMapper.getCombos((Integer) heroId, 3);
        if (!combos.isEmpty()) {
            sb.append("\n").append(formatCombos(heroDbName, combos)).append("\n");
        }
        // 玩法档案
        Map<String, Object> profile = heroMapper.getHeroProfile((Integer) heroId);
        if (profile != null && !profile.isEmpty()) {
            sb.append("\n").append(formatProfile(heroDbName, profile)).append("\n");
        }

        sb.append("\n以上是固定查询的完整数据。如需更深入的机制分析，可再用自由查询(getSynergy/queryDb)。");
        return sb.toString();
    }

    /**
     * 组合查询：问题形如"提莫有了纯粹主义者术士" / "泰隆拿到心之钢"
     * 提取 [英雄名] + [有了/拿到/刷到 后的海克斯关键词] → 查该英雄该海克斯的胜率/排名 + 出装方案
     * 若命中，附带英雄被动/技能档案与海克斯效果，供 AI 直接做联动分析
     */
    private String tryHeroAugmentCombo(String q) {
        String[] markers = {"有了", "拿到", "刷到", "抽到", "选到", "有"};
        for (String m : markers) {
            int idx = q.indexOf(m);
            if (idx <= 0) continue;
            String heroName = q.substring(0, idx).trim();
            heroName = heroName.replaceAll("^(我|帮我|请问|我想|玩到|选了|问一下|想)", "").trim();
            if (heroName.length() < 2 || heroName.length() > 6) continue;

            Map<String, Object> hero = heroMapper.findHero(heroName);
            if (hero == null) continue;
            Object heroId = hero.get("id");
            String heroDbName = String.valueOf(hero.get("name"));

            // "有了X" 之后剩下的关键词（去掉 怎么玩/出什么/搭配 等尾巴）
            String keyword = q.substring(idx + m.length()).trim();
            keyword = keyword.replaceAll("(怎么玩|怎么出装|怎么搭配|出什么装|出什么|怎么样|好不好|行不行|适合吗|配合什么|之后|接下去).*$", "").trim();
            if (keyword.length() < 2) continue;

            List<Map<String, Object>> hits = heroMapper.getHeroAugmentByName((Integer) heroId, keyword);
            if (!hits.isEmpty()) {
                StringBuilder sb = new StringBuilder("【" + heroDbName + " 选到「" + keyword + "」】\n");
                // 1. 该海克斯排名/胜率
                for (Map<String, Object> a : hits) {
                    sb.append("海克斯: ").append(a.get("augment_name")).append("（").append(a.get("tier_name")).append("）\n");
                    sb.append("  排名 #").append(a.get("win_rank")).append(" 胜率 ").append(a.get("win_rate_pct")).append("%\n");
                    if (a.get("pick_rate_pct") != null) sb.append("  选取率 ").append(a.get("pick_rate_pct")).append("%\n");
                }
                // 2. 出装方案（胜率最高的方案优先）
                sb.append("\n【" + heroDbName + " 推荐出装】\n");
                List<Map<String, Object>> builds = heroMapper.getBuilds((Integer) heroId);
                if (builds.isEmpty()) {
                    sb.append("  暂无出装数据\n");
                } else {
                    String curBuild = "";
                    for (Map<String, Object> b : builds) {
                        String bk = "方案" + b.get("build_index") + "组" + b.get("group_index") + "（胜率" + (b.get("win_rate") == null ? "?" : b.get("win_rate")) + "）: ";
                        if (!bk.equals(curBuild)) {
                            curBuild = bk;
                            sb.append("  ").append(bk);
                        }
                        sb.append(b.get("item_name")).append(" ");
                        if (String.valueOf(b.get("slot")).equals("3")) sb.append("\n");
                    }
                }
                // 3. 被动/技能档案 + 海克斯效果 → 供 AI 做联动分析
                Map<String, Object> profile = heroMapper.getHeroProfile((Integer) heroId);
                if (profile != null && !profile.isEmpty()) {
                    sb.append("\n【" + heroDbName + " 技能档案】\n");
                    if (profile.get("passive") != null) sb.append("被动: ").append(profile.get("passive")).append("\n");
                    if (profile.get("spells") != null) sb.append("技能: ").append(profile.get("spells")).append("\n");
                    if (profile.get("ally_tips") != null) sb.append("玩法技巧: ").append(profile.get("ally_tips")).append("\n");
                }
                sb.append("\n请做联动分析：这个海克斯的效果是否被该英雄的被动/技能【白赚转化利用】？结合出装与胜率给出结论。");
                return sb.toString();
            }
        }
        return null;
    }

    /** 从问题中提取英雄名（常见模式） */
    private String extractHeroName(String q) {
        // "X怎么玩" "X选什么海克斯" "X出什么装" "X的胜率" 等，取开头到关键词前
        String[] patterns = {"怎么玩", "选什么海克斯", "选什么", "出什么装", "出什么", "的胜率", "的Tier", "的强度", "出装", "怎么出装"};
        for (String p : patterns) {
            int idx = q.indexOf(p);
            if (idx > 0) {
                String name = q.substring(0, idx).trim();
                // 去掉可能的前缀（"我玩到""我选了""帮我看看"等）
                name = name.replaceAll("^(我|帮我|请问|我想|玩到|选了|问一下|想)", "").trim();
                if (name.length() >= 2 && name.length() <= 6) return name;
            }
        }
        // 尝试"X 怎么玩"带空格
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^([\\u4e00-\\u9fa5]{2,6})\\s+(怎么|选|出|的|玩)").matcher(q);
        if (m.find()) return m.group(1);
        return null;
    }

    private String formatStats(Map<String, Object> s) {
        return String.format("胜率: %s%%  Tier: %s  选取率: %s%%  排名: %s (版本 %s)",
                s.get("win_rate_pct"), s.get("tier"), s.get("pick_rate_pct"), s.get("win_rank"), s.get("version"));
    }

    private String formatAugments(String heroName, List<Map<String, Object>> augs) {
        StringBuilder sb = new StringBuilder("【" + heroName + " 海克斯排名 TOP】\n");
        int i = 1;
        for (Map<String, Object> a : augs) {
            sb.append(i++).append(". ").append(a.get("augment_name"))
                    .append("（").append(a.get("tier_name")).append("）")
                    .append(" 排名").append(a.get("win_rank"))
                    .append(" 胜率").append(a.get("win_rate_pct")).append("%\n");
        }
        return sb.toString().trim();
    }

    private String formatBuilds(String heroName, List<Map<String, Object>> builds) {
        StringBuilder sb = new StringBuilder("【" + heroName + " 出装方案】\n");
        String curBuild = "";
        for (Map<String, Object> b : builds) {
            String bk = "方案" + b.get("build_index") + "组" + b.get("group_index");
            if (!bk.equals(curBuild)) {
                curBuild = bk;
                sb.append("\n").append(bk).append(": ");
            }
            sb.append(b.get("item_name")).append(" ");
        }
        return sb.toString().trim();
    }

    private String formatProfile(String heroName, Map<String, Object> p) {
        StringBuilder sb = new StringBuilder("【" + heroName + " 玩法档案】\n");
        if (p.get("passive") != null) sb.append("被动: ").append(p.get("passive")).append("\n");
        if (p.get("spells") != null) sb.append("技能: ").append(p.get("spells")).append("\n");
        if (p.get("ally_tips") != null) sb.append("玩法技巧: ").append(p.get("ally_tips")).append("\n");
        return sb.toString().trim();
    }

    private String formatCombos(String heroName, List<Map<String, Object>> combos) {
        StringBuilder sb = new StringBuilder("【" + heroName + " 三连组合】\n");
        // 收集所有海克斯 id → 一次查询映射名字
        java.util.Set<Integer> ids = new java.util.HashSet<>();
        for (Map<String, Object> c : combos) {
            Object raw = c.get("augment_ids");
            if (raw != null) {
                for (String s : String.valueOf(raw).split(":")) {
                    try { ids.add(Integer.parseInt(s.trim())); } catch (NumberFormatException ignored) {}
                }
            }
        }
        java.util.Map<Integer, String> idToName = new java.util.HashMap<>();
        if (!ids.isEmpty()) {
            for (Map<String, Object> a : heroMapper.findAugmentNamesByIds(new java.util.ArrayList<>(ids))) {
                idToName.put(((Number) a.get("id")).intValue(), String.valueOf(a.get("name")));
            }
        }
        int i = 1;
        for (Map<String, Object> c : combos) {
            Object raw = c.get("augment_ids");
            String names = "";
            if (raw != null) {
                java.util.List<String> nameList = new java.util.ArrayList<>();
                for (String s : String.valueOf(raw).split(":")) {
                    try {
                        int id = Integer.parseInt(s.trim());
                        nameList.add(idToName.getOrDefault(id, s));
                    } catch (NumberFormatException ignored) {
                        nameList.add(s);
                    }
                }
                names = String.join("+", nameList);
            }
            sb.append(i++).append(". ").append(names)
                    .append(" 胜率").append(c.get("win_rate_pct")).append("%\n");
        }
        return sb.toString().trim();
    }

    private String formatTopHeroes(List<Map<String, Object>> top) {
        StringBuilder sb = new StringBuilder("【英雄排行榜 TOP】\n");
        int i = 1;
        for (Map<String, Object> h : top) {
            sb.append(i++).append(". ").append(h.get("name"))
                    .append(" Tier:").append(h.get("tier"))
                    .append(" 胜率:").append(h.get("win_rate_pct")).append("%\n");
        }
        return sb.toString().trim();
    }
}
