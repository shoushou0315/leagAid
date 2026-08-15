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

- 🎤 **全语音交互**：按住 **F6** 说话，松开自动识别发送（JNativeHook 全局热键，**全屏游戏可用**）
- 👁 **一键选海克斯**：说"选哪个海克斯"→ 自动截图 → **qwen-vl-max 视觉识别** → LLM 结合阵容/装备推荐
- 📊 **实时对局感知**：AI 自动获取自己/双方阵容/板凳/装备/熟练度/已选海克斯
- 🧠 **多工具 Agent**：9 个工具（固定查询 / 参数化查询 / 机制联动 / 语义检索 / 游戏状态 / 海克斯识别）
- 🔄 **一局一轮对话**：sessionId 锚定，换局自动切会话，记忆持久化
- 🛡 **防幻觉**：所有数据来自工具返回，查不到如实说明，不编造胜率/排名

## 🚀 快速开始

### 前置依赖

| 组件 | 说明 |
|---|---|
| JDK 21 | |
| MySQL 8 | 自动建表（schema.sql，7 张表） |
| Redis 8 | 游戏快照 + 已选海克斯历史 + 向量库 |
| 阿里云百炼 API Key | qwen3.8-max / qwen-vl-max / text-embedding-v4 / ASR 语音识别 |
| 英雄联盟客户端 | 运行时读取实时对局数据 |

### 启动

```bash
# 1. 配置密钥（application.yml，或环境变量）
# 2. 启动 MySQL + Redis
# 3. 管理员权限运行（JNativeHook 热键 + LCU 认证需要）
mvn spring-boot:run
# 浏览器打开 http://localhost:8080
```

> ⚠️ 需**管理员权限**运行；启动后等待 LOL 客户端（自动重连，先开项目后开游戏也可）；游戏内按 **F6** 说话。

### 环境变量

| 变量 | 说明 |
|---|---|
| `CHAT_API_KEY` | 千问对话 API Key（默认模型 qwen3.8-max） |
| `QWEN_API_KEY` | 千问视觉/embedding/语音 API Key |
| `DB_USER` / `DB_PASSWORD` | MySQL 账号密码 |

其余可选：`CHAT_MODEL` / `QWEN_VL_MODEL` / `QWEN_EMBEDDING_MODEL` / `REDIS_HOST` / `REDIS_PORT` / `APP_MEMORY_DIR` 等。

---

## 🏗 架构总览

```
英雄联盟客户端
   ├─ LCU API ────┬─ AutoWatcher(选人/局内采集)
   ├─ 2999 ───────┤   (service.lcu 包，Spring 单进程)
   └─ 屏幕 F12 ───┴─ QwenVisionService(视觉识别)
        ▲ F6 全局热键(JNativeHook / VoiceHotkeyService)
        │ 前端每 300ms 轮询 /api/voice/state 驱动录音
        ▼
   ┌──────────────────────────────────────┐
   │ Redis                                  │
   │   leagaid:state       游戏快照(1s覆盖写)│
   │   hex:history:{session} 已选海克斯     │
   │   vec:rag             向量库(VADD/VSIM)│
   └──────────────────────────────────────┘
        │ GameStateService 每 2s 读快照 + 会话锚定
        ▼
用户(浏览器)
   ├─ WebSocket /voice-ws  PCM 流式 ASR
   ├─ GET /chat             SSE 流式回答
   └─ GET /api/game/hex/recognize  SSE 一键海克斯识别
        ▼
ChatController ──> QueryRouter(硬路由，正则)
   ├─ 命中 → 直接返回数据（0 次 LLM 调用）
   └─ miss → ConsultantService(AiService)
              │ @SystemMessage 提示词（工具0~6 + 分类A-H + 对局引导）
              │ ChatMemory(JsonFile 持久化, 一局一个文件)
              ▼
         qwen3.8-max（流式 + 工具调用, enable_thinking:false）
              ▲
              └─ Tools: getGameState / recognizeHex / saveHex /
                        getSchema / searchName / queryDb / getSynergy /
                        tryFixedQuery / queryKnowledge
```

---

## 🧩 AI 工具链（Agent 能力）

| 工具 | 作用 |
|---|---|
| `getGameState()` | 当前对局实时状态（阶段/我玩的英雄/板凳/双方阵容含熟练度装备/已选海克斯） |
| `recognizeHex()` | 实时截图识别屏幕上海克斯三选一 |
| `saveHex()` | 记录本局已选海克斯（Redis 持久化） |
| `getSchema()` | 数据库全部表结构与查询参数说明 |
| `searchName()` | 名称→id 映射（称号/官方中文名/英文名） |
| `queryDb()` | 参数化动态查询（MyBatis 动态 SQL，6 表白名单，LLM 填参数不写 SQL） |
| `getSynergy()` | 机制联动分析（英雄技能 × 海克斯/装备效果链式推理） |
| `tryFixedQuery()` | 固定查询：英雄胜率/海克斯排名/出装/玩法/三连组合/排行榜 |
| `queryKnowledge()` | 语义检索：按效果/机制描述找装备或海克斯（Redis 8 Vector Set） |

### 回答路由

1. **硬路由 `QueryRouter`**（代码层）：正则识别高频固定查询（英雄排行/数据包/"英雄有了X"组合），命中直接返回数据，0 次 LLM 调用。
2. **LLM 工具链**（硬路由 miss 后）：提示词按问题类型 A-H 分流——
   - 数据类：`tryFixedQuery` 固定查询 → 未命中走 `queryDb` 参数化查询 / `getSynergy` 机制联动
   - 描述类：`queryKnowledge` 语义检索（RAG），**与动态 SQL 并行车道**（`queryDb` 的 keyword 只匹配名字，按效果/机制描述找装备/海克斯必须走 RAG）

### 防幻觉设计

- 对局数据（海克斯选项/阵容/装备/板凳）**必须**来自 `getGameState()`/`recognizeHex()` 实时工具，不信历史记忆，工具与记忆冲突时以实时为准
- 工具返回"无结果"时明确告知用户，**禁止编造任何胜率/排名/场次/装备效果数字**
- 按阶段判断可用数据：选人阶段无装备/对面阵容，如实说明

---

## 📊 数据采集（service.lcu）

| 阶段 | 数据 | 来源 |
|---|---|---|
| 选人 | 我方 5 人账号层（熟练度/胜率/KDA/风格）+ 板凳 | LCU champ-select session（并行 5 线程 + Semaphore(3) 限流） |
| 局内 | 10 人实时层（装备/KDA/等级） | 2999 playerlist（每 5s） |
| 局内 | 对面账号层补查 | LCU summoners 反查 puuid（独立线程，5 并发） |
| 全程 | gameId 锚点（新局清空旧数据） | gameflow session |

- 敌我判定：2999 的 ORDER/CHAOS + 自己 riotId 定位
- 快照写入：DataHubRedisSync 每 1s 覆盖写 Redis `leagaid:state`；GameStateService 每 2s 读取缓存
- 重连机制：LeagAidRunner 每 5s 尝试连 LCU，连上才启动采集；先开项目后开游戏也能自动接上

### 会话锚定（一局一轮对话）

- 选人阶段生成 sessionId（时间戳），一局到底不换 id
- 局内绑定 gameId（重连识别同一局；中途进入局内用 gameId 补建会话）
- 新一局自动切新会话 + 清理上一局已选海克斯（Redis `hex:history:{sessionId}`）

---

## 📁 项目结构

```
src/main/java/com/example/demo/
├── DemoApplication.java        # 入口（headless=false + @EnableScheduling）
├── AppConfig.java              # AiService 装配（tools 注册）
├── controller/
│   ├── ChatController.java     # /chat 对话(SSE) + 会话管理 + 数据刷新
│   ├── GameController.java     # /api/game/state + /api/game/hex/recognize
│   └── VoiceController.java    # /api/voice/state（热键状态）
├── service/
│   ├── ConsultantService.java  # AiService 接口 + 系统提示词
│   ├── GameStateService.java   # 读 Redis 快照 + 会话锚定
│   ├── QwenVisionService.java  # F12 截图 + qwen-vl-max 海克斯识别
│   ├── QwenAsrService.java     # 流式语音识别（qwen-audio，热词增强）
│   ├── HexHistoryService.java  # 已选海克斯历史（Redis）
│   ├── AramggDataService.java  # aramgg 数据采集 + 建向量索引
│   ├── JsonFileChatMemoryStore.java  # 对话记忆持久化
│   └── lcu/                    # 采集端（Spring 化单进程）
│       ├── AutoWatcher / GamePhaseWatcher / LcuClient / LcuNtAuth
│       ├── TeammateAnalyzer / GameDataReader / DataHub / DataHubRedisSync
│       ├── MiniRedisClient / VoiceHotkeyService / LeagAidRunner
├── ai/
│   ├── DatabaseTools.java      # getSchema/searchName/queryDb/getSynergy
│   ├── FixedQueryTools.java    # tryFixedQuery 固定查询
│   ├── QueryRouter.java        # 硬路由
│   ├── GameStateTool.java      # getGameState/saveHex
│   ├── HexRecognizeTool.java   # recognizeHex
│   ├── DynamicContentRetriever.java  # queryKnowledge（RAG）
│   └── RedisVectorStore.java   # Redis 8 Vector Set
├── model/                      # GameState/QwenEmbeddingModel/QwenStreamingChatModel
├── entity/  mapper/            # MyBatis 实体与 Mapper
└── websocket/                  # VoiceWebSocketHandler / WebSocketConfig（/voice-ws）
```

---

## 🔌 HTTP 接口

| 接口 | 说明 |
|---|---|
| `GET /chat?sessionId=&message=` | 对话（SSE 流式） |
| `GET /api/game/state` | 当前对局快照 |
| `GET /api/game/hex/recognize` | 一键海克斯识别 + LLM 推荐（SSE） |
| `GET /api/voice/state` | F6 热键状态（前端轮询） |
| `WS /voice-ws` | PCM 流式语音识别（中间+最终结果） |
| `GET /sessions` / `GET /session/{id}` / `DELETE` | 会话历史查看/删除 |
| `GET /refresh` | 数据同步 + 向量索引重建 |
| `GET /status` / `GET /route-stats` | 服务/路由统计 |

---

## ⚠️ 免责声明

本项目为个人学习项目，仅供学习交流。与 Riot Games 无任何官方关联。数据来源为公开接口与 aramgg.com 静态数据；使用请自行评估游戏封号风险。
