# 企业智能知识库与业务分析 Agent 平台 — 架构方案

> 版本 v0.2.2（2026-11-04）· **状态：M0 已完成。** 工程骨架已编译，`mvn verify` 已在本机通过，Spike S1–S5 已实测并回填 §2.2。M1 起的“验收标准”和“测试用例”仍是计划，要在实现后逐项验证。
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
| PDF 解析 | `spring-ai-pdf-document-reader`（基于 PDFBox） | 按页读取 |
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
| security / user | Access Token 有效期 15 分钟，Refresh Token 7 天并在 Redis 中轮换；登出时把 jti 加入黑名单；每次请求从数据库重新加载用户状态（短 TTL 缓存），已停用的账号会立即失效 | 角色和区域范围以服务端数据库为准，不信任 Token 之外的任何来源 |
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
- **限流**：Redis 令牌桶，每个用户每分钟 20 次对话，超出返回 429。
- **重试和超时**：2.0 的 OpenAI 模块由 OpenAI SDK 自己负责超时和重试，配置项是 `spring.ai.openai.timeout` 和 `spring.ai.openai.max-retries`（可以按 chat / embedding 分别覆盖）。流式输出开始之后不再重试，失败就推送 `error` 事件，并把消息标记为 FAILED。

---

## 5. 数据库表设计

所有 ID 使用 `BIGINT GENERATED ALWAYS AS IDENTITY`，时间使用 `TIMESTAMPTZ`。Flyway 脚本规划：
- `V1__init.sql`：建表
- `V2__seed_business_data.sql`：业务种子数据
- `V3__seed_demo_users.sql`：演示账号（初始密码从环境变量读取）

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
- 分页参数 `page`、`size`。
- HTTP 状态码：400 参数错误 / 401 未认证 / 403 无权限 / 404 不存在（**访问不属于自己的资源也返回 404**，避免泄露资源是否存在）/ 409 冲突 / 429 限流 / 502 上游模型错误。

### 6.2 认证、用户、知识库、文档

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| POST | `/auth/login` · `/auth/refresh` · `/auth/logout` | 公开 / 登录 | Refresh Token 轮换；登出时 jti 进入黑名单 |
| GET | `/users/me` | 登录 | 返回角色和区域范围（前端据此控制菜单显示，但**真正的权限由后端判断**） |
| POST / GET / PATCH | `/admin/users[/{id}]` | ADMIN | 创建用户、启用/停用、修改角色和 `regionScope` |
| POST / GET | `/knowledge-bases` | 登录 | 列表只返回有权限的知识库 |
| GET / PATCH / DELETE | `/knowledge-bases/{kbId}` | owner / ADMIN | |
| PUT / DELETE | `/knowledge-bases/{kbId}/members/{userId}` | owner / ADMIN | |
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
| POST | `/conversations` | 登录 | `{mode: KB_QA\|AGENT, kbId?}`。所有角色都可以创建 AGENT 会话，**可用的工具由角色决定**（§4.4.3） |
| GET | `/conversations?keyword=&page=` | 本人 | 按更新时间倒序 |
| PATCH / DELETE | `/conversations/{id}` | 本人 | 重命名、软删除 |
| GET | `/conversations/{id}/messages?beforeId=&size=` | 本人 | 游标分页，包含引用和工具步骤 |
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
event: done       data: {"status":"OK","usage":{"prompt":1830,"completion":412,"estimated":false},"latencyMs":5210,"firstTokenMs":820}
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
| GET | `/admin/metrics/overview?from&to` | ADMIN | 按天聚合：调用次数、成功率、P50/P95 延迟、首字延迟、Token 用量 |
| GET | `/admin/tasks?status=` · POST `/admin/tasks/{id}/retry` | ADMIN | 查看 DEAD 状态的任务并手动重试 |
| GET | `/actuator/health` · `/actuator/prometheus` | health 公开；prometheus 只对内网开放 | |

---

## 7. 测试方法

所有测试用例都是**计划**，实现后逐项执行。只有真正跑过的结果才能写进 README。

| 层级 | 工具 | 重点用例 |
|---|---|---|
| 单元测试 | JUnit 5 + Mockito + AssertJ | 切分器（边界、重叠、不跨页、超长句）· `CitationParser`（§4.5 的层次 A）· `SensitiveDataMasker`（每条规则的正例和反例，包括 Luhn 误判）· `ToolPolicy` 权限矩阵（角色 × 工具 × 区域的参数化测试）· 区域范围求交 · 调用预算 · 重试退避计算 · 文档和消息状态机的非法状态转换 · 报告 Markdown 生成 |
| 接口切片测试 | `@WebMvcTest` + Spring Security Test | 每个接口的 401 / 403 / 404（访问他人资源）/ 400；`/admin/**` 对非 ADMIN 用户返回 403 |
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
| **M1 认证与权限** | 用户、JWT、Refresh Token 轮换、登出黑名单、角色、区域范围、限流、`/admin/users` | 三种角色可以登录；401/403/404 的语义符合约定；登出后旧 Token 失效；账号停用后立即失效 |
| **M2 知识库与入库** | 知识库和成员；StorageService；上传和删除流程；`async_task` 扫描、领取、租约、重试；切分器；Embedding；对账任务 | §7 中入库、补偿、任务恢复、事务边界、删除竞争相关的集成测试全部通过；一份真实的 20 页 PDF 能在合理时间内变为 READY |
| **M3 KB_QA 与会话** | 检索和调试接口；KB_QA 流程；SSE 事件协议；引用解析；会话和消息状态机；`LlmCallRecorder` + 脱敏 | 引用合法性测试通过；问知识库里没有的内容时拒答且不调用 LLM；断开连接后消息为 INTERRUPTED；日志中没有原文敏感信息 |
| **M4 Agent 与工具** | 4 个工具；`GuardedToolCallback`；`ToolPolicy`；`CitationRegistry`；工具事件推送；报告生成和下载；工具审计 | 权限矩阵的参数化测试和越权集成测试通过；报告数字与 SQL 一致；超出调用预算时安全收尾；SSE 中能看到工具调用的完整过程；通义流式分片回放测试通过（§7） |
| **M5 前端** | 见 §9 | 在浏览器里不借助 Postman 完成整条演示流程：上传 → 问答（流式、引用）→ Agent 分析（工具过程可视化）→ 下载报告 → 管理员查看日志 |
| **M6 部署与文档** | 后端 Dockerfile（多阶段构建，`eclipse-temurin:21-jre`）；前端 Nginx 镜像；完整的 Compose（含健康检查和依赖顺序）；README（架构图、启动步骤、演示脚本、设计取舍、已知限制） | 在一台新机器上执行 `cp .env.example .env`、填入 Key、`docker compose up`，就能完整运行 |
| P1（MVP 之后） | 离线评测（§4.5 层次 B）；GitHub Actions CI；混合检索 / Rerank；问题改写；指标看板 | 评测结果来自真实运行 |
| P2 | 测井异常诊断模块（§10） | |
| P3 | 只读数据库账号；MinIO；Ollama 离线方案 | |

每个阶段结束时都要满足：代码可运行，本阶段的测试已实际执行并通过，README 已同步更新。之后再进入下一阶段。

---

## 9. 前端设计（Vue 3 + TypeScript，企业后台风格）

**基础约束**：
- 前端只调用本系统的 REST 和 SSE 接口，**不包含任何模型 API 地址或 Key**。
- 布局采用 Element Plus 的经典后台结构：左侧菜单、顶部用户栏和面包屑、内容区。
- 按角色控制菜单显示，只用于改善体验，权限以后端为准。

| 页面 | 功能 |
|---|---|
| 登录页 | 登录；Axios 拦截器在收到 401 时自动刷新一次 Token 并重放请求，刷新失败则跳回登录页 |
| 知识库 | 知识库列表、新建、成员管理；文档表格（状态标签，PENDING/PROCESSING 时轮询，FAILED 时显示错误码并提供重试按钮）；拖拽上传；chunk 预览抽屉；检索调试面板 |
| 智能对话 | **左栏**：会话列表（新建时选择模式和知识库、搜索、重命名、删除、按时间分组）。**中栏**：消息流，Markdown 渲染（`markdown-it` 关闭 HTML，再经过 `DOMPurify` 净化，代码高亮）；流式渲染用 `requestAnimationFrame` 节流；`[n]` 渲染成可点击的角标；“停止生成”按钮；失败或中断的消息可以重试。**工具调用时间线**：折叠卡片显示工具名、脱敏参数、状态（进行中、成功、被拒绝、失败）、耗时和结果摘要。**右侧引用面板**：文档名、页码、片段、相似度，点击角标时联动高亮 |
| 报告 | 列表、Markdown 预览、下载 |
| 系统管理（ADMIN） | 用户管理（角色、区域范围、启用/停用）；LLM 和工具调用日志（按 traceId 串联查看）；失败任务列表和重试；统计概览（MVP 用表格和数字卡片展示） |

**技术细节**：
- SSE 用 `@microsoft/fetch-event-source` 发起请求，因为它支持 POST 方法和 `Authorization` 请求头，原生的 EventSource 不支持。
- 事件类型在 `types/sse.ts` 中与后端协议一一对应。
- 状态管理用 Pinia：`auth`、`conversation`、`chatStream` 三个 store。

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
```
