package com.example.demo.ai;

import com.example.demo.mapper.HeroMapper;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 数据库查询工具集（参数化查询 + 语义检索 + 联动分析）
 *
 * 全部查询走 MyBatis（HeroMapper），无 JdbcTemplate。
 */
@Component
public class DatabaseTools {

    private static final java.util.Set<String> ALLOWED_TABLES = java.util.Set.of(
            "heroes", "augments", "items", "hero_augment_rank", "hero_item_build");

    private final HeroMapper heroMapper;

    public DatabaseTools(HeroMapper heroMapper) {
        this.heroMapper = heroMapper;
    }

    /** 数据库结构说明（AI 决定查询参数的依据） */
    @Tool("返回数据库全部表结构、列名、类型、含义和查询参数说明，供决定 queryDb 查询参数使用")
    public String getSchema() {
        return """
                数据库共 5 张表，全部与海克斯大乱斗相关。查询请用 queryDb 填参数，不要写 SQL：

                【表1 heroes】英雄总榜（173个）
                  id(INT PK), name(VARCHAR 称号,如"刀锋之影"), official_name(VARCHAR 官方中文名,如"泰隆"), en_name(英文名), tier(如T1/T2),
                  win_rate(DOUBLE 0~1胜率), pick_rate(选取率), version(版本), date, win_rank(总排名)
                  说明：玩家说"泰隆"或"刀锋之影"都指向同一英雄，搜索英雄时用 name 或 official_name 或 en_name 均可

                【表2 augments】海克斯强化（236个）
                  id(INT PK), name(中文名), en_name(英文名), rarity(INT 0=白银1=黄金2=棱彩),
                  tier_name(白银/黄金/棱彩), description(效果描述), tooltip(详细效果), enabled

                【表3 hero_augment_rank】英雄×海克斯排名（核心，每英雄145条）
                  hero_id(FK→heroes.id), augment_id(FK→augments.id),
                  tier, win_rank(排名越小越强), total(总数145), win_rate, pick_rate, num_games, num_win_games

                【表4 items】装备（大乱斗可用装备，含合成小件）
                  id(INT PK), name(中文名), en_name, description, plaintext,
                  total_price, base_price, tags(分类逗号分隔), from_ids(合成来源), into_ids(可合成), version

                【表5 hero_item_build】英雄×装备推荐方案（每英雄3套×3组×3件）
                  hero_id, build_index(方案0-2), group_index(组1-3), slot(槽位1-3),
                  item_id(FK→items.id), win_rate(该方案胜率), pick_rate

                queryDb 可用参数：
                  table: heroes / augments / items / hero_augment_rank / hero_item_build
                  heroId: 英雄数字id（先 searchName 拿到，可为空）
                  keyword: 名称关键词（只匹配 英雄名/海克斯名/装备名，不匹配效果描述！）
                  tier: 稀有度筛选（白银/黄金/棱彩 或 T1/T2/T3/T4/T5）
                  order: asc / desc
                  limit: 结果条数（默认10，上限300；要"输出全部列表"时传300）

                查询技巧：
                  - 查某英雄胜率：table=heroes + heroId（或 keyword=英雄名），即可命中该英雄一条数据
                  - 查某英雄的海克斯：table=hero_augment_rank + heroId
                  - 查某英雄出装：table=hero_item_build + heroId

                重要分工：
                  - 按名字查（"提莫有什么海克斯"）→ queryDb + heroId/keyword
                  - 按效果/机制描述找装备或海克斯（"克护盾的""召唤机器人的""把攻速转CD的"）→ 必须用 queryKnowledge（RAG）

                注意：win_rate/pick_rate 是 0~1 的小数（如 0.576 = 57.6%）。
                """;
    }

    /** 名称解析：中文名/称号/英文名 → id */
    @Tool("按名称搜索英雄/海克斯/装备（英雄支持称号/官方名/英文名），返回 [类型, 名称, id] 列表")
    public String searchName(@P("名称关键词，如：薇恩、泰隆、刀锋之影") String keyword) {
        System.out.println("[工具] searchName " + keyword);
        try {
            StringBuilder sb = new StringBuilder();
            List<Map<String, Object>> heroes = heroMapper.searchNameHeroes(keyword);
            for (Map<String, Object> h : heroes) {
                sb.append("英雄: ").append(h.get("name")).append(" (id=").append(h.get("id")).append(")\n");
            }
            List<Map<String, Object>> augs = heroMapper.searchNameAugments(keyword);
            for (Map<String, Object> a : augs) {
                sb.append("海克斯: ").append(a.get("name")).append(" (id=").append(a.get("id")).append(")\n");
            }
            List<Map<String, Object>> items = heroMapper.searchNameItems(keyword);
            for (Map<String, Object> it : items) {
                sb.append("装备: ").append(it.get("name")).append(" (id=").append(it.get("id")).append(")\n");
            }
            return sb.length() == 0 ? "未找到匹配" : sb.toString().trim();
        } catch (Exception e) {
            return "搜索失败: " + e.getMessage();
        }
    }

    /** 参数化动态查询：LLM 填参数，不写 SQL。MyBatis 动态 SQL 生成，白名单 + 参数化，永不报语法错误 */
    @Tool("参数化查询数据库。填 table/heroId/keyword/tier/order/limit 参数即可，无需写SQL。表有：heroes英雄榜、augments海克斯、items装备、hero_augment_rank英雄海克斯排名、hero_item_build出装。注意：keyword 只匹配名字，按效果描述找装备/海克斯必须用 queryKnowledge。查'提莫有什么海克斯'用 hero_augment_rank+heroId；查'提莫胜率'用 heroes+heroId；查'黄金海克斯'用 augments+tier='黄金'")
    public String queryDb(@P("表名，可选：heroes/augments/items/hero_augment_rank/hero_item_build") String table,
                          @P("英雄id（数字，先用 searchName 拿到），可为空") Integer heroId,
                          @P("名称关键词（只匹配英雄名/海克斯名/装备名，不匹配效果描述），可为空") String keyword,
                          @P("稀有度/分类筛选：白银/黄金/棱彩 或 T1~T5，可为空") String tier,
                          @P("排序方向：asc/desc，可为空") String order,
                          @P("返回条数，默认10，上限300；要'输出全部列表'时传300") int limit) {
        System.out.println("[工具] queryDb " + table + (heroId != null ? " h" + heroId : "") + (keyword != null ? " k=" + keyword : "") + " n=" + limit);
        try {
            if (table == null || !ALLOWED_TABLES.contains(table)) {
                return "错误：table 必须是 heroes/augments/items/hero_augment_rank/hero_item_build 之一。";
            }
            int lim = limit <= 0 ? 10 : Math.min(limit, 300);
            // heroId 为 0 视为未指定（0 不是有效英雄 id，避免 WHERE id=0 查空）
            if (heroId != null && heroId == 0) heroId = null;
            List<Map<String, Object>> rows = heroMapper.dynamicQuery(
                    table, heroId, keyword, tier, order, lim);
            if (rows.isEmpty()) {
                return "查询成功，无结果（0 行）。**如实告诉用户数据库查不到该数据，禁止编造任何胜率/排名/数字**。可换关键词或表重试。";
            }
            StringBuilder sb = new StringBuilder();
            sb.append("查询成功，返回 ").append(rows.size()).append(" 行\n");
            for (Map<String, Object> row : rows) {
                sb.append("| ");
                row.forEach((k, v) -> sb.append(k).append("=").append(v).append(" "));
                sb.append("|\n");
            }
            return sb.toString();
        } catch (Exception e) {
            return "查询失败: " + e.getMessage() + "。请检查参数是否合法。";
        }
    }

    /**
     * 机制联动分析工具：返回英雄的技能档案 + 多个装备/海克斯的完整效果描述（并列呈现）。
     * 用于判断"某装备/海克斯与某英雄技能是否能形成机制联动"，以及"多个装备/海克斯之间能否互相触发"。
     */
    @Tool("分析某个英雄与一个或多个装备/海克斯的机制联动，返回英雄技能档案+每个装备/海克斯的完整效果描述并列列出，并引导做链式推理。当玩家问'某英雄选某海克斯/出某装备好不好/能不能配合/邪修玩法'时使用，找出'技能→海克斯→装备'的完整触发链")
    public String getSynergy(@P("英雄名，如：莉莉娅") String heroName,
                             @P("装备名或海克斯名，可传多个用逗号分隔，如：心之钢,虚幻武器") String itemOrAugmentNames) {
        System.out.println("[工具] getSynergy " + heroName + " x " + itemOrAugmentNames);
        StringBuilder sb = new StringBuilder();
        try {
            Map<String, Object> hero = heroMapper.findHero(heroName);
            if (hero == null) {
                return "未找到英雄: " + heroName;
            }
            Object heroId = hero.get("id");
            String heroDbName = String.valueOf(hero.get("name"));

            sb.append("【英雄: ").append(heroDbName).append(" 技能档案】\n");
            Map<String, Object> profile = heroMapper.getHeroProfile((Integer) heroId);
            if (profile != null) {
                sb.append("定位: ").append(profile.get("tags")).append("\n");
                sb.append("被动: ").append(profile.get("passive")).append("\n");
                sb.append("技能: ").append(profile.get("spells")).append("\n");
                System.out.println("[工具] getSynergy 技能档案 len=" + String.valueOf(profile.get("spells")).length());
            }

            String[] names = itemOrAugmentNames.split("[,，]");
            boolean foundAny = false;
            for (String name : names) {
                String key = name.trim();
                if (key.isEmpty()) continue;
                List<Map<String, Object>> augs = heroMapper.getSynergyAugments(key);
                for (Map<String, Object> a : augs) {
                    foundAny = true;
                    sb.append("\n【海克斯: ").append(a.get("name")).append("（").append(a.get("tier_name")).append("）】\n");
                    sb.append("效果: ").append(a.get("description")).append("\n");
                    if (a.get("tooltip") != null) sb.append("详细: ").append(a.get("tooltip")).append("\n");
                    Object augmentId = a.get("id");
                    if (augmentId != null) {
                        Map<String, Object> st = heroMapper.getSynergyAugmentStats((Integer) heroId, ((Number) augmentId).intValue());
                        if (st != null) {
                            sb.append("该英雄数据: 排名 #").append(st.get("win_rank"))
                                    .append(" 胜率 ").append(st.get("win_rate_pct")).append("%")
                                    .append(" 登场率 ").append(st.get("pick_rate_pct")).append("%\n");
                        }
                    }
                }
                List<Map<String, Object>> items = heroMapper.getSynergyItems(key);
                for (Map<String, Object> it : items) {
                    foundAny = true;
                    sb.append("\n【装备: ").append(it.get("name")).append("】\n");
                    sb.append("分类: ").append(it.get("tags")).append("\n");
                    sb.append("效果: ").append(it.get("plaintext")).append("\n");
                    if (it.get("description") != null) sb.append("详情: ").append(it.get("description")).append("\n");
                }
                if (augs.isEmpty() && items.isEmpty()) {
                    sb.append("\n未找到: ").append(key).append("\n");
                }
            }

            sb.append("\n\n请进行【链式联动分析】，找出完整的触发链：");
            sb.append("\n1. 起点：该英雄哪个技能/被动/属性是触发源（如：W加双抗、被动给攻速、Q高频命中、技能自带吸血/护盾/真伤等）");
            sb.append("\n2. 白赚检查（关键）：英雄自带的能力（被动攻速、双抗、吸血、护盾、治疗等）是否正好被某个海克斯/装备【转化利用】？例如：被动给攻速 + 海克斯把攻速转急速 = 白赚CD");
            sb.append("\n3. 传导：逐个分析每个海克斯/装备的效果，看它的触发条件是否能被【上一步的结果】满足");
            sb.append("\n4. 链式串联：把效果连成一条链：技能 → 海克斯 → 装备A → 装备B，检查每一步是否环环相扣");
            sb.append("\n5. 闭环校验（重要）：对每个海克斯，明确它【产出什么】和【消耗什么】。当 A 产出的被 B 消耗、B 产出的又被 A 消耗时，判断这是【互相喂养的正循环】还是【空转】——关键看产出源是否持续存在（如踢踏舞根据移速持续产攻速，即使攻速被转走仍会不断补）。不要默认'消耗=归零'，要看消耗后是否有持续产出源");
            sb.append("\n6. 终点：整条链最终形成的效果（如高攻速、高治疗、无限叠层、技能机关枪等）");
            sb.append("\n7. 结合技能机制与胜率数据，给出'这套玩法能否成立+成立的原因/不成立的原因'。");
            sb.append("\n注意：不要只判断单个装备好不好，要看组合能否形成'1+1>2'协同，也要指出短板。");
            sb.append("\n【表达要求】闭环推理可以大胆串联，但每步结论要标注可靠度：效果描述明确写明的用'确定'，推导出来的用'大概率/理论上'，并提醒关键联动可游戏内实测验证。");
            return sb.toString();
        } catch (Exception e) {
            return "查询失败: " + e.getMessage();
        }
    }
}
