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
 *  - 命中固定查询（英雄胜率/海克斯排名/出装/玩法/排行榜）→ 返回结构化结果
 *  - 未命中 → 返回固定格式的"未命中"提示，AI 再走自由查询
 */
@Component
public class FixedQueryTools {

    private final HeroMapper heroMapper;

    public FixedQueryTools(HeroMapper heroMapper) {
        this.heroMapper = heroMapper;
    }

    @Tool("尝试用固定查询回答。命中英雄后一次性返回该英雄的完整数据包：胜率/Tier + 海克斯排名 + 出装方案 + 玩法档案。也支持'英雄有了/拿到/刷到 某海克斯'的组合查询。命中返回数据；未命中返回【未命中固定查询，请使用自由查询】")
    public String tryFixedQuery(@P("用户的问题") String question) {
        System.out.println("[工具] tryFixedQuery: " + question);
        return query(question);
    }

    /** 固定查询公共入口（@Tool 用，由模型自主调用） */
    public String query(String question) {
        // 英雄排行榜
        if (containsAny(question, "英雄排行", "胜率排行", "最强英雄", "哪些英雄强", "TOP")) {
            List<Map<String, Object>> top = heroMapper.getTopHeroes(10);
            if (!top.isEmpty()) return formatTopHeroes(top);
        }

        // 海克斯全局胜率排行（无当前英雄时兜底）
        if (containsAny(question, "海克斯排行", "海克斯胜率", "哪个海克斯强", "最强海克斯", "海克斯强度")) {
            List<Map<String, Object>> top = heroMapper.getTopAugmentsByGlobalWinRate(10);
            if (!top.isEmpty()) return formatTopAugments(top);
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

        // 判断问题类型，按需返回（避免问出装却返回海克斯/技能等无关内容）
        boolean askBuild = containsAny(question, "出装", "出什么", "出啥", "怎么出", "咋出", "穿什么");
        boolean askHex = containsAny(question, "海克斯", "强化", "选什么", "选啥", "符文");
        boolean askPlay = containsAny(question, "怎么玩", "咋玩", "怎么打", "咋打", "玩法", "技能", "连招");
        boolean askStat = containsAny(question, "胜率", "Tier", "强度", "排行", "排名", "厉害吗", "厉害不", "强不强", "咋样", "怎么样");

        // 4. 按问题类型返回对应数据包
        StringBuilder sb = new StringBuilder("【" + heroDbName + " 数据】\n");

        // 胜率/Tier（默认返回；或明确问胜率）
        Map<String, Object> stats = heroMapper.getHeroStats((Integer) heroId);
        if (stats != null && !stats.isEmpty() && (!askBuild && !askHex && !askPlay || askStat)) {
            sb.append("【数据】").append(formatStats(stats)).append("\n");
        }
        // 海克斯排名（问海克斯时）
        if (askHex) {
            List<Map<String, Object>> augs = heroMapper.getHeroAugments((Integer) heroId, 5);
            if (!augs.isEmpty()) {
                sb.append("\n").append(formatAugments(heroDbName, augs)).append("\n");
            }
        }
        // 出装方案（问出装时）
        if (askBuild) {
            List<Map<String, Object>> builds = heroMapper.getBuilds((Integer) heroId);
            if (!builds.isEmpty()) {
                sb.append("\n").append(formatBuilds(heroDbName, builds)).append("\n");
                List<Map<String, Object>> exts = heroMapper.getHeroExt((Integer) heroId);
                if (!exts.isEmpty()) {
                    sb.append("\n").append(formatExt(heroDbName, exts)).append("\n");
                }
            } else {
                // 问出装但无 build 数据 → 未命中，让 LLM 兜底
                return "【未命中固定查询，请使用自由查询】";
            }
        }
        // 玩法档案（问玩法/技能时）
        if (askPlay) {
            Map<String, Object> profile = heroMapper.getHeroProfile((Integer) heroId);
            if (profile != null && !profile.isEmpty()) {
                sb.append("\n").append(formatProfile(heroDbName, profile)).append("\n");
            }
        }

        sb.append("\n以上是固定查询数据。如需更深入的机制分析，可再用自由查询(getSynergy/queryDb)。");
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
            heroName = stripPrefix(heroName, new String[]{"我", "帮我", "请问", "我想", "玩到", "选了", "问一下", "想", "给我"});
            if (heroName.length() < 2 || heroName.length() > 6) continue;

            Map<String, Object> hero = heroMapper.findHero(heroName);
            if (hero == null) continue;
            Object heroId = hero.get("id");
            String heroDbName = String.valueOf(hero.get("name"));

            // "有了X" 之后剩下的关键词（去掉 怎么玩/出什么/搭配 等尾巴）
            String keyword = q.substring(idx + m.length()).trim();
            keyword = truncateBefore(keyword, new String[]{"怎么玩", "怎么出装", "怎么搭配", "出什么装", "出什么",
                    "怎么样", "好不好", "行不行", "适合吗", "配合什么", "之后", "接下去"});
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

    /** 从问题中提取英雄名（常见模式，含口语：咋/啥/怎么 变体） */
    private String extractHeroName(String q) {
        // "X怎么玩/咋玩" "X选什么海克斯" "X出什么装/咋出装" "X的胜率" 等，取开头到关键词前
        String[] patterns = {
                // 玩法
                "咋玩", "怎么玩", "怎么打", "咋打", "什么技能", "啥技能", "技能怎么连", "怎么连招", "连招",
                // 海克斯
                "选什么海克斯", "选啥海克斯", "选什么强化", "选啥强化", "选什么", "选啥",
                // 出装
                "出什么装", "出啥装", "出什么装备", "出啥装备", "咋出装", "怎么出装", "出什么", "出啥",
                // 胜率/强度
                "的胜率", "的Tier", "的强度", "强不强", "厉害吗", "厉害不", "咋样", "怎么样",
                // 兜底
                "出装", "怎么出"
        };
        for (String p : patterns) {
            int idx = q.indexOf(p);
            if (idx > 0) {
                String name = q.substring(0, idx).trim();
                // 去掉可能的前缀（"我玩到""我选了""帮我看看"等）
                name = stripPrefix(name, new String[]{"我", "帮我", "请问", "我想", "玩到", "选了", "问一下", "想", "给我"});
                if (name.length() >= 2 && name.length() <= 6) return name;
            }
        }
        // "X 怎么玩"带空格：取第一个空格前的 2-6 字中文名，后接 怎么/咋/啥/选/出/的/玩
        int sp = q.indexOf(' ');
        if (sp > 0) {
            String name = q.substring(0, sp).trim();
            if (name.length() >= 2 && name.length() <= 6
                    && containsAny(q.substring(sp + 1), "怎么", "咋", "啥", "选", "出", "的", "玩")) {
                return name;
            }
        }
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

    private String formatExt(String heroName, List<Map<String, Object>> exts) {
        StringBuilder sb = new StringBuilder("【" + heroName + " 扩展装备】\n");
        String curType = "";
        for (Map<String, Object> e : exts) {
            String type = "situational".equals(e.get("extType")) ? "情境装备" : "推荐装备";
            if (!type.equals(curType)) {
                curType = type;
                sb.append("\n").append(type).append(": ");
            }
            sb.append(e.get("item_name")).append(" ");
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

    private String formatTopAugments(List<Map<String, Object>> top) {
        StringBuilder sb = new StringBuilder("【海克斯全局胜率排行 TOP】\n");
        int i = 1;
        for (Map<String, Object> a : top) {
            sb.append(i++).append(". ").append(a.get("name"))
                    .append("（").append(a.get("tier_name")).append("）")
                    .append(" 全局胜率:").append(a.get("global_win_rate")).append("%\n");
        }
        return sb.toString().trim();
    }

    /** 是否包含任一关键词 */
    private boolean containsAny(String s, String... keys) {
        for (String k : keys) if (s.contains(k)) return true;
        return false;
    }

    /** 去掉开头的任一前缀（一次，去第一个匹配） */
    private String stripPrefix(String s, String[] prefixes) {
        for (String p : prefixes) if (s.startsWith(p)) return s.substring(p.length()).trim();
        return s.trim();
    }

    /** 从第一个出现的"尾巴"词截断（去掉其后内容） */
    private String truncateBefore(String s, String[] tails) {
        int min = Integer.MAX_VALUE;
        for (String t : tails) { int i = s.indexOf(t); if (i >= 0 && i < min) min = i; }
        return min == Integer.MAX_VALUE ? s.trim() : s.substring(0, min).trim();
    }
}
