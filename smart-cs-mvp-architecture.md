# 智能客服系统 — MVP 方案

> 版本：MVP-1.0（as-built） | 日期：2026-08-14
>
> 基于 [smart-cs-architecture.md](./smart-cs-architecture.md)（v2.1）裁剪，并与当前仓库实现对齐。
> **组件骨架齐全，业务只跑通主流程**；完整能力按里程碑回填，避免二次拆架构。

---

## 目录

1. [MVP 目标与边界](#1-mvp-目标与边界)
2. [架构总览（组件保留）](#2-架构总览组件保留)
3. [主业务流程](#3-主业务流程)
4. [模块落地范围](#4-模块落地范围)
5. [编排与状态机（精简）](#5-编排与状态机精简)
6. [Agent / 工具 / CONFIRM](#6-agent--工具--confirm)
7. [事件与 API](#7-事件与-api)
8. [数据与基础设施](#8-数据与基础设施)
9. [可观测（LangFuse 双轨）](#9-可观测langfuse-双轨)
10. [验收标准](#10-验收标准)
11. [演进路线](#11-演进路线)

---

## 1. MVP 目标与边界

### 1.1 目标（一句话）

用户能通过 SSE 完成：**开会话 → 意图分流 → 查订单 / 改址 / 问知识 / 申请退款（确认后执行）→ 必要时转人工提示**，全链路可观测、可部署。

### 1.2 必须保留的组件

与完整方案一致，**模块与中间件不删**，避免后续加功能时推倒重来：

| 层次 | 组件 | MVP 用法 |
|------|------|----------|
| 接入 | `cs-gateway` | SSE/REST、会话 CRUD、基础鉴权/限流 |
| 编排 | `cs-orchestrator` | Supervisor + 规则 Router + sticky + CONFIRM 续跑 |
| Agent | `cs-agents` | 仅启用主流程 Agent（见 §6）；PreSales/Complaint 类保留、默认不挂 Supervisor |
| 工具 | `cs-tools` | 订单（MCP）、知识检索入口、退款、转人工；PermissionGate |
| 订单 | `cs-order-service` | 独立进程；MCP Streamable HTTP 暴露 `query_order` / `modify_order_address`（进程内 Mock） |
| 记忆 | `cs-memory` | Redis 短期审计缓冲 + AgentScope `AgentStateStore`；`MilvusLongTermMemory` 挂 ReActAgent |
| 知识 | `cs-knowledge` | Milvus RAG（种子 FAQ 入库 + 检索；编排器显式调用，不挂 GenericRAGHook） |
| 基础设施 | `cs-infra` + `cs-common` | DashScope、LangFuse 双轨、PG、Redis、WebFlux |
| 运行时 | AgentScope Java 2.0.2 | ReAct + Tool Schema + Middleware + TracerRegistry |
| 部署 | Docker Compose + `start.sh` | PG + Redis + Milvus(+etcd/minio) + `cs-order` + `cs-app` |

### 1.3 明确不做（Post-MVP）

| 不做 | 原因 |
|------|------|
| 情感旁路 / 在线质检 | 不挡主路径，后置 |
| Complaint 独立进 Supervisor、完整风控矩阵 | 投诉意图映射 HUMAN；退款走简化 Gate |
| PreSales 复杂导购 | 售前意图映射 Knowledge；ProductQuery 可保留不挂主路径 |
| 真 token 流式（Phase B） | MVP 用假流式（算完再切句推 `token`） |
| 坐席工作台完整功能 | 仅「转人工」写 handoff + 用户侧排队提示；坐席 API stub |
| 多租户运营台 / 动态策略 | `tenantId=default` 贯穿即可 |
| 长期记忆精调 / 画像挖掘 | 已接线 AgentScope `LongTermMemory`，不做运营侧精调 |
| K8s / Istio / 渠道多端适配 | Compose 单机足够 |

### 1.4 原则继承

沿用完整方案四条硬原则：

1. **框架不自研** — AgentScope
2. **少 Agent、强会话态** — 路由是规则分类器，不是 ReAct
3. **写操作统一 PermissionGate + CONFIRM**
4. **单向量库 Milvus**；热状态 Redis；关系数据 PostgreSQL；订单能力经 MCP 外置

---

## 2. 架构总览（组件保留）

```
┌─────────────────────────────────────────────────────────────────┐
│                     接入层 (cs-gateway)                          │
│  SSE / REST · 会话 · 鉴权 · 限流 · Trace                         │
├─────────────────────────────────────────────────────────────────┤
│                   编排层 (cs-orchestrator)                        │
│  Supervisor · 规则 Router · Sticky · CONFIRM 续跑                │
│  Handoff（最小：入队提示）· RAG 显式注入（知识意图）               │
├─────────────────────────────────────────────────────────────────┤
│                     Agent 层 (cs-agents)                         │
│  Order · AfterSales · Knowledge · HumanCollab · ChitChat         │
│  （PreSales / Complaint：类保留，MVP 不挂 Supervisor）            │
├─────────────────────────────────────────────────────────────────┤
│                     工具层 (cs-tools)                             │
│  OrderQuery(MCP) · Refund(+Gate) · HumanHandoff · RAG Hook       │
├───────────────────────────────┬─────────────────────────────────┤
│  记忆 & 知识                  │  订单服务 (cs-order-service)     │
│  StateStore(Redis)            │  MCP /mcp · Mock OrderStore      │
│  ShortTerm(审计) · LTM(Milvus)│  query_order / modify_address │
│  RAG(Milvus FAQ)              │                                  │
├───────────────────────────────┴─────────────────────────────────┤
│                   基础设施 (cs-infra + cs-common)                 │
│  DashScope · LangFuse(ingestion + OTLP GenAI) · PG · Redis       │
└─────────────────────────────────────────────────────────────────┘
```

### 热路径（MVP）

```
User --> Gateway --> Session(Redis/PG) --> Supervisor
                         |                    |
                         |                    +--> Router(规则，无 LLM)
                         |                    +--> Domain Agent (ReAct)
                         |                    |      +--> Tools / MCP(订单)
                         |                    |      +--> RAG(知识意图)
                         |                    |      +--> StateStore / LTM
                         |                    +--> Memory(短期审计)
                         +--> SSE: token / confirmation / done
                                      |
                                      +--> LangFuse Track A/B
```

情感、质检、审计增强 **不进热路径**。

---

## 3. 主业务流程

MVP 保证以下路径端到端可用（与 `scripts/mvp-smoke.sh` 对齐）。

### 流程 A — 开聊与闲聊兜底

```
创建会话 → 用户发「你好」→ Router→CHITCHAT → 假流式回复 → 落库消息
```

### 流程 B — 订单查询 / 改址（经 MCP）

```
「查一下订单 ORD20260609001」→ ORDER → OrderQueryTool
  → OrderMcpClient → cs-order-service:/mcp (query_order) → 话术 + SSE
次轮同会话 sticky，跳过 Router
改址：modify_order_address（未发货可改；已发货拒绝并引导售后）
```

### 流程 C — 知识问答（RAG）

```
「怎么开具发票？」→ KNOWLEDGE → KnowledgeRAGHook.beforeReasoning
  → Milvus TopK → KnowledgeAgent 生成答案
无命中时礼貌降级，不编造关键政策
售前类问题：Router 映射为 KNOWLEDGE（不启独立 PreSales）
```

### 流程 D — 退款确认（写操作主路径）

```
轮次1:「我要退款 ORD…」→ AFTER_SALES → Gate→CONFIRM
       → PendingAction(Redis) → status=WAITING_CONFIRM → SSE confirmation
轮次2: POST confirm APPROVE → 幂等执行 Refund → 模板话术 → ACTIVE
       或用户说「取消」→ REJECT → ACTIVE → 可继续别的问题
```

合同与完整方案一致：**CONFIRM 只挂起工具；APPROVE 后不恢复完整 ReAct**。

### 流程 E — 转人工（最小）

```
「转人工」→ HUMAN_SERVICE → HumanHandoffTool
→ 写 handoff_record(QUEUED) → status=QUEUED → SSE queue_update
→ 用户侧提示「已排队，请稍候」
投诉类话术：Router 映射 HUMAN_SERVICE，走同一路径
坐席 accept/complete：stub API，可用 curl / smoke 脚本演练回退 ACTIVE
```

### 流程优先级示意

```
                    ┌─────────────┐
                    │  用户消息    │
                    └──────┬──────┘
                           v
              WAITING_CONFIRM? ──是──► 确认/取消/提示
                           │否
                           v
              QUEUED/HUMAN? ──是──► 排队提示 / AI 静默
                           │否
                           v
                    sticky 或 Router(规则)
                           │
        ┌──────────┬───────┼───────┬──────────┐
        v          v       v       v          v
     ORDER    AFTER_SALES  KNOW  HUMAN     CHITCHAT
     MCP查询    退款CONFIRM  RAG   入队       闲聊
     /改址
```

---

## 4. 模块落地范围

| 模块 | MVP Must | MVP Nice / Stub | 不做 |
|------|----------|-----------------|------|
| `cs-gateway` | stream、session CRUD、confirm API、基础 Filter | handoff stub 路由 | 多渠道适配 |
| `cs-orchestrator` | Supervisor、规则 Router、sticky、CONFIRM、状态机、RAG 显式注入 | transition 内存日志 | 情感采纳、复杂策略引擎 |
| `cs-agents` | Order / AfterSales / Knowledge / HumanCollab / ChitChat（ReAct + StateStore + LTM） | PreSales / Complaint 类保留 | Complaint 进 Supervisor |
| `cs-tools` | OrderQuery(MCP)、Refund、HumanHandoff、PermissionGate | ProductQuery / RiskAssess / ComplaintTool | 完整风控特征库 |
| `cs-order-service` | MCP `/mcp`、Mock OrderStore、健康检查 | — | 真实订单中心 |
| `cs-memory` | Redis 短期审计、`RedisAgentStateStore`、`MilvusLongTermMemory` | — | 画像挖掘 |
| `cs-knowledge` | 检索 + 种子 FAQ 入库（`auto-seed`） | 重排关闭或恒等 | 运营知识后台 |
| `cs-infra` | DashScope、PG、Redis、LangFuse 双轨、boundedElastic | — | R2DBC |
| `cs-common` | SessionStatus、Intent、事件模型 | — | — |

未挂 Supervisor 的 Agent **类保留在仓库**，避免空目录与二次拆分。

---

## 5. 编排与状态机（精简）

### 5.1 Supervisor 单轮（MVP）

```
1. 加载 Session；特殊状态走专用分支（WAITING_CONFIRM / QUEUED / HUMAN_ACTIVE）
2. 用户消息写入短期记忆（审计）+ PG
3. sticky？是 → 当前 Agent；否 → 规则 Router（无 LLM；PRE_SALES→KNOWLEDGE，COMPLAINT→HUMAN）
4. 更新 activeAgent、context；PG upsert
5. KNOWLEDGE 时编排器调用 KnowledgeRAGHook；LTM 由 Agent 内自动 retrieve
6. Agent.handle（boundedElastic；Sink unicast 防 SSE 丢事件）
7. PendingConfirmation？→ WAITING_CONFIRM + confirmation 事件；结束
8. Handoff？→ QUEUED + queue_update；结束
9. 否则假流式推 token → 落助手消息
10. LangFuse Track A endTrace；（Track B 由 AgentScope Tracer 异步上报）
```

### 5.2 Sticky 规则（MVP 子集）

**默认 sticky（空闲 ≤ 15 分钟）。** 仅以下触发重路由：

- 用户显式「转人工」
- 用户显式换题（「换个问题」「我还想问」）
- 会话空闲 > 15 分钟后的首条消息
- 强关键词打断（如「退款」+ 订单号 → AFTER_SALES）

不做：`agent_transition_log` 全量分析、低置信度复杂澄清链（低置信度 → CHITCHAT）。

### 5.3 会话状态（MVP 必备）

```
ACTIVE ──写确认──► WAITING_CONFIRM ──批准/拒绝/超时──► ACTIVE
ACTIVE ──转人工──► QUEUED ──(stub accept)──► HUMAN_ACTIVE ──complete──► ACTIVE
任意 ──关闭──► CLOSED
```

`PAUSED`、情感升级、技能组路由：**Post-MVP**。

**WAITING_CONFIRM 自由文本：** 确认语 → APPROVE；取消/换题 → REJECT 后正常路由；其它 → 提示先确认。

### 5.4 记忆分工（与实现一致）

| 层次 | 实现 | 职责 |
|------|------|------|
| 会话热状态 | Redis Session + PG upsert | status、activeAgent、context |
| 短期审计缓冲 | `ShortTermMemoryManager`（Redis） | 编排侧消息列表、handoff 摘要 |
| Agent 跨轮上下文 | AgentScope `AgentStateStore`（Redis） | 同 session ReAct 续聊 |
| 长期记忆 | `MilvusLongTermMemory`（STATIC_CONTROL） | Agent 内 retrieve / record |
| 知识 RAG | `KnowledgeRAGHook`（编排器显式） | 仅知识意图注入，不对齐 AS GenericRAGHook |

---

## 6. Agent / 工具 / CONFIRM

### 6.1 Intent → Agent（MVP）

| IntentType | Agent | 工具 | MVP |
|------------|-------|------|-----|
| `ORDER` | OrderAgent | OrderQueryTool → MCP | ✅ |
| `AFTER_SALES` | AfterSalesAgent | RefundTool | ✅ |
| `KNOWLEDGE` | KnowledgeAgent | RAG Hook | ✅ |
| `HUMAN_SERVICE` | HumanCollabAgent | HumanHandoffTool | ✅ 最小 |
| `CHITCHAT` | ChitChatAgent | 无 | ✅ |
| `PRE_SALES` | → 映射 KNOWLEDGE | — | ⏭ 独立 Agent |
| `COMPLAINT` | → 映射 HUMAN_SERVICE | — | ⏭ 独立 Agent |
| `RISK_CONTROL` | — | Gate / RiskAssess 简化 | ⏭ 独立 Agent |

### 6.2 订单 MCP 契约

| 项 | 值 |
|----|-----|
| 服务 | `cs-order-service`（默认 `:8081`） |
| 传输 | MCP Streamable HTTP，端点 `/mcp`（Stateless） |
| 工具 | `query_order`、`modify_order_address` |
| 客户端 | `OrderMcpClient`：短生命周期连接，避免长 session 断开后工具失效 |
| 配置 | `cs.order.mcp.url` / `ORDER_MCP_URL` |
| 种子单号 | 如 `ORD20260609001`（见 OrderStore） |

### 6.3 PermissionGate（MVP 三档）

| 条件 | 结果 |
|------|------|
| 写工具且无权限 / 明确禁止 | `DENY` |
| 退款等写工具，或金额 ≥ `require-confirmation-amount` | `CONFIRM` |
| 只读查询 | `AUTO` |

不做完整 risk=HIGH 矩阵。

### 6.4 PendingAction

结构、TTL、单 session 单 PENDING、幂等键、审计 `tool_call_log`：**与 v2.1 §6 一致**，MVP 必须实现。

---

## 7. 事件与 API

### 7.1 SSE 事件（用户侧）

| type | MVP |
|------|-----|
| `agent_start` / `agent_end` | ✅ |
| `token` | ✅（假流式） |
| `confirmation` | ✅ |
| `queue_update` | ✅（文案级） |
| `done` / `error` | ✅ |
| `tool_call` 友好文案 | 可选 |
| `human_message` | stub 坐席发消息时再开 |
| debug / thinking | 仅 LangFuse / `debug=true` |

### 7.2 API（MVP 契约）

```
POST   /api/v1/chat/sessions
GET    /api/v1/chat/sessions/{sessionId}
DELETE /api/v1/chat/sessions/{sessionId}

GET|POST /api/v1/chat/stream
POST     /api/v1/chat/confirmations/{id}    # APPROVE | REJECT

# 坐席 stub（演练用）
POST /api/v1/handoff/{id}/accept
POST /api/v1/handoff/{id}/complete

# 健康
GET /api/v1/chat/health
GET /actuator/health
```

订单服务：`GET http://localhost:8081/actuator/health`；MCP `http://localhost:8081/mcp`。

---

## 8. 数据与基础设施

### 8.1 存储职责（MVP）

| 存储 | 必须落地 | 可 stub / 后置 |
|------|----------|----------------|
| Redis | Session 热状态、短期审计、PendingAction、AgentStateStore、限流 | — |
| PostgreSQL | ChatMessage、ToolCallLog、HandoffRecord、Session upsert | Feedback、transition_log 表 |
| Milvus | 知识 chunk + 长期记忆 collection | 重排精调 |
| 进程内 | `cs-order-service` OrderStore Mock | 真实订单 DB |

### 8.2 技术选型（与仓库对齐）

| 项 | 选型 |
|----|------|
| 语言 / 运行时 | Java 21（父 POM compiler 可标 17；运行与 Docker 用 Temurin 21） |
| 框架 | Spring Boot 3.4 WebFlux |
| Agent | AgentScope Java **2.0.2** |
| LLM | DashScope（qwen3.7-plus 等档位） |
| 持久化 | PostgreSQL（JDBC/JPA on boundedElastic） |
| 缓存 | Redis 7 |
| 向量 | Milvus 2.4 |
| 可观测 | LangFuse（ingestion + OTLP GenAI） |
| 构建 / 部署 | Maven 3.9+ · Docker Compose · 多阶段 Dockerfile（`order-service` / `cs-app`） |

租户：`Reactor Context` + `tenantId` 默认 `"default"`，禁止 ThreadLocal。

**本地端口注意：** Compose 将 Redis 映射为宿主机 `6380→6379`，避免与本机其他 Redis（如 Langfuse）冲突；本地跑 app 时按需设置 `REDIS_PORT=6380`。

### 8.3 SLA（仅 Phase A）

| 指标 | MVP 目标 |
|------|----------|
| 完整文本回复（无工具） | P95 &lt; 5s |
| 含读工具 / MCP | P95 &lt; 8s |
| 确认后写执行 | P95 &lt; 3s |
| TTFB 真流式 | **不承诺** |

### 8.4 一键启动

```bash
# 基础设施 + 构建 + 订单 MCP(:8081) + 客服应用(:8080)
./start.sh all

# 或 Compose 全量（含 cs-order + cs-app）
docker-compose up -d
```

---

## 9. 可观测（LangFuse 双轨）

配置前缀：`cs.observability.langfuse`（`enabled` / `flush-enabled` / `otel-enabled`）。

| 轨道 | 机制 | 内容 |
|------|------|------|
| **Track A** | `LangFuseTracer` → `/api/public/ingestion` | 会话级 Trace：Router / Agent 编排 Span |
| **Track B** | `GenAiOtelTracer` → OTLP `/api/public/otel/v1/traces` | AgentScope LLM Prompt、Tool 调用（GenAI 语义） |

- Track B 启用且密钥有效时，`LangFuseAgentMiddleware` **跳过** Prompt/Tool 重复上报，避免双写。
- 密钥未配置时两边可降级为本地日志，不阻断主路径。
- 环境变量：`LANGFUSE_PUBLIC_KEY` / `LANGFUSE_SECRET_KEY` / `LANGFUSE_HOST`（或 `LANGFUSE_BASE_URL`）。

---

## 10. 验收标准

以下全部通过即视为 MVP 完成（推荐执行 `scripts/mvp-smoke.sh`）：

1. **会话**：创建 → 多轮闲聊 → 关闭；消息进 PG，热状态在 Redis。
2. **订单**：查询种子单号 `ORD20260609001`，经 MCP 返回结构化信息话术；次轮 sticky 无 Router LLM。
3. **知识**：对种子 FAQ 提问能命中并回答；无关问题不胡编关键承诺。
4. **退款**：发起 → 收到 `confirmation` → APPROVE 成功且幂等；REJECT/「取消」后可继续对话。
5. **转人工**：用户要求后收到排队提示，session=`QUEUED`；stub complete 后恢复 AI。
6. **可观测**：LangFuse（或本地日志）能看到一轮编排 Trace；密钥齐全时可看到 GenAI Prompt/Tool。
7. **部署**：`docker-compose` 或 `./start.sh all` 起依赖 + 订单服务 + 应用，按 README 可本地复现。

---

## 11. 演进路线

与完整方案对齐，按层加厚，不改骨架：

| 阶段 | 内容 |
|------|------|
| **MVP-1.0（本方案 / as-built）** | 五主流程 + 订单 MCP + Redis/PG/Milvus + CONFIRM + 假流式 + LangFuse 双轨 |
| **MVP-1.1** | PreSales 独立、ProductQuery；Complaint 进 Supervisor；坐席消息 SSE |
| **V1.6 对齐** | 完整 sticky 规则集、transition_log 落库、WAITING 细节、Handoff 技能组 |
| **V2.x** | 真流式、情感旁路、多租户策略、长期记忆精调、质检、真实订单中心、渠道适配 |

```
MVP-1.0 ──► 主流程跑通（组件已齐 + 订单 MCP + 双轨观测）
    │
    ├── + 售前/投诉 Agent 入 Supervisor
    ├── + 坐席工作台
    ├── + 真流式 / 情感
    └── + 多租户与运营能力
         └── 收敛到 smart-cs-architecture.md v2.1
```

---

## 附录 A. 与完整方案对照

| 完整方案 (v2.1) | MVP-1.0 as-built |
|-----------------|------------------|
| 7+ 领域 Agent | 5 个启用；PreSales/Complaint 映射 |
| 情感旁路、质检 | 不做 |
| 完整风控决策表 | 简化三档 Gate |
| Phase A/B SLA | 仅 Phase A |
| 坐席全 API | accept/complete stub |
| 长期记忆 + 重排 | LTM 已接线；重排/精调后置 |
| 订单能力 | 外置 `cs-order-service`（MCP Mock） |
| 可观测 | LangFuse ingestion + OTLP GenAI |
| 多租户运营 | default 租户 |

## 附录 B. 参考

- [smart-cs-architecture.md](./smart-cs-architecture.md) — 生产向完整设计
- [README.md](./README.md) — 模块说明与本地启动
- `scripts/mvp-smoke.sh` — 主流程冒烟
- `start.sh` / `docker-compose.yml` / `Dockerfile` — 部署入口
- AgentScope / WebFlux / LangFuse 文档同完整方案附录 C
