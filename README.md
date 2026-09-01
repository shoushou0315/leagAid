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
- 👁 **一键选海克斯**：说"选哪个海克斯"→ 自动截图 → **qwen-vl-max 视觉识别** → LLM 结合**英雄被动+技能联动**、阵容、装备推荐
- 📊 **实时对局感知**：AI 自动获取自己/双方阵容/板凳/装备/熟练度/已选海克斯
- 🧭 **确定性意图路由**：LLM 做结构化意图分类（替代脆弱正则），跨对话记忆"已拥有海克斯"前提；**代码前置取数注入**，关键对局数据由代码先取再喂给模型，漏调工具也有数据、机制上防幻觉
- 🧠 **多工具 Agent**：10 个工具（固定查询 / 参数化查询 / 机制联动 / 语义检索 / 游戏状态 / 海克斯识别 / 知识库更新），**ReAct 思考模式**引导（思考→行动→观察→回答）
- 🛡 **工具降级**：每个工具失败都有兜底返回，Redis/截图/查询异常时不中断对话，Agent 稳定不崩
- 🔄 **一局一轮对话**：sessionId 锚定，换局自动切会话，记忆持久化；**支持手动新建会话**（不开游戏也能查看/续聊历史），断线重连不误切会话
- 💬 **回答范畴放宽**：游戏问题走工具链专业回答；非游戏闲聊用常识友好回应
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
# 1. 配置密钥（config.yml，见下）
# 2. 启动 MySQL + Redis
# 3. 管理员权限运行（JNativeHook 热键 + LCU 认证需要）
mvn spring-boot:run
# 浏览器打开 http://localhost:8080
```

- **一键脚本**：双击根目录 `start.bat`（自提权 → 拉起 Redis/MySQL → 解压应用 → 打开浏览器 → 脚本自动关闭，应用日志窗口保留）
- **本机密钥**：根目录 `config.yml`（已在 .gitignore 排除，勿提交）；对方使用可参考 `config.example.yml` 模板
- **开发运行**：IDEA 直接 Run `DemoApplication`，默认激活 `local` profile，加载 `application-local.yml`（真实密钥/密码，该文件不入库）

> ⚠️ 需**管理员权限**运行；启动后等待 LOL 客户端（自动重连，先开项目后开游戏也可）；游戏内按 **F6** 说话。

### 环境变量

| 变量 | 说明 |
|---|---|
| `CHAT_API_KEY` | 千问对话 API Key（默认模型 qwen3.8-max） |
| `QWEN_API_KEY` | 千问视觉/embedding/语音 API Key |
| `DB_USER` / `DB_PASSWORD` | MySQL 账号密码 |

其余可选：`CHAT_MODEL` / `QWEN_VL_MODEL` / `QWEN_EMBEDDING_MODEL` / `REDIS_HOST` / `REDIS_PORT` / `APP_MEMORY_DIR` 等。

### 语音交互特性

- **SSE 热键推送**：F6 状态变化实时推送（<50ms），替代轮询；连接建立先推当前值防丢边沿
- **pre-roll 防丢字**：页面加载即预初始化录音管线，按住瞬间先补发最近 400ms 音频，第一个字不丢

---

## 🏗 架构总览

```
英雄联盟客户端
   ├─ LCU API ────┬─ AutoWatcher(选人/局内采集)
   ├─ 2999 ───────┤   (service.lcu 包，Spring 单进程)
   └─ 屏幕 F12 ───┴─ QwenVisionService(视觉识别)
        ▲ F6 全局热键(JNativeHook / VoiceHotkeyService)
        │ SSE 实时推送 /api/voice/events 驱动录音（免轮询，响应<50ms）
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
ChatController ──> IntentClassifier(LLM 结构化意图分类，enum 列表)
   │ 多意图 / 跨轮记忆"已拥有海克斯"
   ↓
   GameContextInjector(代码前置取数: getGameState + 已拥有海克斯)
   │ 注入前缀 → ConsultantService(AiService)
   │ @SystemMessage 提示词（工具 + 意图分流 + 对局引导 + 防幻觉/防硬编）
   │ ChatMemory(JsonFile 持久化, 一局一个文件)
   ▼
   qwen3.8-max（流式 + 工具调用, enable_thinking:false）
        ▲
        └─ Tools: getGameState / recognizeHex / saveHex /
                  getSchema / searchName / queryDb / getSynergy /
                  tryFixedQuery / queryKnowledge / updateKnowledge
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
| `queryDb()` | 参数化动态查询（MyBatis 动态 SQL，5 表白名单，LLM 填参数不写 SQL） |
| `getSynergy()` | 机制联动分析（英雄技能 × 海克斯/装备效果链式推理） |
| `tryFixedQuery()` | 固定查询：英雄胜率/海克斯排名/出装/玩法/排行榜，按问题类型裁剪返回 |
| `queryKnowledge()` | 语义检索：按效果/机制描述找装备或海克斯（Redis 8 Vector Set）；**凡按语义描述找对象必须用它，防止模型凭记忆硬编** |
| `updateKnowledge()` | 更新知识库：全量同步数据 + 重建向量索引（语音"更新知识库"触发） |

### 图片显示

- AI 回答中提到的英雄/装备/海克斯**自动附带图标**：提示词引导 LLM 输出 `【img:URL】` 标记（URL 来自工具返回的真实 `image_url`，禁编造），前端渲染成小图；本地名称映射兜底
- 装备表（items）只保留**大乱斗可用版本**（清洗 22/77 前缀残留，同名装备唯一 id），避免匹配到错误版本

### 回答路由

1. **意图分类 `IntentClassifier`**（LLM 结构化，enum 列表）：把问题归为 HEX_PICK/BUILD/COUNTER/SYNERGY/FREE_QUERY/DESCRIPTIVE/UPDATE_DB/CHAT，可多意图；声明"已有 X"归 SYNERGY、追问承接上文主题，避免误判。
2. **代码前置取数注入 `GameContextInjector`**：按意图由代码先取 `getGameState` + 会话内"已拥有海克斯"，拼成注入前缀喂给模型（无游戏也能注入已拥有前提）。模型只做推理与措辞，关键数据不靠模型自觉调工具。
3. **LLM 工具链**（注入后的主回答）：
   - 数据/固定查询（胜率/排行/出装/玩法/数据包）：提示词强制**先调 `tryFixedQuery`**，命中直接答；未命中才走 `queryDb` 参数化 / `getSynergy` 联动
   - 描述类：按"需求/机制/效果"找对象（克回血、克护盾、X 最合适什么）→ **必须 `queryKnowledge`（RAG）**，因"需求→对象"关系不在库（`queryDb` 的 keyword 只匹配名字）
   - 选海克斯：`recognizeHex` 截图识别 → `getGameState` 看阵容/装备 → **`getSynergy` 逐条分析被动+Q/W/E/R 与候选海克斯联动** → 结合对面阵容/已有出装推荐
   - 更新类：`updateKnowledge` 触发全量同步 + 重建向量索引（更新期间 `/chat` 拦截所有回答）

### 防幻觉设计

- 对局数据（海克斯选项/阵容/装备/板凳）**必须**来自 `getGameState()`/`recognizeHex()` 实时工具，不信历史记忆，工具与记忆冲突时以实时为准
- **代码前置注入兜底**：关键数据由 `GameContextInjector` 先取再注入，模型漏调工具也有数据；注入里声明"数据已由代码获取"禁止重复调用获取同一信息
- 「**已拥有海克斯**」作为确定性前提每轮注入（跨对话固化），模型不再反复"我漏看了"
- **查询工具分工**：按名字查走 `queryDb`，按语义/效果描述找**必须走 `queryKnowledge`（RAG）**，禁止凭记忆硬编对象名/效果；对抗类先确认对面英雄机制再按弱点检索
- 工具返回"无结果"时明确告知用户，**禁止编造任何胜率/排名/场次/装备效果数字**；海克斯/装备效果只引用工具返回原文
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
- 热键解耦：F6 语音热键在 Spring 启动即注册，**不依赖游戏客户端**（游戏没开也能语音对话，开了才补对局数据）

### 会话锚定（一局一轮对话 + 手动会话）

- 选人阶段生成 sessionId（时间戳），一局到底不换 id；局内绑定 gameId（重连识别同一局；中途进入局内用 gameId 补建会话）
- 新一局自动切新会话 + 清理上一局已选海克斯（Redis `hex:history:{sessionId}`）
- **会话防抖 + 手动模式**：LCU 重试/阶段抖动不反复新建；可手动新建会话（不开游戏也能查看/续聊历史），关闭"跟随对局"即进入手动浏览
- **断线兜底**：重连失败时对局态置 `None`（防残留假对局），重连成功同局不误切、换局正常切换

---

## 📁 项目结构

```
src/main/java/com/example/demo/
├── DemoApplication.java        # 入口（headless=false + @EnableScheduling）
├── AppConfig.java              # AiService 装配（tools 注册）
├── controller/
│   ├── ChatController.java     # /chat 对话(SSE) + 会话管理 + 数据刷新
│   ├── GameController.java     # /api/game/state + /api/game/hex/recognize
│   └── VoiceController.java    # /api/voice/state + /api/voice/events（SSE 热键推送）
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
│   ├── Intent.java               # 意图枚举（HEX_PICK/BUILD/COUNTER/SYNERGY/...）
│   ├── IntentClassifier.java     # 结构化意图分类（AiService，enum 列表）
│   ├── GameContextInjector.java  # 代码前置取数 + "已拥有海克斯"注入
│   ├── DatabaseTools.java      # getSchema/searchName/queryDb/getSynergy
│   ├── FixedQueryTools.java    # tryFixedQuery 固定查询
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
| `GET /api/voice/state` | F6 热键当前状态（兼容查询） |
| `GET /api/voice/events` | F6 热键状态变化 SSE 推送（前端 EventSource 订阅，免轮询） |
| `WS /voice-ws` | PCM 流式语音识别（中间+最终结果） |
| `GET /sessions` / `GET /session/{id}` / `DELETE` | 会话历史查看/删除 |
| `GET /refresh` | 数据同步 + 向量索引重建 |
| `GET /status` | 服务就绪状态 |

---

## ⚠️ 免责声明

本项目为个人学习项目，仅供学习交流。与 Riot Games 无任何官方关联。数据来源为公开接口与 aramgg.com 静态数据；使用请自行评估游戏封号风险。
