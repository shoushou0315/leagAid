package com.example.demo.ai;

import com.example.demo.mapper.HeroMapper;
import com.example.demo.service.AramggDataService;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.model.embedding.EmbeddingModel;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 语义知识工具（向量 RAG，按需调用，Redis 8 Vector Set 存储）
 *
 * 向量化内容：
 *   - 海克斯描述（名称+稀有度+效果）
 *   - 装备描述（名称+分类+效果）
 *   - 英雄档案（玩法/技能/定位）
 *
 * 存储：Redis 8 原生 Vector Set（VADD/VSIM），内存检索毫秒级，替代 JVector 图索引。
 *
 * 已从"自动注入的 ContentRetriever"改为 @Tool：
 *   - 结构化查询（胜率/Tier/排名/出装/组合）由 FixedQueryTools/queryDb 负责，不走这里
 *   - 只有 AI 判断需要语义检索（描述性/机制类问题）时主动调用本工具
 */
public class DynamicContentRetriever {

    private final EmbeddingModel embeddingModel;
    private final AramggDataService dataService;
    private final HeroMapper heroMapper;
    private final int vectorDimension;
    private final RedisVectorStore vectorStore;

    private volatile boolean vectorReady = false;

    /** 构建版本号：refresh 时递增，使进行中的构建自动中止 */
    private volatile int buildGeneration = 0;

    public DynamicContentRetriever(EmbeddingModel embeddingModel,
                                   AramggDataService dataService,
                                   HeroMapper heroMapper,
                                   String redisHost,
                                   int redisPort,
                                   String redisKey,
                                   int vectorDimension) {
        this.embeddingModel = embeddingModel;
        this.dataService = dataService;
        this.heroMapper = heroMapper;
        this.vectorDimension = vectorDimension;
        this.vectorStore = new RedisVectorStore(redisHost, redisPort, redisKey);

        if (vectorStore.hasData()) {
            System.out.println(">>> [RAG] Redis 已有向量数据，直接就绪");
            vectorReady = true;
        } else if (heroMapper.countAugments() == 0 || heroMapper.countHeroProfiles() == 0) {
            System.out.println(">>> [RAG] 数据不完整，等待后台同步完成后构建索引...");
            dataService.syncAsync();
        } else {
            // 异步构建向量索引，避免阻塞 Spring 上下文（网页立即可用）
            System.out.println(">>> [RAG] 后台构建向量索引...");
            new Thread(this::buildVectorIndexSync, "vector-builder").start();
        }
        // 数据同步完成后自动重建索引
        dataService.setOnSyncComplete(this::buildVectorIndexSync);
    }

    private final Object buildLock = new Object();

    /** 构建向量索引（Redis Vector Set，内存检索） */
    private void buildVectorIndexSync() {
        synchronized (buildLock) {
            int myGeneration = buildGeneration;
            try {
                List<String[]> segments = new ArrayList<>();  // {text, pageType, pageId}

                // 海克斯描述
                for (Map<String, Object> a : heroMapper.findAllAugments()) {
                    if (myGeneration != buildGeneration) { System.out.println(">>> [RAG] 构建已中止（收到新刷新）"); return; }
                    String text = a.get("name") + "（" + a.get("tier_name") + "海克斯）：" + a.get("description");
                    segments.add(new String[]{text, "augment", String.valueOf(a.get("id"))});
                }
                // 装备描述
                for (Map<String, Object> it : heroMapper.findAllItems()) {
                    if (myGeneration != buildGeneration) { System.out.println(">>> [RAG] 构建已中止（收到新刷新）"); return; }
                    String name = String.valueOf(it.get("name"));
                    if (name.isEmpty()) continue;
                    String plaintext = it.get("plaintext") == null ? "" : String.valueOf(it.get("plaintext"));
                    String description = it.get("description") == null ? "" : String.valueOf(it.get("description"));
                    String text = "装备 " + name + "：" + (plaintext.isEmpty() ? description : plaintext);
                    segments.add(new String[]{text, "item", String.valueOf(it.get("id"))});
                }
                // 英雄档案（玩法/技能/定位）
                for (Map<String, Object> p : heroMapper.findAllHeroProfiles()) {
                    if (myGeneration != buildGeneration) { System.out.println(">>> [RAG] 构建已中止（收到新刷新）"); return; }
                    StringBuilder text = new StringBuilder();
                    text.append("英雄 ").append(p.get("hero_id")).append(" 玩法档案");
                    String title = p.get("title") == null ? "" : String.valueOf(p.get("title"));
                    String tags = p.get("tags") == null ? "" : String.valueOf(p.get("tags"));
                    String blurb = p.get("blurb") == null ? "" : String.valueOf(p.get("blurb"));
                    String passive = p.get("passive") == null ? "" : String.valueOf(p.get("passive"));
                    String spells = p.get("spells") == null ? "" : String.valueOf(p.get("spells"));
                    String allyTips = p.get("ally_tips") == null ? "" : String.valueOf(p.get("ally_tips"));
                    String enemyTips = p.get("enemy_tips") == null ? "" : String.valueOf(p.get("enemy_tips"));
                    if (!title.isEmpty()) text.append("（").append(title).append("）");
                    if (!tags.isEmpty()) text.append("，定位：").append(tags);
                    if (!blurb.isEmpty()) text.append("。简介：").append(blurb);
                    if (!passive.isEmpty()) text.append("。被动：").append(passive);
                    if (!spells.isEmpty()) text.append("。技能：").append(spells);
                    if (!allyTips.isEmpty()) text.append("。玩法技巧：").append(allyTips);
                    if (!enemyTips.isEmpty()) text.append("。对抗技巧：").append(enemyTips);
                    segments.add(new String[]{text.toString(), "hero", String.valueOf(p.get("hero_id"))});
                }

                if (segments.isEmpty()) {
                    System.out.println(">>> [RAG] 无可向量化内容");
                    return;
                }

                vectorStore.clear();
                // embedding + 写入（分批 10，Redis 写入远快于 JVector 图构建）
                int done = 0;
                for (int i = 0; i < segments.size(); i += 10) {
                    if (myGeneration != buildGeneration) { System.out.println(">>> [RAG] 构建已中止（收到新刷新）"); return; }
                    List<String> batchTexts = new ArrayList<>();
                    for (int j = i; j < Math.min(i + 10, segments.size()); j++) {
                        batchTexts.add(segments.get(j)[0]);
                    }
                    var emb = embeddingModel.embedAll(batchTexts.stream().map(s -> dev.langchain4j.data.segment.TextSegment.from(s)).toList()).content();
                    for (int j = 0; j < emb.size(); j++) {
                        String[] seg = segments.get(i + j);
                        float[] vec = emb.get(j).vector();
                        vectorStore.add(seg[2] + ":" + seg[1] + ":" + i + ":" + j, vec, seg[1], seg[2], seg[0]);
                    }
                    done += emb.size();
                    if (done % 100 == 0 || done == segments.size()) {
                        System.out.println(">>> [RAG] 已写入 " + done + "/" + segments.size());
                    }
                }
                vectorReady = true;
                System.out.println(">>> [RAG] 向量索引就绪: " + segments.size() + " 条（海克斯" + heroMapper.countAugments()
                        + " + 装备" + heroMapper.countItems() + " + 英雄档案" + heroMapper.countHeroProfiles() + "），Redis key=" + "vec:rag");
            } catch (Exception e) {
                System.out.println(">>> [RAG] 向量索引构建失败: " + e.getMessage());
                e.printStackTrace();
            }
        }
    }

    /**
     * 语义知识检索工具：用自然语言/效果描述查找相关内容（海克斯/装备/英雄机制）。
     * 只在此类需求时调用：说不出确切名字、用效果或机制描述提问（如"克护盾的装备""适合攻速流的海克斯"）。
     * 结构化查询（指名道姓/查数字）请优先用 tryFixedQuery / queryDb，不要调用本工具。
     */
    @Tool("语义检索知识库：按效果/机制描述查找海克斯、装备、英雄玩法。返回匹配内容列表。仅当问题用'描述效果、找不到确切名称'时才调用；指名道姓/要数据的问题不要用")
    public String queryKnowledge(@P("用自然语言描述想找的内容，如：克制护盾的装备") String question) {
        System.out.println(">>> [Tool] queryKnowledge 调用: " + question);
        if (!vectorReady) {
            return "知识库尚未就绪，请稍后重试";
        }
        try {
            var emb = embeddingModel.embed(question).content().vector();
            List<Map<String, Object>> matches = vectorStore.search(emb, 8);
            if (matches.isEmpty()) {
                return "未找到相关内容。**如实告诉用户知识库没有，禁止编造装备/海克斯的效果描述或胜率**。";
            }
            StringBuilder sb = new StringBuilder("语义检索结果（" + matches.size() + " 条）：\n");
            for (Map<String, Object> m : matches) {
                sb.append("- ").append(m.get("text")).append("\n");
            }
            return sb.toString().trim();
        } catch (Exception e) {
            return "语义检索失败: " + e.getMessage();
        }
    }

    /** 刷新：中止当前构建，触发全量数据同步，完成后自动重建向量索引 */
    public void refresh() {
        buildGeneration++;            // 使进行中的构建在下个检查点中止
        vectorReady = false;          // 索引视为未就绪
        dataService.syncAsync();
    }

    /** 数据就绪：向量索引构建完成（含历史磁盘索引） */
    public boolean isReady() {
        return vectorReady;
    }
}
