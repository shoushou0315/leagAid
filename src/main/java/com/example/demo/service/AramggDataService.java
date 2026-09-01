package com.example.demo.service;

import com.example.demo.entity.Augment;
import com.example.demo.entity.Hero;
import com.example.demo.entity.HeroAugmentRank;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Service;

import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * aramgg 数据采集与落库
 *
 * 数据源（静态JSON，免HTML解析）：
 *   1. /data/aram-mayhem-augments.zh_cn.json   全量海克斯（描述/稀有度）
 *   2. /data/champions-stats.json              英雄总榜（胜率/Tier/排名）
 *   3. /data/champion-details/{id}.json        单英雄145条海克斯排名
 */
@Service
public class AramggDataService {

    private static final Logger log = LoggerFactory.getLogger(AramggDataService.class);

    private final com.example.demo.mapper.DataWriteMapper dataWriteMapper;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @org.springframework.beans.factory.annotation.Value("${aramgg.base-url}")
    private String baseUrl;
    @org.springframework.beans.factory.annotation.Value("${aramgg.augments-file}")
    private String augmentsFile;
    @org.springframework.beans.factory.annotation.Value("${aramgg.champions-file}")
    private String championsFile;
    @org.springframework.beans.factory.annotation.Value("${aramgg.champion-details-dir}")
    private String championDetailsDir;

    /** 图片 CDN 前缀 */
    private static final String AUGMENT_ICON_BASE = "https://cdn.dtodo.cn/hextech/augment-icons/";
    private static final String CHAMPION_ICON_BASE = "https://cdn.dtodo.cn/hextech/champion-icons/";
    private static final String ITEM_ICON_BASE = "https://ddragon.leagueoflegends.com/cdn/";

    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile Runnable onSyncComplete;

    /** 注册同步完成回调（如重建向量索引） */
    public void setOnSyncComplete(Runnable callback) {
        this.onSyncComplete = callback;
    }

    public AramggDataService(com.example.demo.mapper.DataWriteMapper dataWriteMapper) {
        this.dataWriteMapper = dataWriteMapper;
    }

    /** 全量同步入口（异步线程执行，防阻塞） */
    public void syncAsync() {
        if (running.compareAndSet(false, true)) {
            new Thread(this::sync, "aramgg-sync").start();
        }
    }

    /** 全量同步：海克斯 → 英雄榜 → 装备 → 各英雄海克斯排名+装备build → 英雄档案 */
    public void sync() {
        try {
            log.info("[数据] ====== aramgg 数据同步开始 ======");
            syncAugments();
            syncChampions();
            syncItems();
            syncChampionDetails();
            syncHeroProfiles();
            // cleanupItems();   // 暂停：保留所有装备，先人工分析同名实例规律
            log.info("[数据] ====== aramgg 数据同步完成 ======");
            Runnable cb = onSyncComplete;
            if (cb != null) {
                try { cb.run(); } catch (Exception e) { log.warn("[数据] 同步完成回调异常: {}", e.getMessage()); }
            }
        } catch (Exception e) {
            log.error("[数据] 同步失败: {}", e.getMessage(), e);
        } finally {
            running.set(false);
        }
    }

    // ===== 1. 海克斯全量 =====
    /**
     * 爬 aramgg /zh-CN/augments 页面，拿海克斯的"全英雄平均胜率"（页面 SSG 渲染 208 个 article）。
     * 每个 article：a[href*=/zh-CN/augments/{id}]、p 名称、span.stat-value 的百分比。
     * 返回 id -> global_win_rate(0~100)。
     */
    private Map<Integer, Double> fetchAugmentGlobalWinRates() {
        Map<Integer, Double> rates = new HashMap<>();
        try {
            org.jsoup.nodes.Document doc = org.jsoup.Jsoup.connect(baseUrl + "/zh-CN/augments")
                    .userAgent("Mozilla/5.0").timeout(15000).get();
            for (Element art : doc.select("article")) {
                Element a = art.selectFirst("a[href*='/zh-CN/augments/']");
                Element stat = art.selectFirst("span.stat-value");
                if (a == null || stat == null) continue;
                String href = a.attr("href");
                int id = Integer.parseInt(href.substring(href.lastIndexOf('/') + 1));
                String v = stat.text().replace("%", "").trim();
                if (!v.isEmpty()) rates.put(id, Double.parseDouble(v));
            }
            log.info("[数据] 爬到海克斯全局胜率: {} 个", rates.size());
        } catch (Exception e) {
            log.warn("[数据] 拉取海克斯全局胜率失败: {}", e.getMessage());
        }
        return rates;
    }

    private void syncAugments() throws Exception {
        JsonNode root = getJson(baseUrl + augmentsFile);
        Map<Integer, Double> globalRates = fetchAugmentGlobalWinRates();
        List<Augment> list = new ArrayList<>();
        root.fields().forEachRemaining(e -> {
            JsonNode node = e.getValue();
            if (!node.path("enabled").asBoolean(true)) return;
            int id = node.path("id").asInt();
            // 清洗：只保留页面上的海克斯（208），其余丢弃
            if (!globalRates.containsKey(id)) return;
            Augment a = new Augment();
            a.setId(id);
            a.setName(node.path("displayName").asText(""));
            a.setEnName(node.path("name").asText(""));
            a.setRarity(node.path("rarity").asInt(0));
            a.setTierName(rarityName(node.path("rarity").asInt(0)));
            a.setDescription(cleanHtml(node.path("description").asText("")));
            a.setTooltip(cleanHtml(node.path("tooltip").asText("")));
            a.setEnabled(true);
            a.setGlobalWinRate(globalRates.get(id));
            // 海克斯图标：iconLarge 文件名转小写拼 CDN URL（_small 是黑白小图，_large 才是彩色）
            String icon = node.path("iconLarge").asText("");
            if (!icon.isEmpty()) {
                a.setImageUrl(AUGMENT_ICON_BASE + icon.toLowerCase());
            }
            list.add(a);
        });
        dataWriteMapper.deleteAllAugments();
        dataWriteMapper.batchInsertAugments(list);
        log.info("[数据] 海克斯落库（清洗到页面集合）: {} 个", list.size());
    }

    // ===== 2. 英雄总榜 =====
    private void syncChampions() throws Exception {
        // 从 DDragon 官方拿 id→[称号name, 官方名title, 英文名] 映射
        Map<Integer, String[]> nameById = fetchChampionNames();
        JsonNode arr = getJson(baseUrl + championsFile);
        List<Hero> list = new ArrayList<>();
        for (JsonNode n : arr) {
            Hero h = new Hero();
            int id = n.path("championId").asInt();
            h.setId(id);
            String[] names = nameById.get(id);
            h.setName(names != null ? names[0] : "英雄" + id);      // 称号（刀锋之影）
            h.setOfficialName(names != null ? names[1] : "");      // 官方中文名（泰隆）
            h.setEnName(names != null ? names[2] : "");            // 英文名
            h.setTier("T" + n.path("tier").asText(""));
            h.setWinRate(parseNullableDouble(n.path("winRate")));
            h.setPickRate(parseNullableDouble(n.path("pickRate")));
            h.setVersion(n.path("version").asText(""));
            h.setDate(n.path("date").asText(""));
            h.setWinRank(n.path("rank").asInt(0));
            // 英雄头像：CDN champion-icons/{id}.png
            h.setImageUrl(CHAMPION_ICON_BASE + id + ".png");
            list.add(h);
        }
        dataWriteMapper.deleteAllHeroes();
        dataWriteMapper.batchInsertHeroes(list);
        log.info("[数据] 英雄榜落库: {} 个", list.size());
    }

    /** 从 DDragon 官方拉 id → [称号name, 官方中文名title, 英文名] 映射 */
    private Map<Integer, String[]> fetchChampionNames() throws Exception {
        Map<Integer, String[]> map = new HashMap<>();
        try {
            String ver = getJson("https://ddragon.leagueoflegends.com/api/versions.json").get(0).asText();
            JsonNode data = getJson("https://ddragon.leagueoflegends.com/cdn/" + ver + "/data/zh_CN/champion.json").path("data");
            data.fields().forEachRemaining(e -> {
                JsonNode champ = e.getValue();
                String key = champ.path("key").asText("");
                if (!key.isEmpty()) {
                    try { map.put(Integer.parseInt(key), new String[]{
                            champ.path("name").asText(),    // 称号（刀锋之影）
                            champ.path("title").asText(),   // 官方中文名（泰隆）
                            e.getKey()});                   // 英文名
                    }
                    catch (NumberFormatException ignored) {}
                }
            });
            log.info("[数据] DDragon 英雄名映射: {} 个", map.size());
        } catch (Exception e) {
            log.warn("[数据] DDragon 拉取失败: {}", e.getMessage());
        }
        return map;
    }

    // ===== 英雄档案（DDragon 单英雄 JSON：技能/玩法技巧）=====
    private void syncHeroProfiles() throws Exception {
        List<Hero> heroes = dataWriteMapper.findAllHeroes();
        int total = heroes.size();
        dataWriteMapper.deleteAllHeroProfiles();
        java.util.List<com.example.demo.entity.HeroProfile> profiles =
                java.util.Collections.synchronizedList(new ArrayList<>());
        String ver = "";
        try {
            ver = getJson("https://ddragon.leagueoflegends.com/api/versions.json").get(0).asText();
        } catch (Exception ignored) {}
        String finalVer = ver;
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(5);
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(total);
        java.util.concurrent.atomic.AtomicInteger done = new java.util.concurrent.atomic.AtomicInteger(0);
        for (Hero hero : heroes) {
            pool.execute(() -> {
                try {
                    String enName = hero.getEnName();
                    if (enName == null || enName.isEmpty()) return;
                    try {
                        JsonNode data = getJson("https://ddragon.leagueoflegends.com/cdn/" + finalVer + "/data/zh_CN/champion/"
                                + enName + ".json").path("data").path(enName);
                        if (data.isMissingNode()) return;
                        com.example.demo.entity.HeroProfile p = new com.example.demo.entity.HeroProfile();
                        p.setHeroId(hero.getId());
                        p.setTitle(data.path("title").asText(""));
                        p.setBlurb(data.path("blurb").asText(""));
                        StringBuilder tags = new StringBuilder();
                        data.path("tags").forEach(t -> { if (tags.length() > 0) tags.append(","); tags.append(t.asText()); });
                        p.setTags(tags.toString());
                        p.setPassive(data.path("passive").path("name").asText("")
                                + "：" + cleanHtml(data.path("passive").path("description").asText("")));
                        StringBuilder spells = new StringBuilder();
                        int si = 0;
                        for (JsonNode s : data.path("spells")) {
                            if (si >= 4) break;
                            if (spells.length() > 0) spells.append("\n");
                            spells.append(s.path("name").asText(""))
                                  .append("：").append(cleanHtml(s.path("description").asText("")));
                            si++;
                        }
                        p.setSpells(spells.toString());
                        p.setAllyTips(joinStrings(data.path("allytips")));
                        p.setEnemyTips(joinStrings(data.path("enemytips")));
                        p.setVersion(finalVer);
                        profiles.add(p);
                    } catch (Exception e) {
                        log.warn("[数据] 英雄 {} 档案同步失败: {}", hero.getId(), e.getMessage());
                    }
                } finally {
                    int d = done.incrementAndGet();
                    if (d % 20 == 0 || d == total) {
                        log.info("[数据] 英雄档案进度 {}/{}", d, total);
                    }
                    latch.countDown();
                }
            });
        }
        pool.shutdown();
        try { latch.await(15, java.util.concurrent.TimeUnit.MINUTES); } catch (InterruptedException e) { }
        if (!profiles.isEmpty()) {
            dataWriteMapper.batchInsertHeroProfiles(profiles);
        }
        log.info("[数据] 英雄档案落库完成: {} 个", profiles.size());
    }

    private static String joinStrings(JsonNode arr) {
        if (arr == null || !arr.isArray()) return "";
        StringBuilder sb = new StringBuilder();
        for (JsonNode n : arr) {
            if (sb.length() > 0) sb.append("\n");
            sb.append(n.asText());
        }
        return sb.toString();
    }

    // ===== 装备全量（DDragon item.json，只留大乱斗版）=====
    private void syncItems() throws Exception {
        try {
            String ver = getJson("https://ddragon.leagueoflegends.com/api/versions.json").get(0).asText();
            JsonNode data = getJson("https://ddragon.leagueoflegends.com/cdn/" + ver + "/data/zh_CN/item.json").path("data");
            List<com.example.demo.entity.Item> list = new ArrayList<>();
            data.fields().forEachRemaining(e -> {
                JsonNode n = e.getValue();
                String name = n.path("name").asText("");
                // 过滤：不可购买（任务物品）、无名称（占位）
                boolean purchasable = n.path("gold").path("purchasable").asBoolean(false);
                if (name.isEmpty() || !purchasable) return;
                com.example.demo.entity.Item it = new com.example.demo.entity.Item();
                it.setId(Integer.parseInt(e.getKey()));
                it.setName(name);
                it.setEnName(n.path("name").asText(""));
                it.setDescription(cleanHtml(n.path("description").asText("")));
                it.setPlaintext(n.path("plaintext").asText(""));
                it.setTotalPrice(n.path("gold").path("total").asInt(0));
                it.setBasePrice(n.path("gold").path("base").asInt(0));
                // tags 逗号拼接
                StringBuilder tags = new StringBuilder();
                n.path("tags").forEach(t -> { if (tags.length() > 0) tags.append(","); tags.append(t.asText()); });
                it.setTags(tags.toString());
                // from/into 逗号拼接
                it.setFromIds(joinIds(n.path("from")));
                it.setIntoIds(joinIds(n.path("into")));
                it.setVersion(ver);
                // 装备图标：ddragon item/{id}.png（版本号动态）
                it.setImageUrl(ITEM_ICON_BASE + ver + "/img/item/" + e.getKey() + ".png");
                list.add(it);
            });
            dataWriteMapper.deleteAllItems();
            dataWriteMapper.batchInsertItems(list);
            log.info("[数据] 装备落库: {} 个", list.size());
        } catch (Exception e) {
            log.warn("[数据] 装备采集失败: {}", e.getMessage());
        }
    }

    private static String joinIds(JsonNode arr) {
        if (arr == null || !arr.isArray()) return "";
        StringBuilder sb = new StringBuilder();
        for (JsonNode n : arr) {
            if (sb.length() > 0) sb.append(",");
            sb.append(n.asText());
        }
        return sb.toString();
    }

    /**
     * 装备清洗：删除 DDragon 的 22/77 前缀重复实例（非大乱斗版本残留）。
     * 22 系列：无合成路径的残留版；77 系列：老版图标重复。主 id（其余）全部保留。
     * 验证：build/ext 引用的 111 个装备全部是主 id，主 id 装备（含智慧末刃/合成件）需保留。
     */
    private void cleanupItems() {
        try {
            // 删 22/77 开头 id（用 SQL：id LIKE '22%' OR id LIKE '77%'）
            int deleted = dataWriteMapper.deleteItemsByPrefix("22%", "77%");
            log.info("[数据] 清洗: 删除 22/77 前缀残留装备 {} 个", deleted);
        } catch (Exception e) {
            log.warn("[数据] 装备清洗失败: {}", e.getMessage());
        }
    }

    // ===== 3. 各英雄海克斯排名（并行）=====
    private void syncChampionDetails() throws Exception {
        List<Hero> heroes = dataWriteMapper.findAllHeroes();
        int total = heroes.size();
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(5);
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(total);
        java.util.concurrent.atomic.AtomicInteger done = new java.util.concurrent.atomic.AtomicInteger(0);
        for (Hero hero : heroes) {
            pool.execute(() -> {
                try {
                    try {
                        JsonNode root = getJson(baseUrl + championDetailsDir + "/" + hero.getId() + ".json");
                        JsonNode pair = root.path("championAugments").get(0);
                        if (pair == null || pair.size() < 2) return;
                        JsonNode inner = mapper.readTree(pair.get(1).asText());
                        JsonNode augs = inner.path("augments");
                        dataWriteMapper.deleteHeroAugmentRanks(hero.getId());
                        List<HeroAugmentRank> ranks = new ArrayList<>();
                        augs.fields().forEachRemaining(e -> {
                            JsonNode v = e.getValue();
                            HeroAugmentRank r = new HeroAugmentRank();
                            r.setHeroId(hero.getId());
                            r.setAugmentId(Integer.parseInt(e.getKey()));
                            r.setTier(v.path("tier").asText(""));
                            r.setWinRank(v.path("rank").asInt(999));
                            r.setTotal(v.path("total").asInt(0));
                            r.setWinRate(parseNullableDouble(v.path("win_rate")));
                            r.setPickRate(parseNullableDouble(v.path("pick_rate")));
                            r.setNumGames(parseNullableLong(v.path("num_games")));
                            r.setNumWinGames(parseNullableLong(v.path("num_win_games")));
                            ranks.add(r);
                        });
                        dataWriteMapper.batchInsertHeroAugmentRanks(ranks);
                    } catch (Exception e) {
                        log.warn("[数据] 英雄 {} 排名同步失败: {}", hero.getId(), e.getMessage());
                    }
                    // 3. 装备 build 方案（aramgg 英雄页 HTML）
                    try { syncHeroItemBuilds(hero.getId()); }
                    catch (Exception e) { log.warn("[数据] 英雄 {} build失败: {}", hero.getId(), e.getMessage()); }
                } finally {
                    int d = done.incrementAndGet();
                    if (d % 20 == 0 || d == total) {
                        log.info("[数据] 英雄排名进度 {}/{}", d, total);
                    }
                    latch.countDown();
                }
            });
            // 反爬：多线程下每英雄提交间隔小延时
            try { Thread.sleep(30); } catch (InterruptedException ie) { return; }
        }
        pool.shutdown();
        try { latch.await(15, java.util.concurrent.TimeUnit.MINUTES); } catch (InterruptedException e) { }
        log.info("[数据] 英雄排名+build 完成");
    }

    // ===== 装备 build 方案（解析 aramgg 英雄页 HTML）=====
    private void syncHeroItemBuilds(int heroId) {
        try {
            String url = baseUrl + "/zh-CN/champion-stats/" + heroId;
            org.jsoup.nodes.Document doc = org.jsoup.Jsoup.connect(url)
                    .timeout(15000)
                    .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/126.0.0.0 Safari/537.36")
                    .get();
            dataWriteMapper.deleteHeroBuilds(heroId);
            List<com.example.demo.entity.HeroItemBuild> builds = new ArrayList<>();

            // 页面结构（SSR）：3 个重复的 [data-build-content]，每个内含「核心装备」+「推荐装」两块
            // 核心装备块：<span data-slot="badge">#N</span> + 装备 img(item-icons/{id}.png) + 胜率
            // 推荐装块无 #N badge，跳过。3 个 section 内容重复，只取第一个，避免唯一键冲突
            List<org.jsoup.nodes.Element> panels = doc.select("[data-build-content]");
            if (panels.isEmpty()) { log.warn("[数据] 英雄 {} 页面无 build-content", heroId); return; }
            org.jsoup.nodes.Element panel = panels.get(0);   // 只取第一个重复 section
            for (org.jsoup.nodes.Element badge : panel.select("span[data-slot=badge]")) {
                String badgeText = badge.text().trim();
                if (!isHashNumber(badgeText)) continue;   // 只认 #N 方案索引（核心装备有，推荐装没有）
                int buildIndex = Integer.parseInt(badgeText.substring(1));

                    // 方案块：从 badge 往上找「同时含 item-icons 和 胜率:」的最近祖先（该方案完整块）
                    Element block = badge.parent();
                    for (int up = 0; up < 6 && block != null; up++) {
                        boolean hasIcons = !block.select("img[src*='item-icons']").isEmpty();
                        boolean hasWr = block.text().contains("胜率:");
                        if (hasIcons && hasWr) break;
                        block = block.parent();
                    }
                    if (block == null) continue;

                    // 胜率/选取率（块内文本）
                    String blockText = block.text();
                    java.util.regex.Matcher wm = java.util.regex.Pattern.compile("胜率: ([\\d.]+)%").matcher(blockText);
                    java.util.regex.Matcher pm = java.util.regex.Pattern.compile("选取率: ([\\d.]+)%").matcher(blockText);
                    double wr = wm.find() ? Double.parseDouble(wm.group(1)) / 100.0 : 0;
                    double pr = pm.find() ? Double.parseDouble(pm.group(1)) / 100.0 : 0;

                    // 该块的装备（img src 含 item-icons/{id}.png）
                    int slot = 1;
                    for (org.jsoup.nodes.Element img : block.select("img[src*='item-icons']")) {
                        java.util.regex.Matcher idm = java.util.regex.Pattern.compile("item-icons/(\\d+)\\.png").matcher(img.attr("src"));
                        if (!idm.find()) continue;
                        com.example.demo.entity.HeroItemBuild b = new com.example.demo.entity.HeroItemBuild();
                        b.setHeroId(heroId);
                        b.setBuildIndex(buildIndex);
                        b.setGroupIndex(1);
                        b.setSlot(slot++);
                        b.setItemId(Integer.parseInt(idm.group(1)));
                        b.setWinRate(wr);
                        b.setPickRate(pr);
                        builds.add(b);
                    }
                }
            if (!builds.isEmpty()) {
                dataWriteMapper.batchInsertHeroBuilds(builds);
            } else {
                log.warn("[数据] 英雄 {} 出装方案为空（页面无 build-content）", heroId);
            }

            // ===== 扩展装备：情境装备(前12件) + 推荐装备(5件)，无序列表无胜率 =====
            try {
                List<com.example.demo.entity.HeroItemExt> exts = new ArrayList<>();
                int extTypeIdx = 0;
                for (org.jsoup.nodes.Element h3 : panel.select("h3")) {
                    String title = h3.text().trim();
                    String type = null;
                    if (title.contains("情境装备")) type = "situational";
                    else if (title.contains("推荐装备")) type = "recommended";
                    if (type == null) continue;
                    Element section = h3.parent();
                    int slot = 1;
                    for (org.jsoup.nodes.Element img : section.select("img[src*='item-icons']")) {
                        java.util.regex.Matcher idm = java.util.regex.Pattern.compile("item-icons/(\\d+)\\.png").matcher(img.attr("src"));
                        if (!idm.find()) continue;
                        com.example.demo.entity.HeroItemExt ext = new com.example.demo.entity.HeroItemExt();
                        ext.setHeroId(heroId);
                        ext.setItemId(Integer.parseInt(idm.group(1)));
                        ext.setExtType(type);
                        ext.setSlot(slot++);
                        exts.add(ext);
                    }
                }
                if (!exts.isEmpty()) {
                    dataWriteMapper.deleteHeroExt(heroId);
                    dataWriteMapper.batchInsertHeroExt(exts);
                }
            } catch (Exception e) {
                log.warn("[数据] 英雄 {} 扩展装备解析失败: {}", heroId, e.getMessage());
            }
        } catch (Exception e) {
            log.warn("[数据] 英雄 {} 装备build解析失败: {}", heroId, e.getMessage());
        }
    }

    /** 补采缺失 build 的英雄（之前采集失败漏掉的） */
    public void syncMissingBuilds() {
        List<Hero> heroes = dataWriteMapper.findAllHeroes();
        int done = 0, missing = 0;
        for (Hero hero : heroes) {
            List<com.example.demo.entity.HeroItemBuild> existing = dataWriteMapper.findHeroBuilds(hero.getId());
            if (existing != null && !existing.isEmpty()) continue;
            missing++;
            syncHeroItemBuilds(hero.getId());
            done++;
            if (done % 10 == 0 || done == missing) {
                log.info("[数据] 补采build进度 {}/{}", done, missing);
            }
            try { Thread.sleep(200 + (int)(Math.random() * 200)); } catch (InterruptedException ie) { return; }
        }
        log.info("[数据] build补采完成，补采 {} 个英雄", done);
    }

    /** 检查并返回缺失 build 的英雄列表（供 API 查询） */
    public List<Integer> getMissingBuildHeroes() {
        List<Integer> missing = new ArrayList<>();
        for (Hero hero : dataWriteMapper.findAllHeroes()) {
            List<com.example.demo.entity.HeroItemBuild> existing = dataWriteMapper.findHeroBuilds(hero.getId());
            if (existing == null || existing.isEmpty()) {
                missing.add(hero.getId());
            }
        }
        return missing;
    }

    // ===== 工具 =====
    private JsonNode getJson(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/126.0.0.0 Safari/537.36")
                .GET()
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) throw new RuntimeException("HTTP " + resp.statusCode() + " for " + url);
        return mapper.readTree(resp.body());
    }

    /** 是否为 #数字（如 "#3"）：非正则代替 matches("#\\d+") */
    private static boolean isHashNumber(String s) {
        if (s == null || s.length() < 2 || s.charAt(0) != '#') return false;
        for (int i = 1; i < s.length(); i++) if (!Character.isDigit(s.charAt(i))) return false;
        return true;
    }

    /** 去掉 HTML 标签：非正则代替 replaceAll("<[^>]+>","") */
    private static String stripTags(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        boolean inTag = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '<') { inTag = true; continue; }
            if (c == '>') { inTag = false; continue; }
            if (!inTag) sb.append(c);
        }
        return sb.toString();
    }

    private static String cleanHtml(String s) {
        if (s == null || s.isEmpty()) return "";
        return stripTags(s).replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&").trim();
    }

    private static String rarityName(int rarity) {
        return switch (rarity) {
            case 0 -> "白银";
            case 1 -> "黄金";
            case 2 -> "棱彩";
            default -> "未知";
        };
    }

    /** 解析可能为 String("0.534") 或 null 的数字字段 */
    private static Double parseNullableDouble(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) return null;
        String text = node.asText("");
        if (text.isEmpty()) return null;
        try { return Double.parseDouble(text); }
        catch (NumberFormatException e) { return null; }
    }

    private static Long parseNullableLong(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) return null;
        String text = node.asText("");
        if (text.isEmpty()) return null;
        try { return Long.parseLong(text); }
        catch (NumberFormatException e) { return null; }
    }
}
