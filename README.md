<div align="center">

# ⚔️ leagAid — 海克斯大乱斗 AI 助手

**语音驱动的《英雄联盟：极地大乱斗》实时智能助手** —— 选海克斯、出装、对抗分析，全语音交互，全屏游戏可用。

[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.4-green)]()
[![LangChain4j](https://img.shields.io/badge/LangChain4j-1.15-blue)]()
[![Java](https://img.shields.io/badge/Java-21-orange)]()
[![Redis](https://img.shields.io/badge/Redis-8-red)]()
[![MySQL](https://img.shields.io/badge/MySQL-8-blue)]()

</div>

---

## ✨ 核心亮点

- 🎤 **全语音交互**：按住 **F6** 说话，松开自动发送（JNativeHook 全局热键，**全屏游戏可用**）
- 👁 **一键选海克斯**：说"选哪个海克斯"→ 自动截图 → **qwen-vl 视觉识别** → LLM 结合阵容/装备推荐
- 📊 **实时对局感知**：AI 自动获取自己/双方阵容/板凳/装备/熟练度/已选海克斯
- 🧠 **多工具 Agent**：固定查询 + 参数化 SQL + 机制联动分析 + 语义检索 + 游戏状态，五层路由
- 🔄 **一局一轮对话**：sessionId 锚定，换局自动切会话，记忆持久化
- 🛡 **防幻觉**：所有数据来自工具返回，查不到如实说明，不编造胜率/排名

## 🚀 快速开始

### 前置依赖

| 组件 | 说明 |
|---|---|
| JDK 21 | |
| MySQL 8 | 自动建表（schema.sql） |
| Redis 8 | 向量库 + 游戏快照 |
| 阿里云百炼 API Key | qwen3.8-max / qwen-vl-max / text-embedding-v4 |
| DashScope API Key | ASR 语音识别 |

### 启动

```bash
# 1. 配置密钥（application.yml，或环境变量）
# 2. 启动 MySQL + Redis
# 3. 管理员权限运行（JNativeHook 热键 + LCU 认证需要）
mvn spring-boot:run
# 浏览器打开 http://localhost:8080
```

> ⚠️ 需**管理员权限**运行；游戏内按 **F6** 说话；建议**无边框窗口**或使用 F12 截图方案。

### 环境变量

| 变量 | 说明 |
|---|---|
| `CHAT_API_KEY` / `QWEN_API_KEY` | 千问 API Key |
| `MINIMAX_API_KEY` | embedding（可选，默认 qwen） |
| `DB_PASSWORD` | MySQL 密码 |

---

## 🏗 架构总览

```
英雄联盟客户端
   ├─ LCU API ──┬─ AutoWatcher(选人/局内采集) ──┐
   ├─ 2999 ─────┤   (service.lcu 包)            │
   └─ 屏幕 F12 ─┴─ QwenVisionService(视觉识别)   │
       ▲ F6 全局热键(VoiceHotkeyService)          │ 每1s
       │ 前端轮询 /api/voice/state                 ▼
                                              Redis leagaid:state
                                                  │ 每2s
用户(浏览器, 语音F6)                                ▼
   │ WebSocket /voice-ws(ASR) + GET /chat        GameStateService(缓存+会话锚点)
   ▼                                              │
ChatController ──> QueryRouter(硬路由)            ▼
   │              ├─ 命中 → 直接返回数据        GameStateTool.getGameState()(工具)
   │              └─ miss → ConsultantService     │  AI 按需调用
   ▼                                            ▼
ConsultantService (AiService) @MemoryId sessionId
   │  @SystemMessage 提示词（工具0-6 + 分类A-H + 对局引导）
   │  ChatMemory(JsonFile 持久化, 一局一个文件)
   ▼
qwen3.8-max（流式 + 工具调用, enable_thinking:false）
   ▲
   └─ Tools: getGameState / recognizeHex / saveHex /
             tryFixedQuery / queryDb / getSynergy / searchName / queryKnowledge
```

---

## 🧩 AI 工具链（Agent 能力）

| 工具 | 作用 |
|---|---|
| `getGameState()` | 当前对局实时状态（英雄/阵容/装备/熟练度/已选海克斯） |
| `recognizeHex()` | 实时截图识别屏幕上海克斯三选一 |
| `saveHex()` | 记录本局已选海克斯（Redis 持久化） |
| `tryFixedQuery()` | 固定查询：英雄胜率/海克斯排名/出装/玩法/组合 |
| `queryDb()` | 参数化 SQL（6 表，LLM 填参数不写 SQL） |
| `getSynergy()` | 机制联动分析（技能×海克斯×装备链式推理） |
| `searchName()` | 名称→ID 映射（含俗称/官方名/英文名） |
| `queryKnowledge()` | 语义检索（效果描述找装备/海克斯） |

---

## 📁 项目结构

```
src/main/java/com/example/demo/
├── controller/    # Chat/Game/Voice 接口
├── service/       # AI 服务 + lcu 采集端(Spring化)
├── ai/            # Agent 工具(路由/固定查询/自由SQL/RAG/游戏状态)
├── model/         # 数据模型
├── entity/        # MyBatis 实体
├── mapper/        # MyBatis Mapper
└── websocket/     # 语音 WebSocket
```

---

## ⚠️ 免责声明

本项目为个人学习项目，仅供学习交流。与 Riot Games 无任何官方关联。使用请自行评估游戏封号风险。

---

## 📄 完整技术文档

以下为项目开发过程中的完整记录（从 test-aiservices 更名而来）：

---

# 海克斯大乱斗 AI 助手 — 项目技术文档

| 能力 | 说明 |
|---|---|
| 🎤 语音对话 | 全屏游戏按住 F6 说话（JNativeHook 全局热键），松开自动发送，WebSocket 流式 ASR |
| 👁 一键选海克斯 | 说"选哪个海克斯"→ 自动 F12 截图 → qwen-vl-max 识图 → LLM 结合阵容推荐 |
| 📊 实时对局感知 | getGameState 工具，AI 自动获取自己/双方阵容/板凳/装备/熟练度 |
| 🗂 数据问答 | 胜率/海克斯排名/出装/玩法/组合，硬路由+固定查询+自由 SQL+RAG 四层 |
| 🔄 一局一轮对话 | sessionId 锚定，新一局自动开新会话 + 前端提示条 |
| 📜 历史会话 | 每局记忆持久化，可按时间查看/删除 |

---

## 二、架构总览

```
英雄联盟客户端
   ├─ LCU API ──┬─ AutoWatcher(选人/局内采集) ──┐
   ├─ 2999 ─────┤   (service.lcu 包)            │
   └─ 屏幕 F12 ─┴─ QwenVisionService(视觉识别)   │
       ▲ F6 全局热键(VoiceHotkeyService)          │ 每1s
       │ 前端轮询 /api/voice/state                 ▼
                                              Redis leagaid:state
                                                  │ 每2s
用户(浏览器, 语音F6)                                ▼
   │ WebSocket /voice-ws(ASR) + GET /chat        GameStateService(缓存+会话锚点)
   ▼                                              │
ChatController ──> QueryRouter(硬路由)            ▼
   │              ├─ 命中 → 直接返回数据        GameStateTool.getGameState()(工具)
   │              └─ miss → ConsultantService     │  AI 按需调用
   ▼                                            ▼
ConsultantService (AiService) @MemoryId sessionId
   │  @SystemMessage 提示词（工具0-6 + 分类A-H + 对局引导 + 回答规则）
   │  ChatMemory(JsonFile 持久化, 一局一个文件)
   ▼
qwen3.8-max（流式 + 工具调用, enable_thinking:false）
   ▲
   └─ Tools:
       ├─ L0 getGameState  → 当前对局实时状态
       ├─ L1 tryFixedQuery → 固定查询（MyBatis）
       ├─ L2 queryDb/searchName/getSynergy → 自由 SQL（JdbcTemplate）
       └─ L3 queryKnowledge → 语义检索（Redis Vector Set）
```

## 三、技术栈

| 组件 | 选型 | 备注 |
|---|---|---|
| 框架 | Spring Boot 3.4 + LangChain4j 1.15 | 单进程（采集+AI+Web） |
| LLM | qwen3.8-max | enable_thinking:false + QwenStreamingChatModel 清洗工具参数尾逗号 |
| 视觉 | qwen-vl-max | F12 截图 → 裁剪 → 识图海克斯 |
| Embedding | qwen text-embedding-v4 (1024维) | |
| ASR | qwen-audio-3.0-asr-flash-streaming | WebSocket 真流式 + 全量热词(593) |
| RAG | Redis 8 Vector Set | VADD/VSIM，毫秒级 |
| 采集 | leagAid（service.lcu） | LCU + 2999 + 全局热键 |
| 热键 | JNativeHook | F6 全局热键（全屏可用） |
| 查询 | MyBatis 动态SQL + JdbcTemplate | 参数化，不写SQL |
| 数据库 | MySQL 8 | 7 表 |
| 记忆 | JsonFileChatMemoryStore | 一局一个文件 |
| 前端 | static/index.html | 纯语音交互 |

---

## 四、目录结构

```
src/main/java/com/example/demo/
├── DemoApplication.java        # 入口（headless=false + @EnableScheduling）
├── AppConfig.java              # AI 服务装配（tools 注册）
├── controller/
│   ├── ChatController.java     # /chat 对话 + 会话历史
│   ├── GameController.java     # /api/game/state + /hex/recognize
│   └── VoiceController.java    # /api/voice/state（热键状态）
├── service/
│   ├── ConsultantService.java  # AiService 接口 + 系统提示词
│   ├── GameStateService.java   # 读 Redis 快照 + 会话锚定
│   ├── QwenVisionService.java  # F12 截图 + qwen-vl 识别
│   ├── QwenAsrService.java     # ASR 流式识别
│   ├── AramggDataService.java  # aramgg 数据采集
│   ├── JsonFileChatMemoryStore.java  # 记忆持久化
│   └── lcu/                    # leagAid 采集（Spring 化）
│       ├── AutoWatcher / DataHub / DataHubRedisSync
│       ├── LcuClient / LcuNtAuth / GameDataReader / GamePhaseWatcher
│       ├── TeammateAnalyzer / MiniRedisClient / VoiceHotkeyService / LeagAidRunner
├── ai/
│   ├── DatabaseTools.java      # getSchema/searchName/queryDb/getSynergy
│   ├── FixedQueryTools.java    # tryFixedQuery 固定查询
│   ├── QueryRouter.java        # 硬路由
│   ├── GameStateTool.java      # getGameState 工具
│   ├── DynamicContentRetriever.java  # queryKnowledge RAG
│   └── RedisVectorStore.java   # 向量库
├── model/                      # GameState 等
├── entity/  mapper/  websocket/
```

---

## 五、核心设计

### 1. 四层路由（AI 回答）

| 层 | 触发 | 作用 |
|---|---|---|
| 硬路由 QueryRouter | 代码正则 | 高频固定句式 0 token 短路 |
| tryFixedQuery | LLM 先试 | 英雄完整数据包（胜率/海克斯/出装/组合） |
| queryDb/getSynergy | LLM 判断 | 参数化查询/机制联动分析 |
| queryKnowledge | LLM 判断 | 语义检索兜底 |

**去冗余**：硬路由 miss 后，系统提示词约束"固定查询已试过，不重复调 tryFixedQuery"，用户消息保持纯净（前缀不再污染记忆/前端）。

### 2. getGameState 工具（对局感知）

AI 遇到对局问题（选海克斯/出装/克制/板凳）先调 `getGameState()`，返回：阶段/自己英雄/板凳/双方阵容（含熟练度+装备）。再配合 queryDb 查胜率做推荐。

### 3. 一键海克斯识别

```
语音"选哪个海克斯" → 后端模拟 F12 截图 → 裁剪3条海克斯区域
  → qwen-vl-max 识图（提示词强制"只输出3行名称"）→ cleanName 清洗
  → 拼进问题 + getGameState 上下文 → LLM 流式推荐
```

### 4. 一局 = 一轮对话（会话锚定）

- 选人阶段生成 sessionId（时间戳），一局到底不换
- 局内 gameId 锁定（重连识别同一局）
- 新一局自动切换：`GameStateService` 检测 + 前端 `syncSession` 轮询 → 清空 + 提示条

### 5. 全局热键语音

- JNativeHook 监听 F6 按下/松开 → `VoiceHotkeyService` 状态
- 前端每 300ms 轮询 `/api/voice/state` → 控制浏览器录音
- 解决：全屏游戏时浏览器收不到按键

---

## 六、数据层（Redis 快照）

### Redis key: `leagaid:state`

每 1s 由 leagAid 覆盖写，每 2s 由 GameStateService 读取缓存。结构：

```json
{
  "phase": "InProgress", "myChampion": "铁铠冥魂", "myPuuid": "xxx",
  "currentGameId": "500852387674",
  "players": [
    {"puuid":"...","name":"牢 大","team":"我方","champion":"铁铠冥魂",
     "mastery":"等级8 64145点 最高","games":50,"winRate":38.0,
     "kda":"4.9/5.2/21.7","style":"可能偏弱",
     "level":10,"kills":3,"deaths":2,"assists":8,"items":"心之钢 铁板靴"}
  ],
  "bench": ["破败之王","暮光星灵"],
  "hexOptions": [],
  "updatedAt": 1786690385816
}
```

- 我方 name 无 #tag（选人阶段录入），对面带 #tag（局内 2999）
- 账号层（熟练度/胜率/KDA/风格）+ 实时层（等级/装备）统一 Player 模型

### 采集链路（service.lcu）

| 阶段 | 数据 | 来源 |
|---|---|---|
| 选人 | 我方 5 人账号层 + 板凳 | LCU champ-select session |
| 局内 | 10 人实时层（装备/KDA/等级） | 2999 playerlist |
| 局内 | 对面账号层补查 | LCU summoners 反查 puuid |
| 中途进入 | 全部 10 人账号层补查 | 同上（独立线程） |
| 全程 | gameId 锚点 | gameflow session |

敌我判定：2999 的 ORDER/CHAOS + 自己 riotId 定位（不依赖选人 session）。

### Redis 向量库（RAG）

- Redis 8 Vector Set（VADD/VSIM/VSETATTR/VGETATTR/VCARD）
- Jedis `CommandArguments` 执行（`sendCommand` 大参数有 bug）
- NaN 过滤：`Float.isNaN(v) ? 0f : v`
- 数据：海克斯 236 + 装备 + 英雄档案 173 ≈ 1103 条

### 动态 SQL（queryDb）

- `HeroMapper.dynamicQuery(table, heroId, keyword, tier, order, limit)`，6 表 `<choose>`+`<if>` 分支
- 表名白名单，参数化 `#{}`，杜绝 SQL 注入和语法错误
- **排序语义**：`hero_augment_rank`/`heroes` 固定 `ORDER BY win_rank ASC`（排名越小越强），不让 LLM 传 order
- **LIKE 职责收窄**：SQL 只匹配名字，效果描述检索全交 RAG

---

## 七、环境依赖与启动

| 组件 | 位置 | 说明 |
|---|---|---|
| Redis 8.6.3 | `C:\Users\lu\Desktop\工具\Redis-8.6.3-...` | 向量库 + 游戏快照 |
| MySQL 8 | 服务 MySQL80 | 7 表 |
| 启动脚本 | `C:\Users\lu\Desktop\工具\start_env.bat` | 一键拉起 |
| 游戏截图目录 | `C:\WeGameApps\英雄联盟\Game\Screenshots` | F12 截图落地 |

**启动步骤**：
1. 运行 `start_env.bat`（MySQL + Redis）
2. **管理员权限**启动 DemoApplication（JNativeHook 热键 + LCU 认证需要）
3. 浏览器打开 `localhost:8080`，进游戏按住 F6 说话

**关键配置**（application.yml）：qwen.api-key / qwen.vl-model / app.redis / app.game-state-key / app.screenshot-dir

---

## 八、开发记录（关键决策）

| 日期 | 决策/修复 | 原因 |
|---|---|---|
| 08-14 | 单项目合并（leagAid Spring 化） | 单进程全通，不用双项目+Redis 中转 |
| 08-14 | F12 游戏内截图替代 Robot/mss | 独占全屏下桌面截图黑屏 |
| 08-14 | 全局热键 F6 驱动语音 | 全屏游戏浏览器收不到按键 |
| 08-14 | getGameState 工具化（非注入） | 信息按需取，省 token，AI 自主判断 |
| 08-14 | 系统提示词重写（修乱码） | 旧文件被脚本损坏 |
| 08-14 | 前缀不污染用户消息 | 改由提示词约束，记忆/前端纯净 |
| 08-15 | items 采集移除 maps30 过滤 | build 含合成件，过滤后 id 匹配不上 |
| 08-15 | hero_item_build 改 LEFT JOIN | 保留匹配不上的行 |
| 08-15 | QwenStreamingChatModel 清洗尾逗号 | qwen 流式工具参数偶发非法 JSON |
| 08-15 | 会话锚定 gameId（局内为准） | 选人阶段 gameId 是占位 |

### 踩坑备忘

- **headless**：Spring 默认 headless=true → Robot 报错 → `System.setProperty("java.awt.headless","false")`
- **currentSummoner 返回数组** `[{}]` → 解析先取 get(0)
- **LCU 未就绪**：currentSummoner 返回空对象 → 无限重试直到 puuid 非空
- **match-history 偶发返回不全**：分页补齐 + 重试（`getWithRetry` 处理 `__EXCEPTION__`）
- **语音 WebSocket**：`send('end')` 后不能立即 close（丢 final）；`recognizer.stop()` 放独立线程
- **JNativeHook**：需管理员权限；Spring 单进程下 Esc 不杀 JVM 只注销热键

---

## 九、遗留/待办

- [ ] 装备数据重采（`/refresh`）后验证 hero_item_build 出装推荐
- [ ] 意图前置：硬路由规则扩充（中频问题轻量分类器）
- [ ] 给 RAG 喂攻略/排行文本
- [ ] 局内自动推荐（阶段2）：phase 变化自动开新会话 + 选人自动推荐
- [ ] 端到端实测：Redis 向量检索 + 动态 SQL 全链路（LLM 自动）

---

## 十、已废弃文档说明

本文档由原 `PROGRESS.md` / `REDIS_DYNAMIC_SQL.md` / `VOICE_INPUT.md` / `MERGE_PLAN.md` / `LEAGAID_INTEGRATION.md` 合并而来，旧文件已删除。详细历史开发记录见 git（如有）或本文件第八节决策表。
