# 企业智能知识库与业务分析 Agent 平台 — 架构方案

> 版本 v0.2.12（2026-11-06）· **状态：M0–M6 已完成。** `mvn verify` 已在本机通过，Spike S1–S5 已实测并回填 §2.2，M1–M6 的验收标准已验证（§4.8–§4.11、§8.1、§9.1）。P1 起的内容仍是计划。
> v0.2.12 变更：新增 GitHub Actions CI（§8 的 P1 项之一）。
> v0.2.11 变更：M6 落地，新增 §8.1：后端和前端镜像、完整的 Docker Compose（`app` profile、健康检查、启动顺序）、Nginx（SSE 不缓冲、CSP）；README 改为面向读者的首页，各阶段的详细记录移到 `docs/DEVLOG.md`。
> v0.2.10 变更：Agent 追问相关的制度问题时不检索、直接编内容（用户实测发现），历史中改为保留工具调用记录，并新增引用核对；数字核对接受时长换算（§4.11）。
> v0.2.9 变更：M5 落地，新增 §9.1。包括：前端工程 `frontend/`；实现 §6.5 中的 `/admin/tasks`（DEAD 任务重新排队时复用同一行）和 `/admin/metrics/overview`（延迟只统计对话调用）；`TaskHandler` 新增 `onManualRetry` 回调。
> v0.2.8 变更：数字核对的结果写入 `chat_message.unverified_numbers`（Flyway V3）；组装对话历史时跳过含有未核实数字的轮次（§4.11）。
> v0.2.7 变更：M4 补充（§4.11）：AGENT 回答结束后做数字核对，`done` 事件新增 `unverifiedNumbers`；检索工具的结果改为与 KB_QA 相同的文本格式；更正 v0.2.6 中不可复现的“9/9”实测结果。
> v0.2.6 变更：M4 落地，新增 §4.11。包括：工具改为强类型的 `AgentTool` + 参数 record（同时用于 JSON Schema、校验和脱敏）；`AgentRequestContext`、`CitationRegistry`、`ToolRegistry` 放在 tool 包，避免 agent ↔ tool 循环依赖；开启 Spring AI 2.0.2 默认关闭的工具名解析回退，让越权调用走到服务端校验；用真实模型改进 Agent 提示词（2/6 → 9/9）。
> v0.2.5 变更：M3 落地，新增 §4.10。包括：用真实模型校准检索阈值（保持 0.5）；按实测改进引用提示词（合法引用从 6/15 提高到 15/15）；异常堆栈也要脱敏；SSE 响应显式声明 UTF-8；新增 `GET /conversations/{id}`；AGENT 会话在 M4 之前返回 400。
> v0.2.4 变更：M2 落地，新增 §4.9 记录实现细节和设计调整，包括：PDF 解析改为直接使用 PDFBox；`GET /knowledge-bases/{kbId}` 对 READ 级别开放；只有清空了文档的知识库才能删除。
> v0.2.3 变更：M1 落地，新增 §4.8 认证与会话的实现细节和 ADR-13、ADR-14；演示账号改为启动时初始化，不再使用 Flyway V3（§5）；分页参数 `page` 从 1 开始（§6.1）。
> v0.2.2 变更：回填 Spike 结论（§2.2）；版本锁定为 Boot 4.1.2 + Spring AI 2.0.2；通义 base-url 必须以 `/v1` 结尾；Embedding 批大小定为 10；确认 observation 内容记录的属性名（§4.6）；§4.4 的异常终止方案经 S4 验证，保持 L0。
> v0.2.1 变更：Embedding 批大小改为由 S3 实测确定（默认不超过 10 条）；M0 的 Spike 代码位于 `spikes/`。
> 定位：Java 后端工程实践作品。重点在分层设计、数据一致性、权限边界、安全、可观测性和可测试性。
> 技术路线：Java 21 单体 · Spring Boot 4.1 · Spring AI 2.0 · RAG · Tool Calling · PostgreSQL + pgvector · Redis · Vue 3 + TypeScript。

---

## 0. v0.2 修订记录

| # | 修订点 | 对应章节 |
|---|---|---|
| 1 | 文件存储与数据库无法原子提交：采用“先落文件、再提交数据库”的顺序，失败时补偿删除，并定时对账清理孤儿文件；删除文档改为异步任务 | §4.1、ADR-03 |
| 2 | 新增通用任务表 `async_task`：用 `FOR UPDATE SKIP LOCKED` 并发领取，配合租约和 fencing 校验；定时扫描是任务执行的唯一可靠来源，AFTER_COMMIT 事件只用于提前唤醒 | §4.2、ADR-04 |
| 3 | 入库拆为三段：短事务领取 → 无事务地调用外部服务计算 → 短事务提交。对话链路同样不在事务里调用 LLM；关闭 open-in-view | §4.3、ADR-05 |
| 4 | 明确三种角色对每个工具的权限矩阵（含区域数据范围）。每次工具执行都在服务端重新校验，身份取自服务端注入的上下文，不取自模型给的参数 | §4.4、ADR-07 |
| 5 | 引用测试拆成两层：“引用编号是否合法”（确定性，进 CI）和“回答事实是否有依据”（离线评测，需要真实模型） | §4.5、§7 |
| 6 | 日志在**写入时**就脱敏；默认不记录 Prompt、检索片段和完整回答；工具参数按字段分级脱敏；日志查询接口只对 ADMIN 开放，日志保留 30 天 | §4.6、ADR-08 |
| 7 | 工具调用改为优先使用 Spring AI 2.0 自带的 `ToolCallingAdvisor`；只有在明确条件触发时，才逐级引入自定义调度 | §4.4、ADR-06 |
| 8 | 缩小 MVP 范围：混合检索、Rerank、问题改写、Ollama、只读数据源、MinIO、指标看板都移到 MVP 之后 | §1.3、§8 |
| 9 | 前端定为 Vue 3 + TypeScript + Element Plus（企业后台风格）；默认模型为通义 Chat + 通义 Embedding；补充 SSE 事件协议和对话页的交互设计 | §2、§6.4、§9 |
| 10 | 业务场景保持“订单 + 异常事件”；测井异常诊断作为 MVP 之后的特色模块，只预留扩展点 | §10 |

---

## 1. 需求分析

### 1.1 角色

| 角色 | 定位 |
|---|---|
| `USER` 普通员工 | 在有权限的知识库里问答；上传文档需要 EDITOR 权限；只能管理自己的会话 |
| `ANALYST` 业务分析员 | 包含 USER 的能力，另外可以使用业务分析工具，但**只能访问分配给自己的区域数据** |
| `ADMIN` 管理员 | 用户管理、角色和区域分配、所有知识库、查看脱敏后的调用日志、处理失败任务 |

### 1.2 功能需求

| 编号 | 需求 | MVP 的做法 |
|---|---|---|
| F1 | PDF 入库 | 上传后由数据库任务表驱动异步执行：解析 → 切分 → Embedding → 写入 pgvector；提供状态查询、去重、失败重试和删除 |
| F2 | 知识库问答（KB_QA 模式） | 固定流程“先检索后生成”：Top-K 加相似度阈值，回答带 `[n]` 引用，服务端校验编号并对应到文档名和页码；检索不到就直接拒答；流式输出 |
| F3 | Agent 业务分析（AGENT 模式） | 通过 Spring AI 工具调用，使用 4 个受控 Java 工具：订单统计、异常统计、报告生成、知识库检索；工具调用过程实时推送给前端 |
| F4 | 会话与权限 | JWT 鉴权；三种角色；知识库成员权限；区域数据范围；会话增删改查和历史消息；多轮对话采用滑动窗口记忆 |
| F5 | 可观测性 | 记录 LLM 调用日志和工具调用日志（均脱敏）；traceId 贯穿整条链路；统一的异常分类；提供基础延迟和 Token 统计接口；Actuator 和 Prometheus 指标 |

### 1.3 MVP 范围

| 纳入 MVP | 移到 MVP 之后 |
|---|---|
| 认证、三种角色、区域数据范围、知识库成员权限 | SSO、组织架构树 |
| PDF 入库（含任务表、补偿和对账机制） | OCR、Word/Excel 格式、MinIO 对象存储 |
| KB_QA 模式流式问答 + 引用校验 | 混合检索（BM25）、Rerank、多轮问题改写 |
| AGENT 模式工具调用 + 过程可视化 | 自定义规划型 Agent、写操作类工具、需要人工审批的工具 |
| 报告生成（由 Java 根据数据确定性生成 Markdown） | 报告中由 LLM 生成的分析段落、PDF 导出 |
| 会话管理、历史记录、中途停止生成 | 会话分享、导出 |
| 调用日志（脱敏）、ADMIN 日志查询、基础统计接口 | 指标可视化看板、Zipkin 链路追踪 |
| 核心单元测试、集成测试、权限测试、引用合法性测试 | 离线的事实依据评测（P1 阶段） |
| Docker Compose 一键部署、README | Ollama 离线方案、只读数据库账号、CI 流水线（P1 阶段） |
| — | 测井异常诊断模块（P2 阶段） |

### 1.4 非功能需求

- **安全**：模型不能执行任意 SQL；密钥只从环境变量读取；每个资源都做归属校验；工具在服务端二次鉴权；日志脱敏；前端不直接接触任何大模型 API。
- **一致性与可靠性**：文件与数据库最终一致，没有孤儿文件也没有悬空记录；入库任务做到“至少执行一次”，配合幂等写入，重启后自动恢复。
- **资源占用**：不在数据库事务中调用外部 API；SSE 长连接期间不占用数据库连接。
- **可测试性**：`mvn verify` 只依赖 Docker，不需要 API Key，靠确定性的 Fake 模型完成测试。

---

## 2. 技术选型与兼容性

| 组件 | 选型 | 说明 |
|---|---|---|
| JDK | Java 21 | 使用虚拟线程执行 I/O 密集的入库任务 |
| Spring Boot | 4.1.2（S1 锁定） | Spring AI 2.0.2 即基于 Boot 4.1.2 构建 |
| Spring AI | 2.0.2（S1 锁定，通过 `spring-ai-bom` 管理） | 2.0.0 于 2026-06 GA；工具循环由 `ToolCallingAdvisor` 在 Advisor 链中执行。**不得低于 2.0.2**：2.0.0 在通义下流式工具调用会失败（§2.2 S2） |
| 模型接入 | `spring-ai-starter-model-openai` + 通义 DashScope OpenAI 兼容模式 | 2.0 的 OpenAI 模块基于 OpenAI 官方 Java SDK（OkHttp）；base-url 为 `https://dashscope.aliyuncs.com/compatible-mode/v1`。默认 Chat 模型 `qwen-plus`、Embedding 模型 `text-embedding-v3`（1024 维），都可以通过环境变量替换 |
| PostgreSQL | 17，镜像 `pgvector/pgvector:pg17` | pgvector 0.8.x，支持 HNSW 迭代扫描 |
| Redis | 7.x | 用于 Refresh Token、登出黑名单、限流；**不用于任务调度** |
| 持久层 | Spring Data JPA（Hibernate 7）+ `JdbcClient` | 普通 CRUD 用 JPA；向量检索、任务领取、统计 SQL 用 JdbcClient |
| 数据库迁移 | Flyway | |
| 安全 | Spring Security 7 + `oauth2-resource-server`（JWT） | |
| PDF 解析 | PDFBox 3（`PDFTextStripper`） | 按页读取；没有使用 `spring-ai-pdf-document-reader`，原因见 §4.9 |
| 测试 | JUnit 5、Mockito、AssertJ、Testcontainers、Spring Security Test、ArchUnit | |
| 前端 | Vue 3 + TypeScript + Vite + Element Plus + Pinia + Vue Router + Axios；`markdown-it` + `DOMPurify` + `highlight.js`；`@microsoft/fetch-event-source` | |
| 部署 | Docker Compose v2：postgres、redis、backend、frontend（Nginx） | |

### 2.1 已知风险

1. Spring Boot 4 默认使用 **Jackson 3**（包名 `tools.jackson`）。JSONB 映射和自定义序列化要统一使用 Boot 自动配置的 Mapper。
2. 网上大量 Spring AI 示例基于 1.x，2.0 的工具调用和 Advisor API 有变化（例如 `ToolCallAdvisor` 改名为 `ToolCallingAdvisor`）。实现时以 2.0 官方文档为准。
3. Embedding 维度固定写在表结构中。启动时校验“配置的维度 = 表的列维度”，不一致就拒绝启动（fail-fast）。
4. Spring AI 2.0 的 OpenAI 模块改为基于 OpenAI 官方 Java SDK，1.x 的配置写法不能照搬：base-url 必须包含 `/v1`；部分 1.x 属性（如 `chat.options.stream-usage`）已不存在，并且会被**静默忽略**。新增配置时以 2.0 jar 中的 `spring-configuration-metadata.json` 为准（M0 的核对方法与结论见 §2.2）。

### 2.2 M0 Spike 验证结论（2026-11-04 实测）

验证代码在 `spikes/`（独立 Maven 工程），运行方式见 `spikes/README.md`。实测环境：Windows 10、JDK 21.0.4、Docker Desktop 29.9、通义 DashScope 兼容模式（`qwen-plus`、`text-embedding-v3`）。逐项观察记录由测试写入 `spikes/target/spike-report.md`（构建产物，不入库），关键数据摘录在下表和后面的说明里。

| 编号 | 验证内容 | 结论 | 选定方案 |
|---|---|---|---|
| S1 | 锁定 Boot 4.1.x / Spring AI 2.0.x 的最新补丁版本，并检查依赖树没有冲突 | ✅ 两条线的最新补丁分别是 Boot **4.1.2**、Spring AI **2.0.2**（2.0.2 基于 Boot 4.1.2 构建，OpenAI SDK 为 4.80.0）。依赖树只有一处版本仲裁：OpenAI SDK 以 runtime 范围依赖 victools jsonschema 4.38.0，被 Spring AI 依赖的 5.0.0 覆盖；我们不使用 SDK 自带的结构化输出辅助方法，S2–S4 全部通过，可以接受 | 主工程和 `spikes/` 都锁定 Boot 4.1.2 + Spring AI 2.0.2；不采用 4.2.0-RC1 / 2.1.0-RC1 等预发布版本 |
| S2 | 通义兼容模式下 `ChatClient.stream()` 能否完成工具调用；流式响应末尾能否拿到 usage | ✅（需 ≥ 2.0.2）`call()` 和 `stream()` 都能拿到 usage（实测 14/72 与 15/165，流式在最后一个分片）。**流式 + 工具调用在 2.0.0 下失败，在 2.0.2 下正常**（工具调用 1 次，回答使用了工具结果）。根因见下方说明 | 不启用退路；版本下限写进 pom 注释，M4 增加分片回放测试防止回归。原定退路（工具轮次改用 `call()`，只对最终回答流式输出）保留为回归时的预案 |
| S3 | Embedding 的模型名、维度参数；一次请求最多提交多少条 | ✅ OpenAI Embedding 客户端可以直接调用 `text-embedding-v3`；`dimensions` 参数确实生效（配置 1024 和 512 时，返回向量长度和 `EmbeddingModel.dimensions()` 都与配置一致）。❌ 批量上限为 **10 条**：提交 12 条返回 400 `batch size is invalid, it should not be larger than 10` | 不需要 DashScope 原生适配器；**启用批量退路**：入库 Worker 自行切分批次，批大小 `app.embedding.batch-size` 默认 10（通义 v3 的上限；更换 Embedding 模型时按新模型的上限调整） |
| S4 | `ToolContext` 在 stream 模式下能否传到工具；工具在哪个线程执行；工具异常是转成消息还是中断调用 | ✅ `call()` 和 `stream()` 下 `ToolContext` 都完整传到工具。`call()` 时工具在调用方线程执行，`stream()` 时在 Reactor 的 `boundedElastic` 线程执行。异常分两条路径：`@Tool` 方法**内部**抛出的异常会转成消息交给模型，循环继续；在 `ToolCallback` **包装层**抛出的普通 `RuntimeException` 会终止整轮（`call()` 原样抛给调用方，`stream()` 以 `onError` 结束），被包装的工具不会执行 | 保持 L0，不需要 L1：`AgentAbortException` 由 `GuardedToolCallback` 在调用被包装工具之前直接抛出（细节见 §4.4.2） |
| S5 | MVC 返回 `Flux<ServerSentEvent<?>>` 与 Spring Security 异步分发是否兼容；客户端断开后上游能否被取消 | ✅ 认证后返回 200，5 个事件完整收到（异步分发没有被安全过滤链拒绝）；未认证返回 401；客户端断开后，上游 Flux 在 10 秒内被取消。流内（`parallel-1` 线程）`SecurityContextHolder` 读不到用户 | 不启用 `SseEmitter` 退路。身份在返回 Flux 之前从请求线程取出，显式传给后续流程（与 ADR-07、§6.4 一致） |

**S2 的三个问题与根因**（升级 Spring AI 时要逐条复查）：

1. **base-url 约定变了。** 2.0 的 OpenAI 模块基于 OpenAI 官方 Java SDK（`com.openai:openai-java`，HTTP 客户端是 OkHttp）。配置的 base-url 原样交给 SDK，SDK 只在后面拼接 `/chat/completions`、`/embeddings`（默认地址本身就是 `https://api.openai.com/v1`）。所以通义地址必须写成 `https://dashscope.aliyuncs.com/compatible-mode/v1`。照搬 1.x 的写法（不带 `/v1`）时，所有请求都返回 `404: Unknown`。
2. **1.x 的部分属性已不存在。** 例如 `spring.ai.openai.chat.options.stream-usage`，它在 2.0 里会被静默忽略。2.0 在流式请求且没有配置 `stream-options` 时，默认发送 `stream_options.include_usage=true`，所以不需要额外配置就能拿到 usage。
3. **2.0.0 下流式工具调用失败**（`NoSuchElementException`，位于 `OpenAiChatModel$ChunkMerger`）。通义在工具调用的续传分片里返回空字符串 `"id": ""`，而 OpenAI 的做法是省略这个字段。2.0.0 的分片合并逻辑只要看到 `id` 字段就当成一个新的工具调用，于是续传分片变成了没有函数名的“新调用”。通义的原始分片如下：

   ```
   {"index":0,"id":"call_5739…","type":"function","function":{"name":"countOrders","arguments":""}}
   {"index":0,"id":"","type":"function","function":{"arguments":"{\"region\": \""}}
   {"index":0,"id":"","type":"function","function":{"arguments":"EAST\"}"}}
   ```

   同样的请求在 2.0.2 下正常。**回归保护**：M4 增加一个不需要真实 Key 的集成测试，用本地 Mock 服务器回放这种通义风格的分片，断言流式工具调用正常（见 §7）。以后升级 Spring AI 时，这个测试会第一时间暴露回归；如果真的回归，就启用上表中的原定退路。

**S4 的补充观察**：如果 `@Tool` 方法声明了 `ToolContext` 参数，而调用方没有传 `toolContext`，框架会抛出 `IllegalArgumentException`（`ToolContext is required by the method as an argument`）并终止整轮，工具方法不会执行。这与 `GuardedToolCallback` 的 fail-closed 要求一致：`AgentChatService` 每次都必须注入 `toolContext`。

**M0 期间遇到的环境问题**（不属于 Spike，但会影响 `docker compose up`）：Windows 上 Hyper-V / WinNAT 会动态保留一些端口段（`netsh interface ipv4 show excludedportrange protocol=tcp` 可以查看）。本机的 6379 落在保留段 6336–6435 内，Redis 容器因此无法绑定端口，一直停在 `Created` 状态。解决办法是在 `.env` 中把 `REDIS_PORT` 改为动态端口范围之外的值（本机改为 16379），README 中有排查说明。

---

## 3. 系统模块划分

### 3.1 总体结构

```
          ┌─────── Vue3 + TS 前端（Element Plus 后台风格，Nginx 托管 + 反向代理） ───────┐
                              │ REST (JSON)        │ SSE (POST + fetch stream)
┌────────────────────────────── Spring Boot 单体 ─────────────────────────────────────┐
│ Controller   Auth │ User │ KnowledgeBase/Document │ Conversation/Chat │ Report │ Admin │
├──────────────────────────────────────────────────────────────────────────────────────┤
│ Service      KbPermissionService  DocumentService  RetrievalService                 │
│              ChatService（KB_QA）  AgentChatService（AGENT）  ConversationService    │
├───────────── Agent 层 ─────────────────────┬────────── Tool 层 ──────────────────────┤
│ ChatClient + ToolCallingAdvisor（框架自带） │ GuardedToolCallback（服务端二次校验）    │
│ AgentRequestContext（经 ToolContext 传递）  │ OrderStatsTool AnomalyStatsTool          │
│ PromptTemplates / CitationRegistry          │ ReportTool KbSearchTool                  │
├────────────── Task 层 ─────────────────────┴──────────────────────────────────────────┤
│ AsyncTaskScanner（定时扫描 + SKIP LOCKED 领取） → IngestionWorker / FileDeletionWorker │
│ StorageReconciler（孤儿文件对账）                                                      │
├──────────────────────────────────────────────────────────────────────────────────────┤
│ Repository   JPA Repositories │ ChunkVectorRepository │ AsyncTaskRepository │ BizStatsRepository │
├──────────────────────────────────────────────────────────────────────────────────────┤
│ 横切   ApiResponse/错误码/全局异常 · TraceIdFilter · RateLimiter · SensitiveDataMasker │
│        LlmCallRecorder · ToolAuditRecorder · StorageService(本地实现)                   │
└──────────────────────────────────────────────────────────────────────────────────────┘
      PostgreSQL + pgvector          Redis           通义 Chat / Embedding API（外部服务）
```

### 3.2 包结构（按功能分包）

```
com.example.kbagent
├── common/         ApiResponse、ErrorCode、GlobalExceptionHandler、TraceIdFilter、分页
├── config/         Security、线程池、ChatClient、Redis、调度
├── security/       JWT 签发与校验、Refresh Token、登出黑名单、当前用户 Actor
├── user/           用户、角色、区域数据范围
├── knowledge/      知识库、成员、KbPermissionService
├── storage/        StorageService 接口、LocalFileStorage、StorageReconciler
├── document/       上传、文档状态、切分器、入库 Worker
├── task/           AsyncTask 实体、领取、租约、重试、扫描器
├── retrieval/      ChunkVectorRepository、RetrievalService、检索调试接口
├── chat/           会话与消息、KB_QA 流程、SSE 事件、CitationParser
├── agent/          AgentChatService、AgentRequestContext、ToolRegistry、CitationRegistry
├── tool/           @Tool 业务工具、GuardedToolCallback、ToolPolicy（权限矩阵）
├── business/       订单和异常事件的只读统计仓储（固定的参数化 SQL）
├── report/         报告生成与下载
└── observability/  LlmCallRecorder、ToolAuditRecorder、SensitiveDataMasker、日志查询、保留期清理
```

**用 ArchUnit 强制的约束**：
- Controller 只能依赖 Service。
- `tool` 包不能依赖 `JdbcTemplate`、`JdbcClient` 或 `EntityManager`，必须通过 `business` 仓储访问数据。
- 依赖 `ChatClient` 或 `EmbeddingModel` 的类，其方法不能标注 `@Transactional`（见 ADR-05）。

### 3.3 各模块职责

| 模块 | 职责 | 关键约束 |
|---|---|---|
| security / user | Access Token 有效期 15 分钟，Refresh Token 7 天并在 Redis 中轮换；登出时把 jti 加入黑名单；每次请求从数据库重新加载用户状态（短 TTL 缓存），已停用的账号会立即失效（实现见 §4.8） | 角色和区域范围以服务端数据库为准，Token 只用来确认身份；依赖方向只能是 security → user（ArchUnit 约束） |
| knowledge | 知识库增删改查、成员管理（READER/EDITOR）；计算当前用户可访问的知识库集合 | 接口鉴权和检索过滤共用同一个方法 |
| storage | `StorageService` 提供 `writeTemp / promote / delete / exists / list` 操作；对账任务 | 删除操作是幂等的：文件不存在也视为成功 |
| document | 校验上传（≤20MB、文件头 `%PDF`）；计算 SHA-256 去重；上传和删除流程（§4.1）；切分器 | |
| task | 通用异步任务：领取、续租、fencing 校验、指数退避重试、重试耗尽后转为 DEAD | 任务执行以数据库为准，Redis 不参与调度 |
| retrieval | 把问题向量化后，在用户可访问的知识库内取 Top-K 并按阈值过滤 | 只返回状态为 READY 的文档中的 chunk |
| chat | 会话和消息；KB_QA 流程；SSE 推送；引用解析；消息状态机 | LLM 调用前后各用一个短事务 |
| agent | 组装 ChatClient 请求：按角色暴露工具、注入 `AgentRequestContext`；把工具事件转成 SSE | 优先使用框架自带的工具循环 |
| tool | 4 个工具；`GuardedToolCallback` 统一负责鉴权、参数校验、数据范围、调用预算、审计和事件推送 | 工具全部只读，唯一的写操作是写入 report 表 |
| business | 固定的参数化聚合 SQL | 模型只能填写枚举和日期 |
| report | 根据工具查询到的数据确定性生成 Markdown | 报告里的数字全部来自 SQL 结果 |
| observability | 在写入时脱敏、记录日志；ADMIN 查询接口；30 天保留期清理；Micrometer 指标 | 日志写入失败不影响业务 |

---

## 4. 关键设计与技术难点

### 4.1 文件存储与数据库的一致性（修订点 1）

**问题**：本地文件系统无法加入数据库事务。无论先写哪一边，进程都可能在两步之间崩溃。

**策略**：先写文件、后提交数据库；任何一步失败都立即补偿；同时定时对账作为兜底。系统只保证一个不变式：**数据库里的记录是权威的，没有对应记录的文件就是垃圾，可以安全删除。**

**上传流程**：
1. **（不开事务）** 把文件流式写入 `tmp/{uuid}.part`，写的同时计算 SHA-256 和大小，并校验文件头和大小上限。不合法就删除临时文件并返回 400。
2. **（不开事务）** 查询同一知识库下是否已有相同 hash 且状态不是 DELETING 的文档。如果有，删除临时文件，直接返回已有文档（HTTP 200）。
3. **（不开事务）** 用 `Files.move(ATOMIC_MOVE)` 把文件移到 `docs/{kbId}/{uuid}.pdf`，临时目录和正式目录在同一个卷上。
4. **短事务**：插入 `document`（PENDING）和 `async_task`（INGEST_DOCUMENT），然后提交。
   - 如果遇到唯一约束冲突（两个人并发上传同一个文件），**删除第 3 步的文件**，查询已有文档并返回。
   - 如果是其他异常，**删除第 3 步的文件**，然后抛出异常。
5. 提交之后发布唤醒事件（见 §4.2）。

**崩溃窗口**：如果进程在第 3 步和第 4 步之间崩溃，会留下一个孤儿文件。由 `StorageReconciler` 处理，每小时执行一次：
- 删除 `tmp/` 中修改时间超过 1 小时的 `.part` 文件。
- 删除 `docs/` 中修改时间超过 1 小时、且数据库里没有任何 `document.storage_path` 指向的文件。设置 1 小时宽限期，是为了避免误删正在上传中的文件。
- 对反方向的不一致（状态为 READY 但文件丢失）只记录告警和指标，不自动修改数据。因为 chunk 仍然可以检索，文件只在重新处理时才需要。

**删除流程**（同样改为异步任务）：
1. **短事务**：锁定文档行，把状态改为 `DELETING`；删除该文档的全部 chunk（检索立即看不到）；把正在进行的 INGEST 任务标记为 `CANCELLED`；插入一个 `DELETE_FILE` 任务。
2. `FileDeletionWorker` 执行任务：删除文件（文件不存在也算成功），然后删除 document 记录。失败按任务机制重试。

**扩展性**：`StorageService` 是一个接口，以后换成 MinIO 时，上传和删除流程以及对账逻辑都不需要改。

### 4.2 异步任务：可恢复、可并发领取（修订点 2）

**问题**：`@TransactionalEventListener(AFTER_COMMIT)` 发出的事件只存在内存里。进程崩溃、线程池满或部署重启时，事件会丢失，任务就永远不会执行。

**设计**：
- 用 `async_task` 表持久化所有异步任务。**定时扫描器是任务执行的唯一可靠来源**，AFTER_COMMIT 事件只是一个“立即扫描一次”的提示，用来降低延迟。即使事件丢失，最多 5 秒后任务也会被扫描到。
- **领取**（短事务，多个实例同时运行也安全）：

```sql
UPDATE async_task t
   SET status = 'RUNNING', attempt = attempt + 1,
       locked_by = :workerId, lease_until = now() + interval '5 minutes', updated_at = now()
 WHERE t.id IN (
   SELECT id FROM async_task
    WHERE (status = 'PENDING' AND next_run_at <= now())
       OR (status = 'RUNNING' AND lease_until < now())      -- 租约过期，说明执行者已死
    ORDER BY next_run_at
    LIMIT :freeSlots
    FOR UPDATE SKIP LOCKED)
RETURNING *;
```

- **并发控制**：
  - 每个实例的执行线程池容量固定为 2，只领取空闲槽位数量的任务。
  - `SKIP LOCKED` 保证同一个任务不会被两个实例同时领取。
  - 通过部分唯一索引保证同一文档同时最多只有一个活跃的 INGEST 任务。
- **租约与 fencing**：
  - 执行过程中，每处理完一批 Embedding 就续租一次：`UPDATE ... SET lease_until = now()+5min WHERE id=? AND locked_by=? AND attempt=?`。影响行数为 0 说明租约已经被别人接管，当前执行立即放弃。
  - 最终提交时同样带上 `locked_by` 和 `attempt` 条件。这样即使旧的执行者“复活”，也无法覆盖新执行者的结果。
- **重试**：
  - 可重试的错误（网络错误、429、5xx）：状态回到 `PENDING`，`next_run_at = now() + min(30s × 2^attempt, 10min)`。
  - 不可重试的错误（PDF 损坏、没有可提取的文本、chunk 数量超过上限）：直接转为 `FAILED`。
  - 超过 `max_attempts`（默认 5 次）：转为 `DEAD`，对应文档标记为 FAILED，ADMIN 可以手动重试。
- **为什么不用 Redis 分布式锁或 MQ**：任务状态本来就在数据库里，用行锁加租约就能同时满足持久化、互斥和恢复的需求，不需要再引入一个组件，也就避免了“锁在 Redis、状态在数据库”这种双写不一致。

### 4.3 外部计算与短事务提交分离（修订点 3）

**入库任务分三段执行**：

| 阶段 | 事务 | 内容 |
|---|---|---|
| ① 领取 | 短事务 | 领取任务（§4.2）；文档状态改为 PROCESSING |
| ② 计算 | **不开事务、不持有数据库连接** | 读取文件 → PDFBox 解析 → 切分 → 分批调用 Embedding API（批大小 `app.embedding.batch-size` 默认 10 条：S3 实测通义 v3 单次最多 10 条，超出返回 400）→ 结果暂存在内存（单文档 chunk 上限 3000 个，1024 维向量约 12MB）。每批之间用一个小事务续租 |
| ③ 提交 | 短事务 | fencing 校验 → 确认文档状态仍为 PROCESSING（期间被删除的话就丢弃结果）→ 删除该文档的旧 chunk → JDBC 批量插入新 chunk → 文档改为 READY，记录 `embedding_model` → 任务改为 SUCCEEDED |

**对话链路同样遵循这个原则**：
- 短事务 1：保存用户消息，同时插入一条状态为 `STREAMING` 的助手消息占位。
- 流式调用 LLM，期间不开事务。
- 短事务 2：写入最终内容、引用和工具步骤，把状态改为 `OK`、`FAILED` 或 `INTERRUPTED`。
- 如果进程在流式输出过程中崩溃，消息会一直停在 `STREAMING`。扫描器会把超过 10 分钟仍为 `STREAMING` 的消息改为 `INTERRUPTED`。

**配套措施**：
- 设置 `spring.jpa.open-in-view=false`，避免 SSE 长连接一直占用数据库连接。
- Hikari 连接池大小根据“短事务”的假设来配置。
- 用 ArchUnit 规则禁止在调用 AI 的类里使用 `@Transactional`。

### 4.4 工具调用：框架机制优先，服务端逐次鉴权（修订点 4、7）

#### 4.4.1 使用 Spring AI 2.0 自带的机制

AGENT 模式的请求组装如下：

```
chatClient.prompt()
   .system(agentSystemPrompt)
   .messages(最近 N 轮历史)
   .user(问题)
   .toolCallbacks(toolRegistry.callbacksFor(actor))   // 按角色只暴露有权限的工具
   .toolContext(Map.of("ctx", agentRequestContext))    // 服务端注入，不会发送给模型
   .stream()
```

- 工具循环由 `DefaultChatClient` 自动注册的 `ToolCallingAdvisor` 执行，`call()` 和 `stream()` 都支持。它会累计每一轮的 Token 用量。S2 已在通义兼容模式下实测流式工具调用，前提是 Spring AI ≥ 2.0.2（§2.2）。
- 工具的执行线程（S4 实测）：`call()` 时在调用方线程，`stream()` 时在 Reactor 的 `boundedElastic` 线程。所以工具和 Service 都不能依赖任何 ThreadLocal（包括 `SecurityContextHolder` 和 MDC 中的 traceId），需要的信息一律从 `AgentRequestContext` 获取。
- 工具定义使用 `@Tool` 注解，参数使用强类型 record 和枚举。每个工具的 `ToolCallback` 都被 `GuardedToolCallback` 包装（装饰器模式）。
- **不使用 `MessageChatMemoryAdvisor`**：历史消息从我们自己的 `chat_message` 表读取最近 N 轮，手动拼进 Prompt。原因是消息表需要保存引用、工具步骤和归属信息，而且多数 ChatMemory 仓储实现不支持工具消息。
- `AgentRequestContext` 包含：`userId`、`role`、`regionScope`、`accessibleKbIds`、`conversationId`、`traceId`、`ToolBudget`（本轮剩余调用次数）、`AgentEventSink`（推送 SSE 事件）、`CitationRegistry`（本轮引用编号表）。

#### 4.4.2 什么情况下才引入自定义调度（按顺序逐级升级）

| 级别 | 方式 | 什么时候用 |
|---|---|---|
| L0（默认） | `ToolCallingAdvisor` + `GuardedToolCallback` 实现调用预算和事件推送 | MVP |
| L1 | 子类化 `ToolCallingAdvisor`，覆写 `doBeforeCall` / `doAfterCall` 等钩子来统计轮次或强制收尾（仍然属于框架机制） | 需要一个严格的轮次上限（S4 已验证包装层异常能中断循环，所以不是因为这个原因） |
| L2 | `AdvisorParams.toolCallingAdvisorAutoRegister(false)`，自己用 `ToolCallingManager` 驱动循环 | 需要逐步规划或人工审批；或者流式工具调用再次不可用（S2 在 2.0.2 下可用，MVP 不做） |

**调用预算**：
- 每轮对话最多执行 6 次工具调用。超出后，工具不再执行，只返回 `BUDGET_EXCEEDED`，提示模型基于已有结果作答。
- 如果累计尝试达到 10 次，就抛出 `AgentAbortException` 终止本轮，并向前端推送 SSE `error` 事件。S4 已验证这样可以终止循环，但有两个前提：
  - 异常必须在 `GuardedToolCallback` 里、**调用被包装工具之前**抛出。`DefaultToolCallingManager` 只捕获 `ToolExecutionException`，而 `@Tool` 方法内部抛出的异常会先被框架包装成 `ToolExecutionException`，再转成消息交给模型，循环不会停。
  - `AgentAbortException` 只继承 `RuntimeException`，不能继承 `ToolExecutionException`。

  终止后，`call()` 会把异常原样抛给调用方，`stream()` 会以 `onError` 结束。`AgentChatService` 负责把它转成 SSE `error` 事件，并把消息标记为 FAILED。

#### 4.4.3 权限矩阵

| 工具 | USER | ANALYST | ADMIN | 服务端每次执行时的校验 |
|---|---|---|---|---|
| `searchKnowledgeBase(query, kbId?)` | ✅ | ✅ | ✅ | `kbId` 必须在 `accessibleKbIds` 内；不传 `kbId` 时检索全部可访问的知识库 |
| `queryOrderStats(from, to, region?, groupBy)` | ❌ | ✅（限本人区域） | ✅（全部区域） | 校验角色；校验 `region` 在用户的区域范围内，不传就按用户的区域范围过滤；时间跨度 ≤ 366 天；结果 ≤ 100 行 |
| `countAnomalies(from, to, eventType?, minSeverity?, region?, groupBy)` | ❌ | ✅（限本人区域） | ✅（全部区域） | 同上 |
| `generateReport(reportType, from, to, region?)` | ❌ | ✅（限本人区域，报告归本人） | ✅ | 同上；另外按用户限流，每分钟 5 次 |
| 查看他人报告 | ❌ | ❌ | ✅（通过 REST，不通过工具） | — |

**`GuardedToolCallback` 的固定执行顺序**：
1. 从 `ToolContext` 取出 `AgentRequestContext`，取不到就拒绝执行（fail-closed）。
2. 用 `ToolPolicy` 按角色判断能否使用该工具，**不依赖“只暴露了有权限的工具”这一点**。暴露过滤只是为了减少无效尝试，不是安全边界。
3. 反序列化参数并做 Jakarta Validation 校验。
4. 收敛数据范围：模型请求的区域与用户的区域范围求交集，结果为空就拒绝。
5. 扣减调用预算。
6. 推送 `tool START` 事件，然后执行。业务 Service 的方法签名显式接收 `Actor` 参数，并在内部再做一次范围校验，**不依赖 ThreadLocal 中的 SecurityContext**，因为流式模式下工具在 Reactor 的 `boundedElastic` 线程上执行（S4 实测）。
7. 记录审计日志：参数脱敏，结果写 `ALLOWED`、`DENIED` 或 `ERROR`。推送 `tool END` 事件。

**拒绝时的处理**：返回结构化的错误结果给模型，例如 `{"error":"FORBIDDEN_SCOPE","message":"无权访问 NORTH 区域数据"}`，让模型向用户解释原因。不抛 500。

**身份来源**：`AgentRequestContext` 由服务端根据 JWT 和数据库中的用户记录构建，存放在 `ToolContext` 里，不会发送给模型。因此模型无论输出什么参数，都改变不了“我是谁”和“我能看什么”。

#### 4.4.4 Agent 模式下的引用

`searchKnowledgeBase` 可能在一轮对话中被调用多次。所以引用编号由服务端的 `CitationRegistry` 全局分配：第二次检索的结果从 `[4]` 开始编号。最终解析引用时，以这张编号表为准。

### 4.5 引用准确性（修订点 5）

把“引用是否正确”拆成两个独立的问题：

| 层次 | 问题 | 性质 | 怎么保证 | 怎么测试 |
|---|---|---|---|---|
| A. 引用合法性 | 回答里的 `[n]` 是否对应本轮真实检索到的 chunk？映射到的文档名和页码对不对？ | 确定性的后处理，可以 100% 保证 | `CitationParser` 支持 `[1]`、`[1][2]`、`[1,2]`、全角 `【1】` 等格式；越界编号会被剔除并记入 `invalidIndices`；重复编号去重；代码块内的 `[n]` 不解析；没有任何引用时进入 FALLBACK 模式 | 单元测试和集成测试（Fake 模型按脚本输出），**进 CI** |
| B. 事实依据 | 被引用的 chunk 是否真的支撑了这句话？回答里有没有引用之外的编造？ | 概率性的，取决于检索质量和模型 | Prompt 约束 + 检索阈值 + 检索不到时拒答 | 离线评测，需要真实模型（P1 阶段），**不作为 CI 的门禁** |

**层次 A 的计划测试用例**：越界编号、编号 0、负数、混合格式、重复引用、完全没有引用；多次检索后的全局编号；chunk 映射到正确的 `document_id`、页码和片段。

**层次 B 的离线评测设计**（P1 阶段实施，结果写入 README 前必须真实运行过）：
- 构造约 30 条评测数据，每条包含：问题、**期望支撑 chunk 的集合**、关键事实（数字、日期、专有名词）。另外加入约 5 条知识库里没有答案的问题。
- 计算以下指标：
  - 引用合法率（预期 100%，用来回归验证层次 A）
  - 引用精确率：被引用的 chunk 中，属于期望支撑集合的比例
  - 引用召回率
  - 关键事实支撑率：回答中出现的关键事实，能否在它所引用的 chunk 原文中找到（字符串或数字匹配，确定性）
  - 拒答正确率
- 可以额外用 LLM-as-judge 打分，但只作为参考，不作为门禁。

**Agent 模式下的数字**：报告和统计结果中的数字由 Java 代码生成。集成测试会断言“报告中的数字等于直接执行 SQL 得到的结果”，这是层次 A 在数据类回答上的对应保证。

### 4.6 敏感信息脱敏与日志访问控制（修订点 6）

**原则**：在**写入时**脱敏，而不是在读取时。即使数据库被导出，日志里也不包含原始敏感数据。

| 数据 | 默认策略 |
|---|---|
| System Prompt、检索到的 chunk、完整的模型回答 | **不记录**。只记录长度、finish reason 和 SHA-256 摘要 |
| 用户问题 | 由 `APP_LLM_LOG_CONTENT=NONE\|MASKED_PREVIEW` 控制，默认 `MASKED_PREVIEW`：脱敏后截取前 200 字 |
| 工具参数 | 按字段分级：参数 record 的字段用 `@Sensitive(MASK\|HASH\|DROP)` 标注。枚举和日期不需要脱敏；客户名这类字段做 MASK |
| 工具结果 | 只记录摘要（行数、合计值），不记录明细行 |
| 异常信息 | 截断到 1000 字后再脱敏；去掉上游响应中可能回显的请求头 |
| 应用日志（Logback） | 用相同规则做脱敏转换 |
| Spring AI 自带的 observation | 不记录 Prompt、回答和工具内容。S1 已按 2.0.2 的配置元数据确认属性名，默认值都是 `false`：`spring.ai.chat.client.observations.log-prompt` / `log-completion`、`spring.ai.chat.observations.log-prompt` / `log-completion` / `include-error-logging`、`spring.ai.tools.observations.include-content`。M3 在 `application.yml` 中显式写成 `false`，并用测试断言，防止以后默认值变化 |

**`SensitiveDataMasker` 的识别规则**（每条都有单元测试）：
- 手机号（`1[3-9]\d{9}`）
- 18 位身份证号
- 邮箱
- 银行卡号（13–19 位数字，并通过 Luhn 校验以减少误判）
- API Key（`sk-` 前缀等）
- `Bearer` Token 和 JWT（`eyJ...`）

**访问控制**：
- `/api/v1/admin/logs/**` 只允许 ADMIN 访问（方法级安全注解），并且返回的就是已脱敏的数据，**系统中没有任何接口能返回原始内容**。
- 用户在自己的会话里看到的是完整的问答内容（那本来就是他自己的数据），但工具步骤中的参数同样是脱敏后的。
- 定时任务每天删除 30 天之前的 `llm_call_log` 和 `tool_call_log`。
- 返回给客户端的错误只包含错误码、通用描述和 traceId，不包含堆栈和上游原始报错。

### 4.7 中文切分、带过滤的向量检索、模型调用稳定性（沿用 v0.1）

- **切分器** `SentenceAwareTextSplitter`：优先在 `\n\n`、`。！？；`、`\n` 处断开；目标长度约 500 字，相邻 chunk 重叠 80 字；不跨页；保留页码。是纯函数，单元测试最完整。
- **自建 `doc_chunk` 表**：权限过滤就是普通的 SQL 列（`kb_id`）条件，文档删除时 chunk 级联删除。为了避免过滤后凑不满 Top-K，在检索事务内执行 `SET LOCAL hnsw.iterative_scan = relaxed_order`，并调整 `hnsw.ef_search`。
- **限流**：Redis 令牌桶，每个用户每分钟 20 次对话，超出返回 429。令牌桶已在 M1 实现（`RedisRateLimiter`，见 §4.8），M3 直接复用。
- **重试和超时**：2.0 的 OpenAI 模块由 OpenAI SDK 自己负责超时和重试，配置项是 `spring.ai.openai.timeout` 和 `spring.ai.openai.max-retries`（可以按 chat / embedding 分别覆盖）。流式输出开始之后不再重试，失败就推送 `error` 事件，并把消息标记为 FAILED。

### 4.8 认证与会话（M1 实现）

**Access Token**（HS256 JWT，15 分钟）：只携带 `iss`、`sub`（用户 ID）、`jti`、`iat`、`exp`，**不携带角色**。每个请求的处理过程：
1. `NimbusJwtDecoder` 校验签名、有效期（允许 60 秒时钟偏差）和签发者；`ActiveTokenValidator` 要求带 `exp` 和 `jti`（框架的时间戳校验在缺少 `exp` 时会放行），并且 `jti` 不在登出黑名单里。
2. `ActorAuthenticationConverter` 按 `sub` 从 `ActorCache` 取出 `Actor`（id、用户名、角色、区域范围）。账号已停用或不存在时返回 401 `ACCOUNT_DISABLED`。
3. Controller 通过 `@AuthenticationPrincipal Actor` 拿到当前用户。

**用户状态缓存**：`ActorCache` 是 Caffeine 本地缓存，TTL 30 秒。管理员修改用户后发布 `UserAccountChangedEvent`，缓存在**事务提交之后**清除对应条目。如果在提交前清除，并发请求可能把尚未提交的旧状态重新读进缓存。所以停用账号、修改角色都会立即生效，不需要等 Token 过期，也不需要等缓存过期（`AuthFlowIT` 验证）。单实例部署用本地缓存就够了；以后多实例部署时，需要把清除操作广播到所有实例（例如 Redis pub/sub）。

**Refresh Token**（7 天，每次刷新重新计时）：32 字节随机串（Base64URL），Redis 中只保存它的 SHA-256。

| 键 | 值 | 用途 |
|---|---|---|
| `auth:rt:{hash}` | `userId:familyId` | 当前有效的令牌 |
| `auth:rt:used:{hash}` | `userId:familyId` | 已轮换掉的旧令牌，保留到它原本的过期时间，用于重用检测 |
| `auth:rt:family:{familyId}` | 当前有效令牌的 hash | 一次登录产生一个令牌族，用于整族撤销 |
| `auth:bl:{jti}` | `1` | 登出黑名单，TTL = Token 剩余有效期 + 60 秒时钟偏差 |
| `rl:{key}` | 令牌桶状态 | 限流 |

- **轮换**：刷新时用一个 Lua 脚本原子地完成“删除当前令牌并留下已使用标记”，然后在同一令牌族内签发新令牌。刷新时直接查库确认账号仍然有效，不走缓存。
- **重用检测**：已轮换掉的旧令牌再次出现，说明它可能被窃取。这时撤销整个令牌族，攻击者和合法用户都需要重新登录。已签发的 Access Token 最多再有效 15 分钟（可以接受的代价）。**前端必须串行刷新**（同一时刻只发一个刷新请求，其他请求等待结果），否则多个标签页并发刷新会被误判为重用（§9）。
- **登出**：当前 Access Token 的 `jti` 进入黑名单；请求体带了 `refreshToken` 时，撤销它所在的令牌族（只能撤销属于自己的令牌）。

**登录**：
- 按客户端 IP 限流，默认每分钟 10 次（令牌桶由 Lua 脚本原子执行，时间取 Redis 服务器的 `TIME`），超出返回 429 和 `Retry-After`。M6 部署在 Nginx 之后时，要配置 `server.forward-headers-strategy`，才能拿到真实的客户端 IP。
- 用户名不区分大小写，保存为小写。
- 用户不存在时也做一次 BCrypt 比对，使响应时间与“口令错误”一致，防止枚举用户名。
- 口令超过 72 字节直接判为错误（BCrypt 只处理前 72 字节，超长输入会抛异常）。
- 只有口令正确时，才会提示“账号已停用”。

**其他约定**：
- 认证失败的响应由 `RestAuthenticationEntryPoint` / `RestAccessDeniedHandler` 输出统一响应体。`TraceIdFilter` 排在安全过滤链之前，所以 401/403 同样带 traceId。
- 方法级鉴权（`@PreAuthorize`）失败时，异常从 Controller 抛出，由 `GlobalExceptionHandler` 映射为 403。如果不显式处理，它会被兜底的 `Exception` 处理器变成 500。
- 管理员不能停用自己，也不能修改自己的角色（409），保证系统里始终至少有一个可用的 ADMIN。
- 只有 ANALYST 有区域范围，而且至少要有一个区域；角色改为 USER/ADMIN 时自动清空区域。这条不变式由 `SysUser` 实体保证，所有修改路径都要经过它。
- `/actuator/health` 公开；`/actuator/prometheus` 等其余端点只对 ADMIN 开放。
- 模块依赖方向：`security → user`，`user` 不感知 JWT 和 Redis 中的令牌。用 ArchUnit 约束，同时约束顶层模块之间没有循环依赖。

### 4.9 知识库与入库（M2 实现）

§4.1–§4.3 的设计已按原样落地。下面记录实现时补充的细节，以及和原设计不一致的地方。

**模块与依赖方向**：`document → knowledge / storage / task`。`task` 是通用模块，不认识文档：业务方实现 `TaskHandler`，任务模块负责领取、续租、fencing、重试和状态流转。`storage` 通过端口 `StoredFileReferences` 查询“哪些文件被引用”，由文档模块实现，所以 `storage` 不反向依赖 `document`。这些方向都由 ArchUnit 的“无循环依赖”规则约束。

**知识库权限**：`KbPermissionService` 是唯一入口。访问级别分为 NONE / READ / EDIT / MANAGE：ADMIN 和所有者是 MANAGE，EDITOR 成员是 EDIT，READER 成员和公开知识库是 READ。没有任何访问权限时返回 404，有权查看但级别不够时返回 403。文档接口先按文档找到所属知识库，再用同一套规则判断。

**任务执行（§4.2 的补充）**：
- 执行者是虚拟线程，并发数由信号量限制（每个实例 2 个）。定时扫描和“提交后唤醒”共用一把扫描锁；扫描进行中收到的唤醒会合并为“本轮结束后再扫一轮”，不会漏掉刚提交的任务。
- 租约过期、而且已经是最后一次尝试的任务，由扫描器直接转为 DEAD，不再领取。执行者在处理过程中反复死亡（例如内存溢出）时，任务走不到正常的失败分支，需要靠这一步兜底，否则会被无限次重新领取。
- 结果分类：处理器在提交事务中调用 `complete()` 表示成功；`NonRetryableTaskException` 转为 FAILED；其他异常按指数退避重试，最后一次仍失败则转为 DEAD；`LeaseLostException` 表示租约已被接管或任务已被取消，直接放弃。
- 错误信息分两层：`async_task.last_error` 记录完整的内部错误（包括上游原因），只用于排查；文档的 `error_message` 和接口返回的只有错误码和面向用户的说明（§4.6）。
- 删除流程和入库提交都**先锁文档行、再改任务表**，加锁顺序一致，避免两者互相等待造成死锁。

**入库（§4.3 的补充）**：
- PDF 解析直接使用 PDFBox 3 的 `PDFTextStripper` 按页提取，没有用 `spring-ai-pdf-document-reader`。我们有自己的 chunk 模型（ADR-02），只需要“页码 + 文本”，这样依赖更少、行为可控。PDF 已加密返回 `PDF_ENCRYPTED`，文件损坏返回 `CORRUPT_PDF`，扫描件没有文本返回 `NO_EXTRACTABLE_TEXT`，都不重试。
- 切分器每个片段的上限是“chunk 大小减去重叠”，所以加上 80 字重叠后也不会超过 500 字。重叠部分如果包含句子边界，就从边界之后开始，避免新 chunk 以半句话开头。切分前会去掉 NUL 字符，因为 PostgreSQL 的 TEXT 不能包含它。
- Embedding 错误分类：Spring AI 2.0 直接抛出 OpenAI SDK 的异常，没有经过 spring-ai-retry 包装。所以由 `EmbeddingClient` 自己分类：408/409/429/5xx 和网络错误可以重试，其他错误不重试；返回的向量数量和维度也会逐一校验。
- 启动时校验 `app.embedding.dimensions` 与 `doc_chunk.embedding` 的列维度（读取 `pg_attribute.atttypmod`），不一致就拒绝启动。

**接口上的调整**：
- `GET /knowledge-bases/{kbId}` 对 READ 级别开放（原设计是所有者和 ADMIN），这样知识库详情页对成员可用。修改和删除知识库仍然需要 MANAGE。
- 新增 `GET /knowledge-bases/{kbId}/members`（MANAGE），供成员管理页面使用。
- 知识库中还有文档（包括正在删除的）时不能删除，返回 409。这样避免在同一个请求里级联删除大量文件。
- 上传遇到同一知识库中内容相同的文档时，无论那个文档是什么状态（包括 FAILED），都返回 200 和已有文档。用户可以对它执行“重新处理”。

**验收结果（2026-11-04）**：§7 中入库、补偿、任务恢复、事务边界、删除竞争的集成测试全部通过（见 [DEVLOG](DEVLOG.md) 的 M2 验收清单）。真实 PDF 用的是一份 54 页的英文文档（1.5MB），调用真实的通义 `text-embedding-v3`，每批 10 条：

- 上传接口 0.13 秒返回 202。
- 9.3 秒后变为 READY，第一次尝试就成功，没有重试。
- 得到 186 个 chunk，覆盖 53 页（另 1 页没有可提取的文本）。
- chunk 长度在 82 到 500 字之间，平均 396 字。

### 4.10 知识库问答与会话（M3 实现）

§4.3（对话链路的事务边界）、§4.5（层次 A）、§4.6、§6.3、§6.4 的设计已落地。下面记录实现细节、实测数据和与原设计不一致的地方。

**模块与依赖方向**：`chat → retrieval → knowledge`，`chat / retrieval → observability`。`retrieval` 只负责“问题向量化 + 带权限过滤的向量检索”，问答流程、提示词、引用和消息状态都在 `chat`。`observability`（脱敏、调用日志）不依赖任何业务模块。

**一轮问答的执行过程**（`KbQaService`，本类不开事务）：
1. 在请求线程上同步完成：限流（Redis 令牌桶，键 `chat:{userId}`）→ 短事务 1（`ChatTransactions.begin`：校验会话归属和知识库权限、读取最近 3 轮成功的历史、保存问题和 STREAMING 占位、用第一个问题生成标题）。这些步骤失败时什么都没有写入，接口返回普通的 4xx。
2. 返回冷的 Flux，订阅后：推送 `meta` → 在 `boundedElastic` 上检索 → 推送 `retrieval` → 没有达到阈值的片段时直接拒答（不调用模型，`citation_mode = NONE`），否则流式调用模型、逐段推送 `delta`。
3. 模型输出结束后解析引用，写入调用日志，再执行短事务 2，最后推送 `citations` 和 `done`。所以客户端收到 `done` 时数据库已经是最终状态。
4. 三种结束方式（完成、失败、订阅被取消）用一个 `AtomicBoolean` 保证只有一个能执行短事务 2；短事务 2 先 `SELECT … FOR UPDATE` 锁住消息，只处理仍为 STREAMING 的消息，与超时扫描器互斥。
5. 订阅被取消（客户端断开、停止生成）时，上游的模型请求随之取消，已生成的部分以 INTERRUPTED 保存（`error_code = CLIENT_CLOSED`）。这一步放到 `boundedElastic` 上执行，不占用触发取消的容器线程。超过 10 分钟仍为 STREAMING 的消息由扫描器改为 INTERRUPTED（`STREAM_TIMEOUT`），这个时间大于 `spring.mvc.async.request-timeout`（5 分钟），不会误伤正在输出的回答。

**SSE 的细节**：
- 心跳用 `publish` 把事件流分成两路：一路原样输出，另一路决定 `Flux.interval` 心跳何时停止（事件流结束，心跳也结束）。心跳除了防止代理断开空闲连接，也让服务端在等待首字期间就能发现客户端已断开。
- 响应头显式写成 `text/event-stream;charset=UTF-8`。SSE 规范要求按 UTF-8 解码，但不带 charset 时，MockMvc 和 IntelliJ HTTP Client 会按 ISO-8859-1 显示，中文变成乱码。
- 开始输出之前的错误（404、429 等）按普通 JSON 返回。为此全局异常处理显式设置 `Content-Type: application/json`：如果按请求头 `Accept: text/event-stream` 协商，找不到能写 JSON 的转换器，响应体会丢失。
- 开始输出之后的失败不抛异常，统一转换为 `error` 事件。

**检索阈值的校准**（text-embedding-v3，2026-11-05 实测，`/knowledge-bases/{kbId}/search`）：用示例差旅制度（中文，3 页）和 M2 验收用的 54 页英文文档，各准备了相关和无关的问题，比较 top-1 相似度：

| 问题类型 | 数量 | top-1 相似度 |
|---|---|---|
| 相关问题（12 个中 top-1 都是答案所在的页） | 12 | 0.579 – 0.767 |
| 与文档领域无关（天气、编程、地理、年会；在英文文档上问报销） | 9 | 0.347 – 0.488 |
| 同一领域但文档没有覆盖（在差旅制度上问年假、绩效、入职） | 3 | 0.55 – 0.605 |

阈值保持 0.5（`APP_RETRIEVAL_MIN_SCORE`）：所有相关问题都能通过；领域外的问题全部直接拒答，不调用模型。同一领域但没有覆盖的问题会越过阈值，这类问题靠提示词处理：模型回答“根据现有资料无法回答”，实测的年假问题就是这样。阈值只能判断“像不像”，判断不了“能不能回答”。如果把阈值提高到 0.6，会把相关问题误拒（最低 0.579）。样本量还小，P1 的离线评测会扩大样本（§4.5 层次 B）。

**引用（§4.5 层次 A 的实现）**：
- `CitationParser` 是纯函数，支持 `[1]`、`[1][2]`、`[1,2]`、`[1，2]`、`[2、3]`、`【1】`、`［1］`；编号 0、负数、越界的进入 `invalidIndices`；去重并按编号排序；围栏代码块和行内代码里的 `[n]` 不解析，没闭合的代码块一直算到结尾（输出被中断时会出现）。
- 引用模式：至少有一个合法引用时为 NORMAL，`citations` 是被引用的片段；检索到了资料但回答没有合法引用时为 FALLBACK，`citations` 是本轮检索到的全部片段，前端作为“参考资料”展示；拒答时为 NONE。
- 历史回答带给模型之前会去掉 `[n]`：它们指向当时那一轮的检索结果，和本轮的编号会混淆。

**提示词按实测修改**：第一版提示词在示例差旅制度上实测时，qwen-plus 经常把原文的“第九条”写成 `[9]`。解析器能识别出它越界（层次 A 保证不会映射到错误的文档），但这样的回答就没有可用的引用了。修改了两处：System Prompt 中加入正反例（“资料 [2] 的原文是第九条……应标注 [2]，而不是 [9]”）；在用户消息的末尾、问题之后，再重复一次引用要求，因为模型对提示词末尾的内容最敏感。用 5 个问题各问 3 次（含一个多轮追问）做对比：合法引用（NORMAL）从 **6/15** 提高到 **15/15**，答案内容都正确。同时规定“无法回答时不要标注编号”，否则模型会给拒答也标上引用。

**用量和耗时**：用量取流式响应最后一个分片（通义兼容模式下实测一直返回，S2）；没有返回时按“1.5 个字符约 1 个 token”估算，并标记 `estimated = true`。`done` 事件里的 `latencyMs` 和 `firstTokenMs` 从请求开始计算（用户感受到的时间）；`llm_call_log` 里的 `first_token_ms` 和 `latency_ms` 从模型调用开始计算（模型本身的性能）。

**脱敏（§4.6 的补充）**：
- 只对日志消息（`%m`）脱敏是不够的。集成测试发现：流式调用失败时，Spring AI 的 `MessageAggregator` 会以 ERROR 级别打印“Aggregation Error”和完整的异常堆栈，而上游报错可能回显请求内容。所以 `logback-spring.xml` 中也覆盖了 Spring Boot 的 `%wEx` / `%wex`，异常堆栈（包括各级 Caused by）同样经过 `SensitiveDataMasker`。
- 本项目自己打印上游异常时，只输出异常类型和（脱敏后的）消息，不打印堆栈。
- 用户问题在 `llm_call_log` 中只保存脱敏后的前 200 字和 SHA-256；System Prompt、检索片段和完整回答都不记录，只记录输出字数。
- Spring AI observation 的 6 个内容开关在 `application.yml` 中显式设为 false，`ApplicationIT` 断言的是框架实际绑定的配置对象。

**接口上的调整**：
- 新增 `GET /conversations/{id}`（本人），前端恢复会话时需要知道它绑定的知识库。
- AGENT 会话在 M4 之前不能创建，返回 400（原设计是所有角色都可以创建，M4 实现工具后恢复）。
- 消息的游标分页返回 `{items, nextBeforeId}`：`items` 按时间正序，`nextBeforeId` 为 null 表示没有更早的消息。
- 会话绑定的知识库被删除后 `kb_id` 变为 null（`ON DELETE SET NULL`），之后在用户有权访问的全部知识库中检索。只有清空了文档的知识库才能删除（§4.9），所以不会悄悄换掉有内容的资料来源。

**验收结果（2026-11-05）**：§8 的四项都已通过集成测试（见 [DEVLOG](DEVLOG.md) 的 M3 验收清单）。真实模型（qwen-plus + text-embedding-v3）的端到端检查：
- 首字时间（从请求开始算）：12 次调用中 11 次在 0.4–0.9 秒，1 次 1.7 秒。英文长回答（210–323 个 token）5.1–7.6 秒完成。
- 领域外的问题 0.13–0.23 秒拒答，没有 Chat 调用记录。
- 用户在第 5 个 delta 后断开，0.3 秒内消息变为 INTERRUPTED，保存了已生成的 140 个字。调用日志记录为 `CLIENT_CLOSED`，耗时 0.8 秒（完整回答需要约 6 秒），说明上游请求确实被中止了。

### 4.11 Agent 与工具（M4 实现）

§4.4 的设计已落地。工具循环使用 Spring AI 自带的 ToolCallingAdvisor（L0，没有升级到 L1/L2）。下面记录实现细节、框架行为和实测数据。

**模块与依赖方向**：`agent → chat / tool`，`tool → business / report / retrieval / observability`，`report → business`。

- chat 不依赖 agent。chat 定义了 `ChatModeHandler` 接口，KB_QA（chat 模块）和 AGENT（agent 模块）各自实现，`ChatService` 按会话模式分发。
- 一轮问答中与模式无关的部分抽到模板类 `ChatRound`，两种模式共用：首字时间、用量、累计输出、工具步骤、三种结束方式的互斥收尾、调用日志、错误转换。
- 与 §3.2 不同的地方：`AgentRequestContext`、`CitationRegistry`、`ToolRegistry` 放在 tool 包，而不是 agent 包。GuardedToolCallback 要读取上下文，ToolRegistry 要作为 Spring AI 的解析器，放在 agent 包会形成 agent ↔ tool 的循环依赖（ArchUnit 禁止）。

**工具定义**：每个工具实现 `AgentTool<A>`，参数是一个 record。同一个 record 有三个用途：
- 用 `JsonSchemaGenerator` 生成给模型的 JSON Schema。`@ToolParam` 标在 record 组件上，提供描述和是否必填。
- 做 Jakarta Validation。
- 用 `@Sensitive` 标注需要脱敏的字段。

这样只有一个来源。§4.4.1 原定使用 `@Tool` 注解方法，没有采用：GuardedToolCallback 必须在执行之前拿到强类型的参数，才能做校验和范围收敛，`@Tool` 方法的参数到执行时才由框架转换。

4 个工具：
- `searchKnowledgeBase`：检索结果按本轮的 `CitationRegistry` 全局编号，同一个片段再次出现时沿用原编号。
- `queryOrderStats`、`countAnomalies`：统计结果同时返回分组行和全部数据的合计。
- `generateReport`：唯一的写操作。

**GuardedToolCallback** 按 §4.4.3 的 7 步执行。另外有几点实现细节：
- 被拒绝（`FORBIDDEN_TOOL`、`FORBIDDEN_SCOPE`）、参数不合法（`INVALID_ARGUMENTS`）、超出预算（`BUDGET_EXCEEDED`）时，也推送一对 tool START / END 事件、写审计日志、记入消息的工具步骤，再把结构化的错误返回给模型。所以 SSE 和历史消息里能看到每一次尝试。
- 尝试次数达到上限时，先写一条审计（`error_code = AGENT_ABORTED`），再抛出 `AgentAbortException`。它继承自 BusinessException，只是 RuntimeException，不是 ToolExecutionException（S4）。`ChatRound` 会沿着异常链找到它，转成 `error` 事件，并把消息标记为 FAILED。
- 业务 Service 抛出的 `ScopeDeniedException`（二次校验）记为 DENIED。限流和其他业务异常记为“已放行但执行失败”。未知异常返回 `TOOL_ERROR`，不把内部信息交给模型。
- 参数按大小写不敏感解析枚举（模型偶尔会写 `east`）。参数解析失败时，错误详情可能回显参数，先脱敏再截断。

**Spring AI 2.0.2 的框架行为**（实现时发现，升级时要复查）：
1. **工具名解析回退默认关闭**（`spring.ai.tools.resolution.fallback.enabled=false`）。模型按名字调用没有暴露给它的工具时，`DefaultToolCallingManager` 不会去问 ToolCallbackResolver，而是直接抛出 `IllegalStateException: No ToolCallback found`，整轮失败。越权调用因此走不到服务端校验，也不会留下审计记录。我们开启了回退，并让 `ToolRegistry` 替换默认的解析器：它只返回受保护的回调（不存在的工具名返回一个只会报错的回调）。这样越权调用会被 GuardedToolCallback 按角色拒绝，记为 DENIED。AgentIT 覆盖了这个场景（USER 的请求只暴露检索工具，模型仍然调用 `countAnomalies`）。
2. **ChatClient 以 `ChatModel.getOptions()` 为基础合并请求选项**（`getDefaultOptions()` 已废弃）。只有返回 `ToolCallingChatOptions` 时，toolCallbacks 和 toolContext 才会进入请求，否则工具被静默忽略。OpenAiChatModel 满足这个条件；测试用的 FakeChatModel 必须同样实现。
3. 框架新增了工具调用次数上限：默认单个工具 40 次、合计 150 次（`spring.ai.tools.limits.*`），远大于我们的预算，不会先触发。
4. 定位第 1 点时曾怀疑是自动配置的先后顺序导致 ToolCallingAdvisor 没有使用容器里的 ToolCallingManager。打印运行时的装配后确认不是，所以没有保留那个额外的配置。

**业务数据**：
- `V2__seed_business_data.sql` 用 `hashtext` 确定性地生成 6000 条订单和 1500 条异常事件，覆盖迁移当天之前的 180 天，区域分布刻意不均匀（EAST 最多）。
- 统计 SQL 是固定的：分组表达式和可选条件都是枚举里的常量片段，取值全部参数绑定。按业务时区（`app.business.zone`）划分自然日。时间跨度不超过 366 天，分组不超过 100 行，超出时截断并标记。
- `DataScope` 是区域范围的唯一规则：ADMIN 全部区域，ANALYST 自己的区域，USER 没有。工具的第 4 步和 `BizStatsService` 内部的二次校验都用它，所以绕过工具直接调用 Service 也拿不到范围之外的数据。

**报告**：
- `ReportMarkdown` 是纯函数，报告里的每个数字都来自统计结果（ADR-10）。报告包括总览，以及按区域、品类、时间的表格（31 天以内按天，否则按月）。`report.params` 记录收敛之后实际生效的区域。
- 生成报告按用户限流，每分钟 5 次。
- 只有本人和 ADMIN 能查看和下载，其他人返回 404。
- 下载的文件名包含中文标题，按 RFC 6266 用 `filename*` 编码。

**提示词（按实测修改）**：Agent 的 System Prompt 写明当前用户的角色、数据范围和今天的日期（业务时区），让模型少做无效尝试。真正的权限仍由服务端校验。用 qwen-plus 实测时发现三个问题：
- 不检索就说知识库里没有；
- 把原文的“第九条”写成 `[9]`；
- 给业务数据也标上 `[1]`、`[2]`。

做了三处修改：
- System Prompt 要求制度类问题必须先检索，并给出引用编号的正反例；
- 检索工具的结果里再提醒一次引用规则（工具结果离回答最近）；
- 业务数据工具的结果里写明区域代码的中文名，并说明这些数据不需要标编号。

同一组问题（2 个制度类问题和 1 个业务数据问题）的合格次数从 **2/6** 提高到 **9/9**。

**更正（复测）**：当天晚些时候用同一提示词复测，只有 **6/9**，第二个制度类问题 3 次都把“第八条”写成 `[8]`，说明上面的 9/9 不可复现。同时复测了 KB_QA，15/15 仍然成立。两者的区别在于检索结果的形式：
- KB_QA 的资料是纯文本（“[n] 《文件》第 p 页”），引用要求紧挨着放在最后；
- 当时 Agent 的检索工具返回 JSON，提醒放在 `note` 字段里，而 `Map.of` 的字段顺序不固定，提醒可能排在结果前面。

把检索工具的结果改成与 KB_QA 相同的文本格式后（`AgentTool.Output` 的结果是字符串时原样交给模型），两组复测共 **18/18**。

**数字核对**：用户手工测试时出现过一次编造。分析员追问“那华北上个月有多少异常事件？”，模型没有调用任何工具，直接回答“127 起”，真实是 56 起。这不是数据泄露，因为华北的数据根本没有被访问，但它违反了“业务数字只能来自工具结果”。处理分两层：
- **提示词**：明确“本轮没有调用工具就不能给出任何业务数字”“无权查看的数据只说明无权查看，不给任何数字”。复测追问 3 次，都没有给出数字。
- **服务端核对**（`NumberGrounding`，和引用合法性一样是确定性的后处理）：AGENT 回答结束后，回答中的每个数字都要能在本轮的工具参数、返回结果、摘要或用户问题中找到出处。
  - 比对时容忍：千分位、四舍五入到 0～2 位小数、“万”“亿”换算、中文数字（资料原文的“六小时”）、PDF 换行拆开的数字。
  - 带 % 的数字如果等于某两个出处数字相除的结果，也算有出处。分析类回答里占比很常见，每次都提示会让用户习惯性忽略，真正编造的数字反而不显眼。增长率等其他推算仍会被标出。
  - 不核对的数字：日期、月份、引用标记、列表序号、“第 N”、标识符。
  - 找不到出处的数字会在回答末尾追加一段提示（作为 delta 推送并写入消息，不认识新字段的客户端也能看到），并列在 `done` 事件的 `unverifiedNumbers` 中。本轮没有调用任何工具时，提示更严厉（“没有查询依据，请不要采用”）。
  - 核对结果写入 `chat_message.unverified_numbers`（V3）。组装对话历史时，跳过回答中含有未核实数字的轮次。原因是用户复用旧会话测试时，模型照着历史里自己编造的“127 起”原样又说了一遍。V3 之前的回答没有核对记录，仍会进入历史。
  - 它只回答“有没有出处”，不判断计算对不对。KB_QA 暂不做核对：资料原文的数字写法更多样，误标的代价大于收益。

**追问时不检索（M5 之后的补充，2026-11-06）**：用户手工测试时发现。管理员在 Agent 会话里先问 Java 开发规范（正常检索），再问“报销补助”。模型说了“我将为您检索……”，却没有调用任何工具，编了一份《员工费用报销管理制度（2025版）》，还标了不存在的引用 [3]。数字核对标出了 7 个数字，但编造的条文本身没有被拦住。

- **复现**（qwen-plus，管理员）：
  - 先问“出差住宿标准是多少？”再问“报销补助”：5 次全部不检索，编造内容并标上引用。
  - 没有历史，直接问“报销补助”：5/5 正常检索。
  - 原因：历史里只有“问题 + 回答”。模型看到的是“助手凭记忆直接给出了制度内容”，追问相关主题时就照着做。
- **只改提示词无效，而且更差**：
  - 改法是要求“每个新问题都要重新检索”和“调用工具之前不要先说计划”。
  - 结果：可复现的场景仍是 0/5；原本正常的场景反而退化（先问 Java 再问报销 1/3；没有历史时 0/3，模型改成反问）。
  - 结论：没有采用。
- **采用的做法**：
  1. **历史里保留工具调用**。组装 AGENT 历史时，按 `chat_message.tool_steps` 还原当时的 assistant `tool_calls` 和对应的 tool 消息：参数是脱敏后的，结果只有摘要（“历史记录，只保留了结果摘要”），id 加了轮次前缀，避免和本轮的 `c1` 重复。
     - 复测：可复现的场景三批共 15/15 先检索；先问 Java 再问报销 9/9。
     - 业务数据追问（问完订单再问“那异常事件呢”）和越权追问（华东分析员追问华北）都没有回归。
  2. **引用核对**（确定性的后处理）：本轮没有检索知识库，回答里却标了引用编号时，在回答末尾追加提示。推送和保存方式与数字核对相同。提示里写的是“引用编号（1、3）”，而不是带方括号的写法，避免再被当成引用解析。
  3. 没有调用工具却标了引用编号的轮次，不进入后续历史，与含未核实数字的轮次相同。
- **数字核对补充：时长换算**。工具返回“平均处理 378.06 分钟”，模型写成“约 6.3 小时”“约 6 小时 18 分钟”，曾被误标为未核实（复测 3/3 误标）。
  - 现在出处中以分钟为单位的数值（字段名以 `Minutes` 结尾，或文字“N 分钟”），可以与回答中的“X 小时”“X 小时 Y 分钟”按回答的精度换算比对。
  - 只和分钟数值比较，不会因为碰巧接近某个订单数，而放过编造的时长。
  - 复测 3/3 不再误标。
- **仍然存在**：没有历史时直接问很短的制度问题，模型偶尔会反问具体想了解什么（3 次中 1 次），但没有编造。

**验收结果（2026-11-05）**：§8 的五项都已通过（见 [DEVLOG](DEVLOG.md) 的 M4 验收清单）。

- **通义流式分片回放测试**：本地模拟服务器回放 S2 记录的分片（续传分片带 `"id": ""`），走真实的 OpenAiChatModel。Spring AI 2.0.2 下通过。切到 2.0.0 运行同一个测试会失败（`NoSuchElementException`，与 S2 记录的现象一致），说明它确实能拦住这个回归。
- **真实模型端到端**（qwen-plus）：
  - 华东分析员问品类订单：正确收敛为 `region=EAST`，回答中的数字与工具结果一致。
  - 分析员问华北：模型根据提示词直接说明无权查看，没有发起调用。
  - 生成报告：报告数字与统计结果一致。
  - 管理员对比各区域：调用了两次统计工具。
  - 普通员工问订单量：直接说明没有权限。
  - 工具执行耗时 8–362 毫秒；一轮带工具调用的回答（含模型的两次调用）4.0–10.4 秒。

---

## 5. 数据库表设计

所有 ID 使用 `BIGINT GENERATED ALWAYS AS IDENTITY`，时间使用 `TIMESTAMPTZ`。Flyway 脚本规划：
- `V1__init.sql`：建表
- `V2__seed_business_data.sql`：业务种子数据
- ~~`V3__seed_demo_users.sql`~~：演示账号改由 `DemoUserInitializer` 在启动时创建（`app.demo.seed-users=true`，local profile 默认开启）。原因：初始密码来自环境变量，放进 Flyway 占位符会让明文进入 SQL，修改密码后还会改变迁移脚本的校验和；在 Java 里创建还能复用应用的 `PasswordEncoder`。只在账号不存在时创建，不覆盖已有密码。

与 v0.1 相比的变化用 `-- v0.2` 标注。

```sql
CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE sys_user (
  id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  username      VARCHAR(64)  NOT NULL UNIQUE,
  password_hash VARCHAR(100) NOT NULL,
  display_name  VARCHAR(64)  NOT NULL,
  role          VARCHAR(16)  NOT NULL CHECK (role IN ('USER','ANALYST','ADMIN')),
  region_scope  VARCHAR(16)[] NOT NULL DEFAULT '{}',   -- v0.2：ANALYST 的数据范围；ADMIN 忽略此字段（可访问全部）
  enabled       BOOLEAN      NOT NULL DEFAULT TRUE,
  created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
  updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE knowledge_base (
  id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  name        VARCHAR(128) NOT NULL,
  description VARCHAR(512),
  owner_id    BIGINT NOT NULL REFERENCES sys_user(id),
  visibility  VARCHAR(16) NOT NULL DEFAULT 'PRIVATE' CHECK (visibility IN ('PRIVATE','PUBLIC')),
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE kb_member (
  kb_id      BIGINT NOT NULL REFERENCES knowledge_base(id) ON DELETE CASCADE,
  user_id    BIGINT NOT NULL REFERENCES sys_user(id),
  permission VARCHAR(16) NOT NULL CHECK (permission IN ('READER','EDITOR')),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (kb_id, user_id)
);

CREATE TABLE document (
  id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  kb_id           BIGINT NOT NULL REFERENCES knowledge_base(id),
  filename        VARCHAR(255) NOT NULL,
  content_hash    CHAR(64)     NOT NULL,
  storage_path    VARCHAR(512) NOT NULL UNIQUE,       -- v0.2：对账时按路径反查
  size_bytes      BIGINT       NOT NULL,
  page_count      INT,
  chunk_count     INT          NOT NULL DEFAULT 0,
  status          VARCHAR(16)  NOT NULL
                  CHECK (status IN ('PENDING','PROCESSING','READY','FAILED','DELETING')),  -- v0.2：新增 DELETING
  error_code      VARCHAR(64),                         -- v0.2：如 NO_EXTRACTABLE_TEXT / TOO_MANY_CHUNKS
  error_message   VARCHAR(1000),
  embedding_model VARCHAR(64),                         -- v0.2：为将来更换模型后重新向量化做准备
  uploaded_by     BIGINT NOT NULL REFERENCES sys_user(id),
  created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
-- v0.2：改为部分唯一索引。正在删除的文档不参与去重，用户可以立即重新上传同一文件
CREATE UNIQUE INDEX uk_document_kb_hash ON document(kb_id, content_hash) WHERE status <> 'DELETING';
CREATE INDEX idx_document_kb_status ON document(kb_id, status);

CREATE TABLE doc_chunk (
  id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  document_id BIGINT NOT NULL REFERENCES document(id) ON DELETE CASCADE,
  kb_id       BIGINT NOT NULL,
  chunk_index INT    NOT NULL,
  page_number INT    NOT NULL,
  content     TEXT   NOT NULL,
  char_count  INT    NOT NULL,
  embedding   vector(1024) NOT NULL,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (document_id, chunk_index)
);
CREATE INDEX idx_chunk_kb ON doc_chunk(kb_id);
CREATE INDEX idx_chunk_embedding ON doc_chunk USING hnsw (embedding vector_cosine_ops);

-- v0.2：通用异步任务表
CREATE TABLE async_task (
  id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  task_type    VARCHAR(32) NOT NULL CHECK (task_type IN ('INGEST_DOCUMENT','DELETE_FILE')),
  biz_id       BIGINT      NOT NULL,                  -- document_id
  payload      JSONB,                                 -- 如 DELETE_FILE 的 storage_path
  status       VARCHAR(16) NOT NULL
               CHECK (status IN ('PENDING','RUNNING','SUCCEEDED','FAILED','DEAD','CANCELLED')),
  attempt      INT         NOT NULL DEFAULT 0,
  max_attempts INT         NOT NULL DEFAULT 5,
  next_run_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  locked_by    VARCHAR(64),
  lease_until  TIMESTAMPTZ,
  last_error   VARCHAR(1000),
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_task_claim ON async_task(status, next_run_at);
CREATE UNIQUE INDEX uk_task_active ON async_task(task_type, biz_id) WHERE status IN ('PENDING','RUNNING');

CREATE TABLE conversation (
  id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  user_id    BIGINT NOT NULL REFERENCES sys_user(id),
  title      VARCHAR(128) NOT NULL DEFAULT '新会话',
  mode       VARCHAR(16)  NOT NULL CHECK (mode IN ('KB_QA','AGENT')),
  kb_id      BIGINT REFERENCES knowledge_base(id) ON DELETE SET NULL,
  deleted    BOOLEAN NOT NULL DEFAULT FALSE,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_conv_user ON conversation(user_id, updated_at DESC) WHERE deleted = FALSE;

CREATE TABLE chat_message (
  id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  conversation_id BIGINT NOT NULL REFERENCES conversation(id) ON DELETE CASCADE,
  role            VARCHAR(16) NOT NULL CHECK (role IN ('USER','ASSISTANT')),
  content         TEXT        NOT NULL DEFAULT '',
  citations       JSONB,          -- [{index, documentId, filename, page, snippet, score}]
  citation_mode   VARCHAR(16),    -- v0.2：NORMAL / FALLBACK / NONE
  tool_steps      JSONB,          -- [{callId, tool, argsMasked, decision, ok, latencyMs, summary}]
  -- unverified_numbers JSONB     -- M4 补充（V3）：AGENT 回答中找不到出处的数字；KB_QA 和 V3 之前的回答为 NULL
  status          VARCHAR(16) NOT NULL DEFAULT 'OK'
                  CHECK (status IN ('STREAMING','OK','FAILED','INTERRUPTED')),  -- v0.2
  error_code      VARCHAR(64),
  trace_id        VARCHAR(32),
  created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_msg_conv ON chat_message(conversation_id, id);
CREATE INDEX idx_msg_streaming ON chat_message(updated_at) WHERE status = 'STREAMING';

CREATE TABLE report (
  id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  user_id         BIGINT NOT NULL REFERENCES sys_user(id),
  conversation_id BIGINT REFERENCES conversation(id) ON DELETE SET NULL,
  report_type     VARCHAR(32) NOT NULL,
  title           VARCHAR(200) NOT NULL,
  params          JSONB NOT NULL,        -- 收敛之后实际生效的参数（含区域）
  content_md      TEXT  NOT NULL,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_report_user ON report(user_id, created_at DESC);

CREATE TABLE llm_call_log (
  id                    BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  trace_id              VARCHAR(32),
  user_id               BIGINT,
  conversation_id       BIGINT,
  call_type             VARCHAR(16) NOT NULL CHECK (call_type IN ('CHAT','EMBEDDING')),
  chat_mode             VARCHAR(16),             -- KB_QA / AGENT
  model                 VARCHAR(64) NOT NULL,
  streamed              BOOLEAN NOT NULL DEFAULT FALSE,
  prompt_tokens         INT,
  completion_tokens     INT,
  usage_estimated       BOOLEAN NOT NULL DEFAULT FALSE,  -- v0.2：流式拿不到 usage 时为估算值
  tool_call_count       INT NOT NULL DEFAULT 0,
  input_preview_masked  VARCHAR(256),            -- v0.2：脱敏后的预览，可配置为不记录
  input_sha256          CHAR(64),
  output_chars          INT,
  first_token_ms        INT,                     -- v0.2：首字延迟
  latency_ms            INT NOT NULL,
  success               BOOLEAN NOT NULL,
  error_code            VARCHAR(64),
  error_message_masked  VARCHAR(1000),
  created_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_llm_log_time ON llm_call_log(created_at);
CREATE INDEX idx_llm_log_trace ON llm_call_log(trace_id);

CREATE TABLE tool_call_log (
  id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  trace_id        VARCHAR(32),
  tool_call_id    VARCHAR(64),
  user_id         BIGINT NOT NULL,
  user_role       VARCHAR(16) NOT NULL,                       -- v0.2
  conversation_id BIGINT,
  tool_name       VARCHAR(64) NOT NULL,
  args_masked     JSONB,                                      -- v0.2：已脱敏
  effective_scope VARCHAR(16)[],                              -- v0.2：收敛后实际生效的区域
  decision        VARCHAR(16) NOT NULL CHECK (decision IN ('ALLOWED','DENIED','INVALID','BUDGET_EXCEEDED')),
  success         BOOLEAN NOT NULL,
  result_summary  VARCHAR(500),
  error_code      VARCHAR(64),
  latency_ms      INT NOT NULL,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_tool_log_time ON tool_call_log(created_at);
CREATE INDEX idx_tool_log_trace ON tool_call_log(trace_id);

-- 业务数据（演示用；区域枚举：EAST/NORTH/SOUTH/WEST/CENTRAL）
CREATE TABLE biz_order (
  id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  order_no   VARCHAR(32) NOT NULL UNIQUE,
  customer   VARCHAR(128) NOT NULL,
  region     VARCHAR(16) NOT NULL,
  category   VARCHAR(32) NOT NULL,
  amount     NUMERIC(12,2) NOT NULL,
  status     VARCHAR(16) NOT NULL,      -- CREATED/PAID/SHIPPED/CANCELLED/REFUNDED
  created_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_order_time_region ON biz_order(created_at, region);

CREATE TABLE biz_anomaly_event (
  id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  event_type    VARCHAR(32) NOT NULL,   -- PAYMENT_TIMEOUT/INVENTORY_MISMATCH/API_ERROR/LOGISTICS_DELAY
  severity      SMALLINT    NOT NULL CHECK (severity BETWEEN 1 AND 4),
  source_system VARCHAR(32) NOT NULL,
  region        VARCHAR(16) NOT NULL,
  description   VARCHAR(500),
  occurred_at   TIMESTAMPTZ NOT NULL,
  resolved_at   TIMESTAMPTZ
);
CREATE INDEX idx_anomaly_time_type ON biz_anomaly_event(occurred_at, event_type);
```

**说明**：Refresh Token 和登出黑名单保存在 Redis 里，键带 TTL，不建表。

---

## 6. REST / SSE API 设计

### 6.1 通用约定

- 统一前缀 `/api/v1`。
- 统一响应体 `{code, message, data, traceId}`。
- 响应头带 `X-Trace-Id`。
- 分页参数 `page`（从 1 开始）、`size`（1–100）；分页结果为 `{items, page, size, total}`。
- HTTP 状态码：400 参数错误 / 401 未认证 / 403 无权限 / 404 不存在（**访问不属于自己的资源也返回 404**，避免泄露资源是否存在）/ 409 冲突 / 429 限流（带 `Retry-After` 头）/ 502 上游模型错误。
- 401 细分错误码：`UNAUTHORIZED`（未登录、Token 无效/过期/已登出，不区分具体原因）、`BAD_CREDENTIALS`（用户名或密码错误，用户不存在时也返回它）、`ACCOUNT_DISABLED`（账号已停用，登录时只在口令正确时才会返回）、`REFRESH_TOKEN_INVALID`（前端收到后跳转登录页）。

### 6.2 认证、用户、知识库、文档

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| POST | `/auth/login` · `/auth/refresh` · `/auth/logout` | 公开 / 公开 / 登录 | 登录和刷新返回 `{tokenType, accessToken, expiresIn, refreshToken, refreshExpiresIn, user}`（时长单位为秒）；Refresh Token 轮换；登出时 jti 进入黑名单，请求体可带 `refreshToken` 一并撤销 |
| GET | `/users/me` | 登录 | 返回角色和区域范围（前端据此控制菜单显示，但**真正的权限由后端判断**） |
| POST / GET / PATCH | `/admin/users[/{id}]` | ADMIN | 创建用户、启用/停用、修改角色和 `regionScope` |
| POST / GET | `/knowledge-bases` | 登录 | 列表只返回有权限的知识库 |
| GET / PATCH / DELETE | `/knowledge-bases/{kbId}` | GET：READER；PATCH / DELETE：owner / ADMIN | 还有文档时不能删除（409）；返回值带 `myAccess`（当前用户的访问级别） |
| GET | `/knowledge-bases/{kbId}/members` | owner / ADMIN | 成员列表（M2 新增） |
| PUT / DELETE | `/knowledge-bases/{kbId}/members/{userId}` | owner / ADMIN | 请求体 `{permission: READER\|EDITOR}`；删除是幂等的 |
| POST | `/knowledge-bases/{kbId}/documents` | EDITOR | multipart 上传 → 202 `{documentId, status}`；重复文件 → 200 返回已有文档 |
| GET | `/knowledge-bases/{kbId}/documents` | READER | 可按状态过滤 |
| GET | `/documents/{docId}` | READER | 返回状态、错误码、任务尝试次数（供前端轮询） |
| POST | `/documents/{docId}/reprocess` | EDITOR | 已有活跃任务时返回 409 |
| DELETE | `/documents/{docId}` | EDITOR | 202，进入 DELETING 状态，异步删除文件 |
| GET | `/documents/{docId}/chunks` | READER | 分页查看切分结果，用于调试和验收 |
| POST | `/knowledge-bases/{kbId}/search` | READER | `{query, topK}` → 带分数的 chunk 列表，只检索不生成回答 |

### 6.3 会话与对话

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| POST | `/conversations` | 登录 | `{mode: KB_QA\|AGENT, kbId?, title?}`。所有角色都可以创建 AGENT 会话，**可用的工具由角色决定**（§4.4.3） |
| GET | `/conversations?keyword=&page=` | 本人 | 按更新时间倒序 |
| GET | `/conversations/{id}` | 本人 | 会话详情（M3 新增） |
| PATCH / DELETE | `/conversations/{id}` | 本人 | 重命名、软删除 |
| GET | `/conversations/{id}/messages?beforeId=&size=` | 本人 | 游标分页，返回 `{items, nextBeforeId}`，items 按时间正序；包含引用（M4 起包含工具步骤） |
| POST | `/conversations/{id}/messages/stream` | 本人 | **主入口**，返回 `text/event-stream`，请求体 `{content}` |
| POST | `/conversations/{id}/messages` | 本人 | 非流式版本，返回完整结果，供脚本和集成测试使用 |

**停止生成**：前端调用 `AbortController.abort()` 断开连接，服务端感知断开后取消上游请求，把已生成的部分内容保存下来，并把消息标记为 `INTERRUPTED`。MVP 是单实例部署，不需要单独的取消接口。

### 6.4 SSE 事件协议

```
event: meta       data: {"messageId":123,"userMessageId":122,"traceId":"..."}
event: retrieval  data: {"candidates":[{"index":1,"documentId":9,"filename":"差旅制度.pdf","page":3,"score":0.82}]}   # KB_QA
event: tool       data: {"callId":"c1","name":"countAnomalies","phase":"START","args":{...已脱敏}}
event: tool       data: {"callId":"c1","phase":"END","decision":"ALLOWED","ok":true,"latencyMs":41,"summary":"共 37 条，按类型分 4 组"}
event: delta      data: {"text":"根据制度第三章"}
event: citations  data: {"mode":"NORMAL","citations":[...],"invalidIndices":[9]}
event: done       data: {"status":"OK","usage":{"prompt":1830,"completion":412,"estimated":false},"latencyMs":5210,"firstTokenMs":820,"unverifiedNumbers":[]}   # unverifiedNumbers 只有 AGENT 模式有（§4.11 数字核对）
event: error      data: {"code":"UPSTREAM_TIMEOUT","message":"模型服务超时，请重试","traceId":"..."}
: ping                                                          # 每 15 秒发一次注释行作为心跳
```

- 只在流开始时鉴权一次，流式过程中 Token 过期不会打断输出。
- S5 已验证：MVC 直接返回 `Flux<ServerSentEvent<?>>` 与 Spring Security 兼容，客户端断开后上游会被取消。但流内的回调不在请求线程上，读不到 `SecurityContextHolder`，所以 `Actor` 和 traceId 必须在返回 Flux 之前取出，显式传给后续流程。
- Nginx 针对 `/api/v1/conversations/*/messages/stream` 配置 `proxy_buffering off`，读超时设为 300 秒。

### 6.5 报告与管理

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | `/reports` · `/reports/{id}` · `/reports/{id}/download` | 本人 / ADMIN | 下载格式为 `text/markdown` |
| GET | `/admin/logs/llm-calls` · `/admin/logs/tool-calls` | ADMIN | 支持按 traceId、用户、时间、成败、decision 过滤；返回的都是脱敏后的数据 |
| GET | `/admin/metrics/overview?from&to` | ADMIN | 按天聚合：调用次数、成功率、P50/P95 延迟、首字延迟、Token 用量（M5 实现，见 §9.1） |
| GET | `/admin/tasks?status=` · POST `/admin/tasks/{id}/retry` | ADMIN | 查看 DEAD 状态的任务并手动重试（M5 实现，见 §9.1） |
| GET | `/actuator/health` · `/actuator/prometheus` | health 公开；prometheus 只对内网开放 | |

---

## 7. 测试方法

所有测试用例都是**计划**，实现后逐项执行。只有真正跑过的结果才能写进 README。

| 层级 | 工具 | 重点用例 |
|---|---|---|
| 单元测试 | JUnit 5 + Mockito + AssertJ | 切分器（边界、重叠、不跨页、超长句）· `CitationParser`（§4.5 的层次 A）· `SensitiveDataMasker`（每条规则的正例和反例，包括 Luhn 误判）· `ToolPolicy` 权限矩阵（角色 × 工具 × 区域的参数化测试）· 区域范围求交 · 调用预算 · 重试退避计算 · 文档和消息状态机的非法状态转换 · 报告 Markdown 生成 |
| 接口切片测试 | `@WebMvcTest` + Spring Security Test（组合注解 `@SecuredWebMvcTest`：使用真实的 SecurityConfig 和 JWT 校验，只 mock 依赖 Redis/数据库的组件） | 每个接口的 401 / 403 / 404（访问他人资源）/ 400；`/admin/**` 对非 ADMIN 用户返回 403，且业务 Service 不被调用 |
| 集成测试 | Testcontainers（`pgvector/pgvector:pg17`、`redis:7`）+ Fake 模型 | 见下方列表 |
| 架构测试 | ArchUnit | 分层规则；tool 包不能直接执行 SQL；AI 调用类不能使用 `@Transactional` |
| 离线评测（P1） | 评测集 + 真实模型 | §4.5 的层次 B；不进入 CI |
| 手工冒烟 | 真实 Key + `docker compose up` | 按 README 的演示脚本逐步执行 |

**集成测试用例**：
- **入库**：上传后轮询到 READY，chunk 数量和页码正确；重复上传幂等；**两个线程并发上传同一个文件，最终只有一条文档记录、一个文件**。
- **补偿**：在数据库插入阶段注入异常，断言文件被删除；手工制造一个孤儿文件，执行对账后文件被删除，而宽限期内的文件保留。
- **任务恢复**：领取任务后模拟执行者死亡（租约过期），另一个 worker 接管；旧 worker 的最终提交因为 fencing 校验被拒绝；关闭 AFTER_COMMIT 事件后，任务仍然被扫描器执行；Fake Embedding 前两次返回 503、第三次成功，断言任务最终成功且 attempt 为 3。
- **事务边界**：Fake Embedding 人为阻塞 2 秒，期间断言 Hikari 活跃连接数没有增加（证明外部调用期间不占用连接）。
- **删除竞争**：入库处理过程中删除文档，断言最终没有残留 chunk，文件被删除。
- **权限**：用户 A 检索不到 B 的私有知识库内容；Fake 模型按脚本发起越权工具调用（USER 调用 `countAnomalies`、EAST 区域的 ANALYST 请求 `region=NORTH`），断言调用被拒绝、审计记录为 DENIED、**业务仓储没有被调用**（用 spy 验证）。
- **工具数字**：报告中的数字等于直接执行 SQL 的结果。
- **SSE**：事件顺序为 `meta → tool… → delta… → citations → done`；客户端中途断开后，消息状态为 INTERRUPTED，部分内容已保存。
- **通义流式分片回放**（M4，来自 S2）：用本地 Mock 服务器回放通义风格的工具调用分片（续传分片带 `"id": ""`），走真实的 `OpenAiChatModel`，断言流式工具调用正常完成。这个用例不需要 Key，用来在升级 Spring AI 时发现回归。
- **脱敏**：问题中包含手机号和身份证号时，`llm_call_log` 里没有原文。

**Fake 模型**：
- `ScriptedChatModel` 按脚本依次返回文本或工具调用请求，支持 `call` 和 `stream`。
- `DeterministicEmbeddingModel` 用哈希生成向量；也可以为指定文本预设向量，用来精确控制检索结果。它只用于验证 SQL、过滤和流程的正确性，**不用于评估语义检索质量**。

---

## 8. 分阶段实施计划与验收标准

| 阶段 | 内容 | 验收标准（实现后验证） |
|---|---|---|
| **M0 骨架 + Spike** ✅ 2026-11-04 完成 | 锁定版本；Maven 工程和包结构；Compose（postgres + redis）；Flyway V1；统一响应和异常；TraceId；Actuator；ArchUnit；`.env.example`；完成 Spike S1–S5 并把结论写回 §2.2 | `docker compose up -d` 后 health 返回 UP；`mvn verify` 在没有 Key 的情况下通过；仓库中没有密钥；§2.2 的 5 项都有明确结论和选定的退路 |
| **M1 认证与权限** ✅ 2026-11-04 完成 | 用户、JWT、Refresh Token 轮换、登出黑名单、角色、区域范围、限流、`/admin/users` | 三种角色可以登录；401/403/404 的语义符合约定；登出后旧 Token 失效；账号停用后立即失效 |
| **M2 知识库与入库** ✅ 2026-11-04 完成 | 知识库和成员；StorageService；上传和删除流程；`async_task` 扫描、领取、租约、重试；切分器；Embedding；对账任务 | §7 中入库、补偿、任务恢复、事务边界、删除竞争相关的集成测试全部通过；一份真实的 20 页 PDF 能在合理时间内变为 READY |
| **M3 KB_QA 与会话** ✅ 2026-11-05 完成 | 检索和调试接口；KB_QA 流程；SSE 事件协议；引用解析；会话和消息状态机；`LlmCallRecorder` + 脱敏 | 引用合法性测试通过；问知识库里没有的内容时拒答且不调用 LLM；断开连接后消息为 INTERRUPTED；日志中没有原文敏感信息 |
| **M4 Agent 与工具** ✅ 2026-11-05 完成 | 4 个工具；`GuardedToolCallback`；`ToolPolicy`；`CitationRegistry`；工具事件推送；报告生成和下载；工具审计 | 权限矩阵的参数化测试和越权集成测试通过；报告数字与 SQL 一致；超出调用预算时安全收尾；SSE 中能看到工具调用的完整过程；通义流式分片回放测试通过（§7） |
| **M5 前端** ✅ 2026-11-05 完成 | 见 §9 | 在浏览器里不借助 Postman 完成整条演示流程：上传 → 问答（流式、引用）→ Agent 分析（工具过程可视化）→ 下载报告 → 管理员查看日志 |
| **M6 部署与文档** ✅ 2026-11-06 完成 | 后端 Dockerfile（多阶段构建，`eclipse-temurin:21-jre`）；前端 Nginx 镜像；完整的 Compose（含健康检查和依赖顺序）；README（架构图、启动步骤、演示脚本、设计取舍、已知限制） | 在一台新机器上执行 `cp .env.example .env`、填入 Key、`docker compose up`，就能完整运行 |
| P1（MVP 之后） | 离线评测（§4.5 层次 B）；GitHub Actions CI（✅ 2026-11-06，`.github/workflows/ci.yml`：后端全部测试、前端测试与打包、全新 Linux 机器上的 Compose 部署检查）；混合检索 / Rerank；问题改写；指标看板 | 评测结果来自真实运行 |
| P2 | 测井异常诊断模块（§10） | |
| P3 | 只读数据库账号；MinIO；Ollama 离线方案 | |

每个阶段结束时都要满足：代码可运行，本阶段的测试已实际执行并通过，README 已同步更新。之后再进入下一阶段。

### 8.1 M6 实现说明

部署形态：`web`（Nginx，托管前端并反向代理 `/api`）→ `backend`（Spring Boot）→ `postgres`（pgvector）、`redis`，都在 Docker Compose 的同一个网络里。只有 `web` 映射端口（默认 8088）；后端和 Actuator 不直接对外，数据库和 Redis 只绑定本机回环地址，供本地开发使用。

**镜像**：
- 后端：多阶段构建。`maven:3.9-eclipse-temurin-21` 打包后，用 `java -Djarmode=tools extract --layers --launcher` 按 Spring Boot 分层解压。运行镜像 `eclipse-temurin:21-jre`，依赖层约 180 MB、应用层约 2 MB，改代码只重建最后一层。以非 root 用户运行，带 `HEALTHCHECK`（JRE 镜像自带 curl）；时区 Asia/Shanghai；`MaxRAMPercentage=75`。
- 前端：`node:22-alpine` 执行 `npm ci` 和 `npm run build`（含类型检查），产物放进 `nginx:1.28-alpine`。
- 构建时的依赖下载使用 BuildKit 缓存。国内网络可以通过 `MAVEN_MIRROR`、`NPM_REGISTRY` 指定镜像源（构建参数，默认使用官方源）。

**Compose**：
- `backend`、`web` 属于 `app` profile。`.env.example` 写了 `COMPOSE_PROFILES=app`，新环境 `docker compose up` 就会启动全部服务；本地开发时执行 `docker compose up -d postgres redis`，只启动基础设施。已有的 `.env` 没有这一行，行为不变。
- 启动顺序靠健康检查保证：postgres、redis 健康 → backend 健康（`/actuator/health`）→ web。
- 后端的其他配置仍从 `.env` 读取（`env_file`），与本地开发共用一份；连接地址、端口、密码在 `environment` 中按容器网络覆盖。数据库密码等用插值写入，保证与数据库容器初始化时使用的值一致。
- `JWT_SECRET`、`DEMO_USER_PASSWORD`、`AI_API_KEY` 用 `${VAR:?提示}` 必填。Compose 在启动任何服务之前都会检查（即使只启动数据库也会检查），缺少时直接给出中文提示，而不是构建几分钟后让后端反复重启。
- 与 §8 原计划的差别：原定“只填 Key”就能运行，实际还要填写 JWT 签名密钥和演示账号密码。示例配置里不放默认值，避免有人直接用默认密钥部署到公网。

**Nginx**（`frontend/nginx.conf`）：
- 流式接口 `^/api/v1/conversations/\d+/messages/stream$` 关闭缓冲和压缩，读超时 300 秒（与后端的 5 分钟一致，§6.4）。后端同时在响应头中带 `X-Accel-Buffering: no`。
- `proxy_set_header` 写在 server 级别：location 中一旦再写，就不会继承 server 级别的任何一条（Host、X-Forwarded-For 会丢失）。`add_header` 同理，所以各 location 用 `expires` 控制缓存，不写 `add_header`。
- 安全响应头：CSP（`script-src 'self'`，不允许内联脚本和外部来源；Element Plus 需要 `style-src 'unsafe-inline'`）、`X-Content-Type-Options`、`X-Frame-Options`、`Referrer-Policy`；不暴露版本号。
- 上传上限 21 MB；带哈希的静态资源缓存一年，`index.html` 不缓存；前端路由回退到 `index.html`。

**后端调整**：
- `server.forward-headers-strategy: native`：信任内网代理的 `X-Forwarded-For`，登录限流按真实客户端 IP 计算。只有直接连接方是内网地址时才采信，客户端直连时伪造不了。
- 演示账号由 `APP_DEMO_SEED_USERS` 控制，local profile 和 Compose 部署中开启。

**验证（2026-11-06）**：在本机用隔离的 Compose 项目（单独的项目名、端口和全新数据卷）模拟新环境，详见 [DEVLOG](DEVLOG.md) 的 M6 一节。
- 四个服务按依赖顺序变为 healthy。
- 流式输出经过 Nginx 逐段推送（0.54 秒收到第一段文字，43 段在 5.4 秒内陆续到达）。
- 限流记录的是 Docker 网关地址，而不是 Nginx 容器的地址。
- 用 Playwright 驱动本机 Edge 走完整条演示流程（真实模型），CSP 下页面没有控制台错误。

---

## 9. 前端设计（Vue 3 + TypeScript，企业后台风格）

**基础约束**：
- 前端只调用本系统的 REST 和 SSE 接口，**不包含任何模型 API 地址或 Key**。
- 布局采用 Element Plus 的经典后台结构：左侧菜单、顶部用户栏和面包屑、内容区。
- 按角色控制菜单显示，只用于改善体验，权限以后端为准。

| 页面 | 功能 |
|---|---|
| 登录页 | 登录；Axios 拦截器在收到 401 时自动刷新一次 Token 并重放请求，刷新失败则跳回登录页。刷新必须串行（single-flight）：同一时刻只发一个刷新请求，其余请求等待它的结果，否则会触发 Refresh Token 的重用检测（§4.8） |
| 知识库 | 知识库列表、新建、成员管理；文档表格（状态标签，PENDING/PROCESSING 时轮询，FAILED 时显示错误码并提供重试按钮）；拖拽上传；chunk 预览抽屉；检索调试面板 |
| 智能对话 | **左栏**：会话列表（新建时选择模式和知识库、搜索、重命名、删除、按时间分组）。**中栏**：消息流，Markdown 渲染（`markdown-it` 关闭 HTML，再经过 `DOMPurify` 净化，代码高亮）；流式渲染用 `requestAnimationFrame` 节流；`[n]` 渲染成可点击的角标；“停止生成”按钮；失败或中断的消息可以重试。**工具调用时间线**：折叠卡片显示工具名、脱敏参数、状态（进行中、成功、被拒绝、失败）、耗时和结果摘要。**右侧引用面板**：文档名、页码、片段、相似度，点击角标时联动高亮 |
| 报告 | 列表、Markdown 预览、下载 |
| 系统管理（ADMIN） | 用户管理（角色、区域范围、启用/停用）；LLM 和工具调用日志（按 traceId 串联查看）；失败任务列表和重试；统计概览（MVP 用表格和数字卡片展示） |

**技术细节**：
- SSE 用 `@microsoft/fetch-event-source` 发起请求，因为它支持 POST 方法和 `Authorization` 请求头，原生的 EventSource 不支持。
- 事件类型在 `types/sse.ts` 中与后端协议一一对应。
- 状态管理用 Pinia：`auth`、`conversation`、`chatStream` 三个 store。

### 9.1 M5 实现说明

前端工程位于 `frontend/`：Vue 3.5 + TypeScript 6 + Vite 8 + Element Plus 2.14（全量引入，中文语言包）+ Pinia 4 + Vue Router 5，单元测试用 Vitest + jsdom。开发时 Vite 把 `/api` 代理到后端，后端不需要配置 CORS；M6 由 Nginx 做同样的事。

**目录**：
- `api/`：Axios 实例和拦截器、各模块接口、SSE。
- `types/`：`api.ts` 与后端 DTO 一一对应；`sse.ts` 与 `ChatEvent` 一一对应；`chat.ts` 是页面上的消息模型，以及把 SSE 事件合并到消息上的纯函数（便于单元测试）。
- `stores/`：`auth`、`conversation`、`chatStream` 三个 Pinia store。
- `utils/`：Markdown 渲染、令牌存储、格式化、枚举的中文标签。

**令牌与刷新**（§4.8 要求前端串行刷新）：
- 令牌保存在 localStorage，刷新页面后保持登录，多个标签页共享。取舍：页面脚本能读到令牌，所以模型输出一律经过 `html:false` 和 DOMPurify。改用 httpOnly Cookie 需要后端配合（CSRF、SameSite），留到以后。
- 只有错误码为 `UNAUTHORIZED` 的 401 才刷新，并且只刷新一次再重放；`BAD_CREDENTIALS`、`ACCOUNT_DISABLED`、`REFRESH_TOKEN_INVALID` 不刷新。账号停用或刷新失败时清空本地令牌，跳到登录页并带上 `redirect`。
- 串行刷新有三层：
  1. 同一页面内 single-flight：所有 401 的请求共享同一个刷新 Promise。
  2. 多个标签页之间用 Web Locks（`navigator.locks`）互斥。
  3. 拿到锁之后，先比较失败请求携带的令牌和当前令牌：不同说明别处刚刷新过，直接用新令牌重放，不再刷新。
- 浏览器实测：并发 3 个请求同时 401，只发出 1 次刷新，3 个请求都重放成功。
- 其他标签页登出时，通过 `storage` 事件同步回到登录页。

**SSE**：
- 用 `@microsoft/fetch-event-source` 发 POST，带 Authorization 头。它默认会在出错时自动重试，并在页面隐藏时断开、可见时重连。对提问接口来说，这两种行为都等于把问题再提交一遍，所以设置了 `openWhenHidden: true`，并在 `onerror` 里直接抛出，不重试。
- 开始输出之前的错误是普通 JSON（§6.4），在 `onopen` 里转换成 `ApiError`；其中 401 会先刷新一次 Token 再重新发起。流开始之后服务端不再鉴权，输出过程中 Token 过期不影响。
- 停止生成就是 `AbortController.abort()`。前端立即把消息标记为已停止；服务端感知断开后保存已生成的部分，消息为 INTERRUPTED。“重新提问”是把上一条问题再发一次，后端没有单独的重新生成接口。
- 同一时刻只允许一轮流式问答。切到别的会话时，这一轮在后台继续；切回来时，用内存中的实时消息替换服务端的占位消息。

**渲染**：
- `delta` 用 `requestAnimationFrame` 合并，每帧最多重新渲染一次 Markdown。其他事件到达时，先把缓冲的文本写入，保证顺序。
- Markdown：markdown-it（`html:false`）→ DOMPurify → highlight.js（只注册常用的 9 种语言）。链接一律在新窗口打开，并带 `noopener noreferrer`。
- `[n]` 角标由 markdown-it 的 core 规则生成，识别规则与后端 `CitationParser` 一致：半角、全角方括号和【】，多个编号用逗号、顿号或空白分隔。行内代码和代码块里的编号不是 text token，天然不会被识别。
- 流式输出中引用还没确定，编号显示为灰色的待定角标。收到 `citations` 之后，只有合法编号可以点击，`invalidIndices` 按普通文本显示。回答中的角标、右侧的引用卡片、回答下方的引用标签三者联动高亮。
- 工具时间线按 `callId` 合并 START 和 END。只有 END 没有 START（例如参数不合法）也会显示；本轮结束时还没收到 END 的步骤不再显示“进行中”。状态分为进行中、成功、执行失败、无权限已拒绝、参数不合法、超出调用预算；参数显示的是服务端已脱敏的内容。
- 报告工具的摘要形如“已生成报告 #12：…”，前端据此在回答下方显示“查看报告 #12”，可以预览和下载。
- AGENT 回答有未核实数字时显示警告标签，数字列表在悬浮提示里；回答末尾的核对说明来自后端。调用过工具且没有未核实数字时显示“数字核对通过”。
- 管理员在每条回答下方能看到 traceId 链接，点击跳到调用日志的串联视图。

**为 M5 补齐的后端接口**（§6.5 设计过，此前没有实现）：
- `GET /admin/tasks?status=&type=`：脱敏后的 `last_error` 只给管理员看。
- `POST /admin/tasks/{id}/retry`：只能重试 DEAD 的任务。
  - 实现上是把同一行改回 PENDING、尝试次数清零，而不是新建一行。这样 DEAD 列表自然变短，管理员也能用同一个任务 ID 跟踪结果。
  - 加锁顺序与其他流程一致：先由 `TaskHandler.onManualRetry` 锁定业务对象并同步状态，再改任务表。入库任务要求文档是 FAILED，并把它改回 PENDING；删除任务要求文档仍是 DELETING。
  - 业务对象已经不需要重试（例如文档已被所有者重新处理）时返回 409，整个事务回滚。
- `GET /admin/metrics/overview?from&to`：
  - 按业务时区（Asia/Shanghai）的自然日聚合，一次最多 92 天，没有数据的日子补零。
  - 延迟和首字延迟只统计对话调用（CHAT），向量化调用只计次数，否则百分位会被几十毫秒的 Embedding 调用拉低。
  - 百分位用 `percentile_cont`。合计行用 `GROUPING SETS` 在同一条 SQL 里算出，因为百分位不能由每天的百分位推出。
  - `toolDenied` 只统计 DENIED（越权尝试），不含参数不合法和超出预算。

**测试**：
- 前端单元测试 26 个（`npm test`）：
  - XSS 过滤和链接属性；角标规则（合法、不合法、混合、代码块、待定）。
  - SSE 事件合并，包括只有 END 的工具调用和 error 事件。
  - 串行刷新：并发 401 只刷新一次、别处已刷新时不再刷新、刷新失败、账号停用、登录接口不刷新。
  - 流式接口：事件解析、忽略心跳、限流错误、401 刷新后重新发起、中途断开时不重试。
  - 下载文件名解析、会话按时间分组。
- 后端：`AdminTaskIT`、`MetricsQueryServiceIT`，以及两个接口切片测试。

**浏览器实测**（2026-11-05，Vite 开发服务器代理到本机后端，qwen-plus + text-embedding-v3，使用临时创建的三个角色的测试账号，测试后已停用）：
- 分析员新建知识库，上传示例 PDF：3 页、3 个分片，约 3 秒内变为可用。分片预览正常；检索调试的 3 个结果都超过 0.50 阈值。
- 知识库问答：1.8 秒完成，首字 1.0 秒；回答带 `[1]` 角标，点击后右侧卡片和下方标签同时高亮。
- Agent：
  - 订单统计调用耗时 38 ms，回答限定在华东，并说明其他区域无权查看。
  - 生成报告调用耗时 73 ms；“查看报告 #4”能预览，下载得到中文文件名的 `.md` 文件。
  - 问华北时，模型直接说明无权查看，没有给出数字。
- 停止生成：保留了 3 次工具调用和已生成的部分；刷新页面后，历史消息显示为中断状态。
- 管理员：调用日志按 traceId 串联出 1 次模型调用和 3 次工具调用；统计概览、任务管理、用户管理（停用账号）正常。
- 普通员工看不到系统管理菜单，直接访问后台地址进入 403 页。
- 实测中修正的问题：
  - AGENT 回答没有引用时（`citationMode` 为 NONE），页面误显示 KB_QA 的“知识库中没有相关资料，未调用模型”。
  - 值为空字符串的“全部”选项，在 Element Plus 中会显示成占位符。

**已知问题**：
- qwen-plus 有时在调用工具之前，先输出自己的权限分析和调用计划（例如“用户仅可查看华东……需调用 queryOrderStats……”），这段文字会出现在回答里。2026-11-06 试过在提示词中要求“调用工具前不要先说计划”，实测反而让模型更少调用工具（§4.11），没有采用。
- 工具时间线放在回答文本上方，不保留文本和工具调用的先后顺序；历史消息里也没有保存这个顺序。
- Element Plus 全量引入，主包约 876 KB（gzip 后 279 KB），按需引入留到以后。
- 同一时刻只能进行一轮流式问答。
- 知识库所有者添加成员时，需要输入对方的用户 ID，因为只有管理员能查询用户列表。

---

## 10. 后续特色模块：测井异常诊断（P2，只预留扩展点）

- **定位**：在“订单与异常事件分析”之外，增加一个面向石油测井领域的异常诊断工具，体现项目的领域特色。
- **预留的扩展点**（MVP 阶段不实现）：
  - 新建 `welllog` 包，与 `business` 包平行。
  - 新增工具 `diagnoseWellLogAnomaly(wellId, depthFrom, depthTo, curves)`，同样经过 `GuardedToolCallback` 和 `ToolPolicy`（可以新增一个领域角色或数据范围）。
  - 模型推理（例如 Transformer-VAE）作为独立的 Python 服务部署，Java 端通过带超时和熔断的 HTTP 客户端调用。Java 只负责参数校验、权限、审计和结果解释，不在 JVM 里做推理。
  - 数据表（井、曲线、诊断记录）等到 P2 阶段再设计。
- MVP 中的工具注册机制、权限矩阵和审计方式都保持通用，不针对订单场景硬编码，这样以后可以直接把新工具接进来。

---

## 11. 架构决策记录（ADR）

| 编号 | 决策 | 原因 | 放弃的方案 | 代价 |
|---|---|---|---|---|
| ADR-01 | Java 单体，按功能分包 | 校招作品的核心是展示工程质量，单体便于部署、调试和测试 | 微服务 | 模块边界只能靠 ArchUnit 约束 |
| ADR-02 | 自建 `doc_chunk` 表，只使用 Spring AI 的 `EmbeddingModel` | 权限过滤是普通 SQL 列；可以级联删除；页码是强类型字段；表结构由 Flyway 管理 | `PgVectorStore` 自动建表 | 需要手写约 50 行 SQL 映射 |
| ADR-03 | 先写文件，再提交数据库；失败时补偿删除；定时对账 | 文件系统不能参与事务；确立“数据库是权威”这一不变式后，可以安全删除孤儿文件；进程在任意时刻崩溃都能收敛到一致状态 | 先写数据库再写文件（崩溃会留下悬空记录，更难处理）；分布式事务 | 存在 1 小时的宽限期，期间孤儿文件会暂时占用磁盘 |
| ADR-04 | 数据库任务表 + `SKIP LOCKED` 领取 + 租约和 fencing；事件只用于唤醒 | 内存事件可能丢失；数据库已有持久化和行锁，不需要引入新组件；fencing 能防止旧执行者覆盖结果 | 只依赖 AFTER_COMMIT 事件；Redis 锁；引入 MQ | 有一个 5 秒的扫描周期（被事件唤醒时延迟几乎为零） |
| ADR-05 | 外部调用期间不开事务；只使用短事务；关闭 open-in-view | 外部 API 的耗时可达数秒到数十秒，长事务会占满连接池并延长锁持有时间 | 在一个大事务里完成整个入库过程 | 需要 fencing 校验和状态复核，处理“计算期间数据已被删除”的情况 |
| ADR-06 | 优先使用 `ToolCallingAdvisor`，按 L0 → L1 → L2 逐级升级 | 2.0 的框架机制已经支持流式输出、Token 累计和钩子；自己写循环会增加维护成本，也偏离框架的标准用法 | 一开始就自己写 Agent 循环 | 依赖 Spike S2/S4 的验证结果；轮次上限需要靠调用预算间接实现 |
| ADR-07 | 工具按角色暴露，执行时在服务端二次校验；身份来自 `ToolContext`；Service 方法显式接收 `Actor` 参数 | 模型的输出不可信，暴露过滤也不是安全边界；流式模式下 ThreadLocal 不可靠 | 只依赖暴露过滤；在 Prompt 里约束权限 | Service 方法签名会多一个参数 |
| ADR-08 | 写入时脱敏；默认不记录 Prompt 和回答；日志接口只对 ADMIN 开放；保留 30 天 | 从源头杜绝敏感数据进入日志；读取时脱敏无法防止数据库导出导致的泄露 | 记录全文、读取时再脱敏 | 调试时信息较少，需要靠 traceId 加用户自己的会话记录来还原问题 |
| ADR-09 | 引用拆分为“合法性”（CI 中确定性验证）和“事实依据”（离线评测） | 两者性质不同：前者能被代码保证，后者只能被度量。混在一起会让 CI 结果不稳定，也容易高估系统质量 | 只做格式校验，或者把 LLM-as-judge 当作 CI 门禁 | 需要额外构建评测集（P1 阶段） |
| ADR-10 | 报告数字由 Java 确定性生成，MVP 中不让 LLM 写报告段落 | 杜绝数字被编造，测试可以断言结果相等；也减少一次模型调用 | 让 LLM 直接生成报告全文 | 报告的叙述性较弱，由对话中的最终回答来补充分析 |
| ADR-11 | 消息表自管，不使用 `MessageChatMemoryAdvisor` | 消息表需要保存引用、工具步骤、状态和归属信息；多数 ChatMemory 仓储实现不支持工具消息 | 使用 Spring AI 的 ChatMemory | 需要手动拼接历史消息（代码量很小） |
| ADR-12 | SSE 使用 POST + fetch stream | 可以携带请求体和 Authorization 请求头；断开连接就能触发服务端取消 | 原生 EventSource、WebSocket | 前端需要引入一个小依赖 |
| ADR-13 | Refresh Token 用不透明随机串，存在 Redis 中，每次刷新都轮换，并做重用检测 | 可以随时撤销；Redis 只存哈希，泄露也拿不到原文；旧令牌被重复使用时撤销整个令牌族，能发现令牌被盗用 | 长期有效的 JWT Refresh Token（无法撤销）；数据库表（需要自己清理过期数据） | 前端必须串行刷新；重用检测会让合法用户也重新登录一次 |
| ADR-14 | Access Token 只携带身份；角色和区域范围每个请求都从数据库加载（短 TTL 缓存，事务提交后清除） | 停用账号、修改角色立即生效，不必等 Token 过期；权限的唯一来源是数据库 | 把角色写进 Token（修改后最多要等 15 分钟才生效）；每个请求都直接查库（可行，但没有必要） | 每个请求多一次缓存读取；多实例部署时需要广播缓存清除 |

---

## 12. 环境变量（`.env.example` 草案）

```
POSTGRES_DB=kbagent
POSTGRES_USER=kbagent
POSTGRES_PASSWORD=change-me
REDIS_PASSWORD=change-me
JWT_SECRET=                         # ≥32 字节随机串
DEMO_USER_PASSWORD=                 # 演示账号的初始密码
AI_BASE_URL=https://dashscope.aliyuncs.com/compatible-mode/v1   # 必须以 /v1 结尾（§2.2 S2）
AI_API_KEY=                         # 通义 DashScope Key，只能通过环境变量注入
AI_CHAT_MODEL=qwen-plus
AI_EMBEDDING_MODEL=text-embedding-v3
AI_EMBEDDING_DIMENSIONS=1024
APP_STORAGE_ROOT=/data/storage
APP_LLM_LOG_CONTENT=MASKED_PREVIEW  # NONE | MASKED_PREVIEW
APP_LOG_RETENTION_DAYS=30
APP_RATE_LIMIT_PER_MINUTE=20
APP_AGENT_TOOL_BUDGET=6
APP_LOGIN_RATE_LIMIT_PER_MINUTE=10  # M1：每个客户端 IP 每分钟的登录次数
```
