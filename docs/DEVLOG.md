# 开发记录

各阶段的实现细节、真实模型的实测数据和验收清单（原 README 的内容）。项目介绍和启动方法见 [README](../README.md)，设计见 [ARCHITECTURE.md](ARCHITECTURE.md)。

## 各阶段状态

验证环境：Windows 10、JDK 21.0.4、Docker Desktop 29.9。版本：Spring Boot 4.1.2 + Spring AI 2.0.2（Spike S1 锁定）。

| 项目 | 状态 |
|---|---|
| 工程骨架、统一响应、全局异常、traceId | ✅ 以 local profile 启动后冒烟检查通过：health 为 `UP`，ping 返回统一响应和 `X-Trace-Id`；401/403/404/409/429 都返回带 traceId 的统一错误体 |
| Flyway V1（13 张表 + pgvector + HNSW 索引） | ✅ 在 Testcontainers（`pgvector/pgvector:pg17`）和 Compose 的 PostgreSQL 上都执行成功 |
| Docker Compose（PostgreSQL + pgvector、Redis） | ✅ 两个容器都为 healthy；停掉 Redis 时 health 变为 `DOWN`（503），恢复后回到 `UP` |
| 认证与权限（M1） | ✅ JWT 登录、Refresh Token 轮换与重用检测、登出黑名单、三种角色与区域范围、登录限流、用户管理接口；集成测试和本机冒烟检查都已通过 |
| 知识库与文档入库（M2） | ✅ 知识库与成员权限、PDF 上传（去重、补偿、对账）、`async_task` 任务表（并发领取、租约、fencing、重试）、中文切分、Embedding 入库、异步删除和重新处理；真实的 54 页 PDF 9.3 秒变为 READY |
| 知识库问答（M3） | ✅ 检索调试接口、KB_QA 流程（检索不到时拒答且不调用模型）、SSE 事件协议、引用解析、会话与消息状态机、调用日志与脱敏；已用真实的 qwen-plus 和 text-embedding-v3 校准检索阈值和引用提示词 |
| Agent 与工具（M4） | ✅ 4 个业务工具（知识库检索、订单统计、异常事件统计、生成报告）、服务端逐次鉴权与区域收敛、调用预算、工具审计、SSE 工具事件、报告生成与下载；通义流式分片回放测试通过；已用 qwen-plus 实测并改进 Agent 提示词 |
| 前端（M5） | ✅ Vue 3 + TypeScript + Element Plus：登录与自动刷新 Token、知识库与文档、流式问答与引用、Agent 工具调用时间线、报告、系统管理（用户、调用日志、任务重试、统计概览）；已在浏览器中用真实模型走完整条演示流程 |
| 部署（M6） | ✅ 后端和前端镜像、完整的 Docker Compose（健康检查、启动顺序）、Nginx（SSE 不缓冲、CSP 等安全响应头）；在隔离的 Compose 项目和全新数据卷上用真实模型走完整条演示流程 |
| 测试 | ✅ `mvnw verify` 通过：单元 + 接口切片 + 架构测试 323 个，集成测试 82 个，不需要 API Key；前端 `npm test` 26 个 |
| Spike S1–S5 | ✅ 已在真实通义接口上执行（14 个用例全部通过），结论见 [ARCHITECTURE.md §2.2](ARCHITECTURE.md) |

### M0 技术验证的主要结论

完整结论、证据和选定方案见 [ARCHITECTURE.md §2.2](ARCHITECTURE.md)。这里只列出会直接影响后续编码的几条：

- **Spring AI 不得低于 2.0.2。** 2.0.0 在通义兼容模式下“流式 + 工具调用”会失败：通义的续传分片带空字符串 `id`，2.0.0 的分片合并逻辑处理不了。
- **通义 base-url 必须以 `/v1` 结尾**（`https://dashscope.aliyuncs.com/compatible-mode/v1`）。Spring AI 2.0 底层换成了 OpenAI 官方 Java SDK，照搬 1.x 的写法会得到 404。
- **Embedding 一次最多提交 10 条**，入库时按批切分。
- 工具在 `stream()` 模式下运行在 Reactor 线程上，读不到 `SecurityContext`，身份要通过 `ToolContext` 显式传递；在工具包装层抛出异常可以终止整轮工具调用。
- MVC 直接返回 `Flux<ServerSentEvent>` 与 Spring Security 兼容，客户端断开后上游会被取消。

## 认证与权限（M1）

设计细节见 [ARCHITECTURE.md §4.8](ARCHITECTURE.md)。所有接口都以 `/api/v1` 为前缀，除登录、刷新、ping 和 health 外都需要 `Authorization: Bearer <accessToken>`。

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| POST | `/auth/login` | 公开 | `{username, password}` → `{tokenType, accessToken, expiresIn, refreshToken, refreshExpiresIn, user}`；按 IP 限流，默认每分钟 10 次 |
| POST | `/auth/refresh` | 公开 | `{refreshToken}` → 新的一对令牌；旧 Refresh Token 立即失效 |
| POST | `/auth/logout` | 登录 | 当前 Access Token 立即失效；请求体可带 `{refreshToken}` 一并撤销 |
| GET | `/users/me` | 登录 | 当前用户的角色和区域范围 |
| POST / GET / PATCH | `/admin/users[/{id}]` | ADMIN | 创建用户、分页查询（`page` 从 1 开始）、修改显示名 / 角色 / 区域范围 / 启用状态 |

几条关键行为（都有集成测试覆盖，见 `AuthFlowIT`）：

- Access Token 15 分钟，只携带用户 ID。角色和区域范围每个请求都从数据库加载（30 秒缓存，修改后立即清除），所以**停用账号、修改角色立即生效**，不用等 Token 过期。
- Refresh Token 7 天，每次刷新都轮换。已经用过的旧令牌如果再次出现，会被当作盗用，撤销这次登录产生的全部令牌。
- 用户名不存在和口令错误返回同一个错误（`BAD_CREDENTIALS`），避免被用来枚举用户名。
- 管理员不能停用自己，也不能修改自己的角色；只有 ANALYST 有区域范围。

**演示账号**：local profile 启动时自动创建 `admin`（ADMIN）、`analyst`（ANALYST，区域 EAST）、`user`（USER）三个账号，初始密码是 `.env` 里的 `DEMO_USER_PASSWORD`。只在账号不存在时创建，之后修改 `.env` 不会改变已有账号的密码。

## 知识库与文档入库（M2）

设计见 [ARCHITECTURE.md §4.1–§4.3](ARCHITECTURE.md)，实现细节和设计调整见 §4.9。

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| POST / GET | `/knowledge-bases` | 登录 | 创建（创建者为所有者）；列表只返回有权访问的知识库 |
| GET / PATCH / DELETE | `/knowledge-bases/{kbId}` | 查看：READER；修改、删除：所有者或 ADMIN | 还有文档时不能删除（409） |
| GET / PUT / DELETE | `/knowledge-bases/{kbId}/members[/{userId}]` | 所有者或 ADMIN | 成员权限为 `READER` 或 `EDITOR` |
| POST | `/knowledge-bases/{kbId}/documents` | EDITOR | multipart 字段 `file`，只接受 PDF（≤ 20MB）。新文档返回 202，同一知识库中内容相同的文档返回 200 和已有文档 |
| GET | `/knowledge-bases/{kbId}/documents` | READER | 可按 `status` 过滤 |
| GET | `/documents/{docId}` | READER | 状态、错误码，以及最近一次入库任务的尝试次数（供前端轮询） |
| POST | `/documents/{docId}/reprocess` | EDITOR | 只有 READY 或 FAILED 的文档可以重新处理，否则 409 |
| DELETE | `/documents/{docId}` | EDITOR | 返回 202，文档进入 DELETING，文件和记录异步删除 |
| GET | `/documents/{docId}/chunks` | READER | 分页查看切分结果（含页码） |

几条关键行为：

- **无权访问返回 404，权限不够返回 403**：不是成员时，知识库和其中的文档都显示为“不存在”。
- **入库是异步的**：上传接口只负责保存文件和登记任务，后台执行“解析 → 切分（约 500 字，重叠 80 字，不跨页）→ Embedding（每批 10 条）→ 写入向量”。调用外部服务期间不开事务、不占用数据库连接。
- **可靠性**：
  - 上游的 429、5xx 和网络错误会按指数退避重试，最多 5 次；PDF 损坏、加密或没有文本则直接失败，不重试。
  - 处理中的执行者如果死亡，租约到期后任务由其他执行者接管，旧执行者的结果写不进去。
  - 处理过程中删除文档，不会留下残留数据。
- **文件与数据库的一致性**：先写文件、再提交数据库，数据库失败时删除已写的文件；进程崩溃留下的孤儿文件由每小时一次的对账清理。

本地运行时，文档入库需要在 `.env` 中填写 `AI_API_KEY`（通义 DashScope Key）；集成测试使用假的 Embedding 模型，不需要 Key。

## 知识库问答与会话（M3）

设计见 [ARCHITECTURE.md §4.3、§4.5、§4.6、§6.3、§6.4](ARCHITECTURE.md)，实现细节、实测数据和设计调整见 §4.10。

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| POST | `/knowledge-bases/{kbId}/search` | READER | 检索调试：`{query, topK?}` → 每个片段的相似度和是否达到问答阈值，不调用大模型 |
| POST / GET | `/conversations` | 登录 / 本人 | 创建 `{mode: "KB_QA", kbId?, title?}`（不传 kbId 时在有权访问的全部知识库中检索）；列表按更新时间倒序，可按 `keyword` 搜索标题 |
| GET / PATCH / DELETE | `/conversations/{id}` | 本人 | 详情、重命名、软删除 |
| GET | `/conversations/{id}/messages?beforeId=&size=` | 本人 | 游标分页：`{items, nextBeforeId}`，items 按时间正序 |
| POST | `/conversations/{id}/messages/stream` | 本人 | **主入口**，`{content}` → `text/event-stream` |
| POST | `/conversations/{id}/messages` | 本人 | 非流式版本，一次返回完整结果 |
| GET | `/admin/logs/llm-calls` | ADMIN | 模型调用日志（已脱敏），可按 traceId、用户、成败、时间过滤 |

一次提问的事件流：

```
event:meta       data:{"messageId":124,"userMessageId":123,"traceId":"…"}
event:retrieval  data:{"candidates":[{"index":1,"documentId":3,"filename":"sample-travel-policy.pdf","page":3,"score":0.743}, …]}
event:delta      data:{"text":"出差结束后30天内"}          （多次）
event:citations  data:{"mode":"NORMAL","citations":[{"index":1,"documentId":3,"page":3,"snippet":"…","score":0.743}],"invalidIndices":[]}
event:done       data:{"status":"OK","usage":{"prompt":987,"completion":12,"estimated":false},"latencyMs":919,"firstTokenMs":609}
:ping                                                     （等待期间每 15 秒一次心跳）
```

几条关键行为（都有集成测试覆盖，见 `KbQaIT`、`ChatLifecycleIT`）：

- **检索不到就拒答，不调用模型**：没有片段的相似度达到阈值（默认 0.5）时，直接返回固定的拒答文案，`citations.mode` 为 `NONE`。只在用户有权访问的知识库中检索。
- **引用一定指向本轮真实检索到的片段**：回答中的 `[n]` 由 `CitationParser` 确定性地映射到文档和页码；越界的编号（包括把“第九条”写成 `[9]`）放进 `invalidIndices`，不会映射到任何文档。回答没有合法引用时为 `FALLBACK`，把检索到的资料作为参考资料返回。
- **停止生成**：客户端断开连接即停止。上游的模型请求随之取消，已生成的部分保存下来，消息标记为 `INTERRUPTED`。模型调用失败时推送 `error` 事件，消息标记为 `FAILED`。
- **不在事务中调用模型**：问题和占位消息在一个短事务中保存，流式输出期间不占用数据库连接，结束时再用一个短事务写入最终结果。进程崩溃遗留的 `STREAMING` 消息 10 分钟后由扫描器收尾。
- **限流**：每个用户每分钟 20 次提问（`APP_RATE_LIMIT_PER_MINUTE`），超出返回 429。开始输出之前的错误（404、429 等）即使请求头是 `Accept: text/event-stream`，也按普通 JSON 返回。
- **脱敏**：`llm_call_log` 中的用户问题只保存脱敏后的前 200 字和 SHA-256，不记录 System Prompt、检索片段和完整回答。应用日志的消息和**异常堆栈**都按同样的规则脱敏（手机号、身份证号、银行卡号、邮箱、API Key、Bearer Token、JWT）。

**用真实模型校准**（2026-11-05，qwen-plus + text-embedding-v3，详见 ARCHITECTURE.md §4.10）：

- 检索阈值：相关问题的 top-1 相似度是 0.579–0.767，与文档领域无关的问题是 0.347–0.488，所以阈值保持 0.5。同一领域但文档没有覆盖的问题（例如在差旅制度上问年假）会越过阈值，由提示词让模型回答“根据现有资料无法回答”。
- 引用提示词：第一版提示词下，qwen-plus 经常把条款号写成引用编号。加入正反例，并在问题之后重复一次引用要求后，同一批问题（5 个问题各 3 次）的合法引用从 6/15 提高到 15/15。
- 停止生成：在第 5 个 delta 后断开，0.3 秒内消息变为 `INTERRUPTED`，调用日志记录为 `CLIENT_CLOSED`，耗时 0.8 秒（完整回答约 6 秒），说明上游请求确实被中止了。

本地运行时，问答同样需要在 `.env` 中填写 `AI_API_KEY`；集成测试使用脚本化的假 Chat 模型和假 Embedding 模型，不需要 Key。

## Agent 与工具（M4）

设计见 [ARCHITECTURE.md §4.4](ARCHITECTURE.md)，实现细节、Spring AI 2.0.2 的框架行为和实测数据见 §4.11。创建会话时传 `{"mode": "AGENT"}`，提问接口和事件协议与 M3 相同，多了 `tool` 事件。

| 工具 | USER | ANALYST | ADMIN | 说明 |
|---|---|---|---|---|
| `searchKnowledgeBase(query, kbId?)` | ✅ | ✅ | ✅ | 只检索有权访问的知识库；多次检索的引用编号全局分配 |
| `queryOrderStats(from, to, region?, groupBy)` | ❌ | ✅ 限本人区域 | ✅ | 订单数、成交订单数、成交金额、取消和退款数；按区域、品类、状态、天、月分组 |
| `countAnomalies(from, to, eventType?, minSeverity?, region?, groupBy)` | ❌ | ✅ 限本人区域 | ✅ | 异常事件数、未处理数、平均处理时长 |
| `generateReport(reportType, from, to, region?)` | ❌ | ✅ 限本人区域 | ✅ | 生成 Markdown 报告，每人每分钟最多 5 份 |

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | `/reports` · `/reports/{id}` | 本人 / ADMIN | 报告列表和详情（含 Markdown 原文）；不属于自己的报告返回 404 |
| GET | `/reports/{id}/download` | 本人 / ADMIN | 下载 `.md` 文件（`text/markdown`，中文文件名） |
| GET | `/admin/logs/tool-calls` | ADMIN | 工具调用审计，可按 traceId、用户、工具名、`decision`（ALLOWED / DENIED / INVALID / BUDGET_EXCEEDED）、时间过滤 |

一次带工具调用的提问：

```
event:meta   data:{"messageId":201,"userMessageId":200,"traceId":"…"}
event:tool   data:{"callId":"c1","name":"queryOrderStats","phase":"START","args":{"from":"2026-10-07","to":"2026-11-05","region":"EAST","groupBy":"CATEGORY"}}
event:tool   data:{"callId":"c1","name":"queryOrderStats","phase":"END","decision":"ALLOWED","ok":true,"latencyMs":24,"summary":"共 5 组，订单 299 单，成交金额 350594.19 元"}
event:delta  data:{"text":"华东地区最近30天…"}        （多次）
event:citations data:{"mode":"NONE","citations":[],"invalidIndices":[]}
event:done   data:{"status":"OK","usage":{"prompt":3844,"completion":380,"estimated":false},"latencyMs":10292,"firstTokenMs":1589,"unverifiedNumbers":[]}
```

几条关键行为（都有测试覆盖，见 `AgentIT`、`GuardedToolCallbackTest`、`ToolPolicyTest`、`DataScopeTest`）：

- **权限由服务端逐次校验**：
  - 每次工具调用都按固定顺序执行：服务端上下文 → 角色 → 参数校验 → 区域收敛 → 调用预算 → 执行 → 审计。
  - 身份来自服务端注入的上下文，模型给出的参数改变不了“我是谁、能看什么”。
  - 只向模型暴露有权限的工具，但这不是安全边界：模型按名字调用没有暴露的工具时，同样会被拒绝并记为 DENIED。
  - 被拒绝时把原因告诉模型，由模型向用户解释，不会报 500。
- **区域范围**：分析员请求自己区域之外的数据会被拒绝；不指定区域时只统计自己的区域。业务 Service 内部会再校验一次，绕过工具直接调用也拿不到范围之外的数据。
- **调用预算**：
  - 每轮最多执行 6 次工具调用（`APP_AGENT_TOOL_BUDGET`），超出后工具不再执行，模型基于已有结果作答。
  - 累计尝试达到 10 次时终止本轮，推送 `error`（`AGENT_ABORTED`），消息标记为 FAILED。
- **数字要有出处**：
  - 统计 SQL 是固定的，模型只能填日期和枚举；报告由 Java 根据 SQL 结果生成，模型只拿到几个关键数字。集成测试会断言报告里的数字等于直接执行 SQL 的结果。
  - 但模型仍然可能不调用工具，直接在回答里写数字。实测出现过一次：分析员问华北上月的异常事件，模型没有查询就回答“127 起”，真实是 56 起。
  - 所以 AGENT 模式在回答结束后还会做一次**数字核对**：回答中的每个数字都要能在本轮的工具结果、参数或用户问题中找到出处。比对时容忍千分位、四舍五入、“万”换算、中文数字；能由两个结果相除得到的占比、由分钟换算成的小时（378.06 分钟写成“约 6.3 小时”）也算有出处。找不到出处的数字会在回答末尾追加一段提示，并列在 `done` 事件的 `unverifiedNumbers` 中（`NumberGroundingTest`、`AgentIT`）。
  - 含有未核实数字的轮次不会作为历史带给模型。实测复用旧会话时，模型照着历史里自己编造的数字原样又说了一遍。
- **追问时也要检索**（2026-11-06 用户实测发现）：
  - 现象：先问一个制度问题、再追问相关主题时，模型说了“我将为您检索”却没有调用工具，直接编出一段制度并标上引用编号。用 qwen-plus 复现 5/5。
  - 原因：历史里只有“问题 + 回答”，模型以为助手是凭记忆作答的，追问时就照着做。
  - 修复：历史里保留当时的工具调用（参数已脱敏，结果只有摘要）后，复测 15/15 都先检索。只改提示词无效，而且让原本正常的场景退化，没有采用。
  - 兜底：本轮没有检索、回答里却标了引用编号时，在回答末尾追加“引用核对”提示，这一轮也不进入后续历史（`AgentIT`）。
- **可追溯**：
  - 每次工具调用都写 `tool_call_log`，参数已脱敏，结果只记摘要；工具步骤也随消息保存，历史消息里可以回看。
  - `llm_call_log` 记录本轮工具调用次数。
- **演示数据**：Flyway `V2` 确定性地生成 6000 条订单和 1500 条异常事件，覆盖迁移当天之前的 180 天，所以问“最近 30 天”总能查到数据。

**通义流式分片回放测试**（`TongyiStreamReplayTest`，不需要 Key 和 Docker）：本地模拟服务器回放通义风格的工具调用分片（续传分片带 `"id": ""`），走真实的 OpenAiChatModel。Spring AI 2.0.2 下通过；切到 2.0.0 会按预期失败，所以以后升级 Spring AI 时能第一时间发现回归。

**用真实模型实测**（2026-11-05，qwen-plus，详见 ARCHITECTURE.md §4.11）：
- 分析员问华东的数据：参数被正确收敛为 `region=EAST`，回答中的数字与工具结果一致。
- 分析员问华北：模型直接说明无权查看。
- 生成报告：报告数字与统计结果一致。
- 管理员对比各区域：一轮调用了两次工具。
- 第一版 Agent 提示词下，模型会不检索就说找不到、把条款号写成引用编号、给业务数据也标编号；同一组问题只有 2/6 合格。
  - 改了提示词后，当时测到 9/9。但后来复测同一提示词只有 6/9，错误集中在条款号，说明那次 9/9 不可复现。
  - 再把检索工具的结果改成与 KB_QA 相同的文本格式（“[n] 《文件》第 p 页”，引用要求放在最后）后，两组复测共 18/18。KB_QA 的 15/15 也复测了一遍，结果一致。
- 数字核对上线后复测：分析员追问华北 3 次，模型都只说明无权查看、没有给出数字；正常的统计、检索、跨区对比回答都没有被误标。

## 前端（M5）

代码在 [frontend/](../frontend/)：Vue 3 + TypeScript + Vite + Element Plus + Pinia。实现细节和浏览器实测记录见 [ARCHITECTURE.md §9.1](ARCHITECTURE.md)。

| 页面 | 功能 |
|---|---|
| 登录 | 登录后回到原来要访问的页面；Token 过期时自动刷新并重放请求，刷新失败回到登录页 |
| 智能对话 | 左：会话列表（按时间分组、搜索、重命名、删除）。中：流式回答、Markdown、可点击的 `[n]` 角标、工具调用时间线、数字核对标签、停止生成、失败或中断后重新提问。右：引用面板（文档、页码、相似度、片段），与角标联动高亮 |
| 知识库 | 列表、新建、编辑；文档表格（排队和处理中自动轮询，失败时显示原因并可重试）、拖拽上传、分片预览、检索调试、成员管理 |
| 分析报告 | 列表、Markdown 预览、下载 |
| 系统管理（ADMIN） | 用户（角色、区域范围、启用 / 停用）；调用日志（模型调用和工具调用，按 traceId 串联查看）；任务管理（重试 DEAD 任务）；统计概览 |

为前端补齐的后端接口（§6.5 设计过，此前没有实现）：

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | `/admin/tasks?status=&type=` | ADMIN | 异步任务列表，`lastError` 已脱敏 |
| POST | `/admin/tasks/{id}/retry` | ADMIN | 只能重试 DEAD 的任务：文档和任务一起回到排队状态，尝试次数清零；文档已经不需要重试时返回 409 |
| GET | `/admin/metrics/overview?from=&to=` | ADMIN | 按 Asia/Shanghai 自然日聚合：对话调用次数、成功率、P50 / P95 延迟、首字延迟、Token 用量、工具调用次数和越权次数 |

几个实现要点：
- **刷新 Token 必须串行**：Refresh Token 被重复使用会撤销整个令牌族（M1）。同一页面内只发一个刷新请求，其他请求等它的结果；多个标签页之间用 Web Locks 互斥；别处刚刷新过时直接用新令牌重放。浏览器实测：并发 3 个请求同时 401，只刷新了 1 次，3 个请求都重放成功。
- **SSE 绝不自动重连**：fetch-event-source 默认会在出错时重试，并在页面隐藏时断开、可见时重连，对提问接口来说都等于把问题再提交一遍，这两个行为都关掉了。开始输出之前的 401 会刷新一次 Token 再重新发起。
- **模型输出防 XSS**：markdown-it 关闭 HTML，渲染结果再经过 DOMPurify。`[n]` 角标的识别规则与后端 `CitationParser` 一致，代码里的不算。
- **流式渲染**：`delta` 用 `requestAnimationFrame` 合并，每帧最多重新渲染一次 Markdown。
- 前端不包含任何模型地址或 Key。菜单和路由按角色控制只是为了体验，权限以后端为准。

启动前端（先启动后端）：

```powershell
cd frontend
npm install
npm run dev        # 打开 http://localhost:5173，/api 会代理到 http://localhost:8080
```

后端不在 8080 端口时，在 `frontend/.env.development.local`（已被忽略）中写入 `VITE_PROXY_TARGET=http://localhost:<端口>`。演示账号与后端相同（admin / analyst / user，密码是 `.env` 中的 `DEMO_USER_PASSWORD`）。

## 部署（M6）

设计见 [ARCHITECTURE.md §8.1](ARCHITECTURE.md)。启动方法见 [README](../README.md#快速开始docker)。

| 文件 | 内容 |
|---|---|
| `Dockerfile` | 多阶段构建：`maven:3.9-eclipse-temurin-21` 打包，再按 Spring Boot 分层解压（依赖层约 180 MB、应用层约 2 MB，改代码只重建最后一层）；运行镜像 `eclipse-temurin:21-jre`，非 root 用户，带健康检查。可选 `MAVEN_MIRROR` |
| `frontend/Dockerfile` | `node:22-alpine` 中执行 `npm ci` 和 `npm run build`（含类型检查），产物放进 `nginx:1.28-alpine`（约 95 MB）。可选 `NPM_REGISTRY` |
| `frontend/nginx.conf` | 静态资源和前端路由回退；`/api` 反向代理到后端；流式接口关闭缓冲和压缩、读超时 300 秒；上传上限 21 MB；CSP 等安全响应头；带哈希的资源缓存一年，`index.html` 不缓存；`/actuator` 不对外 |
| `docker-compose.yml` | `backend`、`web` 属于 `app` profile：`.env.example` 中的 `COMPOSE_PROFILES=app` 让 `docker compose up` 启动全部服务；本地开发时 `docker compose up -d postgres redis` 只启动基础设施。启动顺序 postgres / redis 健康 → backend 健康 → web；后端不映射端口，只由 Nginx 访问 |

后端为部署做的调整：
- `server.forward-headers-strategy: native`：信任内网代理传来的 `X-Forwarded-For`，登录限流按真实客户端 IP 计算。只有直接连接方是内网地址时才采信，客户端直连时伪造不了。
- 流式响应加上 `X-Accel-Buffering: no`，即使代理没有单独配置，Nginx 也不会缓冲。
- 演示账号由环境变量 `APP_DEMO_SEED_USERS` 控制，Compose 部署中开启。

**验证（2026-11-06）**：在本机用隔离的 Compose 项目（单独的项目名、端口和全新数据卷）模拟新环境，没有动开发用的数据库。
- `docker compose up -d --build`：首次构建时后端 Maven 打包约 3 分钟；四个服务按依赖顺序变为 healthy。
- 响应头：CSP、`X-Content-Type-Options`、`X-Frame-Options`、`Referrer-Policy` 齐全，不暴露 Nginx 版本；前端路由（`/chat/12`）回退到 `index.html`；`/actuator/health` 经 Nginx 访问得到的是前端页面，不是后端接口。
- 流式输出经过 Nginx 逐段推送：0.04 秒收到 `meta`，0.54 秒收到第一段文字，43 段文字在 5.4 秒内陆续到达。
- 登录限流记录的是 Docker 网关地址（真实来源），而不是 Nginx 容器的地址。
- 用 Playwright（驱动本机 Edge）走完整条演示流程（真实的 qwen-plus 和 text-embedding-v3）：管理员建公开知识库并上传示例 PDF → 知识库问答并点击引用 → Agent 按区域统计订单 → 生成报告并预览 → 按 traceId 查看日志 → 华东分析员问华北（只说明无权查看，没有数字）。CSP 下页面没有任何控制台错误。README 中的截图就是这次生成的。

## M0 验收清单（2026-11-04 在本机执行完毕）

- [x] `.\mvnw.cmd test` 通过
- [x] `.\mvnw.cmd verify` 通过（`ApplicationIT`：Flyway 建表、pgvector 扩展、HNSW 索引、部分唯一索引、健康检查、统一响应）
- [x] `docker compose up -d` 后两个容器均为 healthy；应用启动后 `/actuator/health` 返回 UP
- [x] Spike S1–S5 已执行，`spikes/target/spike-report.md` 已回填到 ARCHITECTURE.md §2.2
- [x] 仓库中没有任何密钥（`.env` 已被 `.gitignore` 忽略；扫描结果只有 `change-me` 占位符）

## M1 验收清单（2026-11-04 在本机执行完毕）

- [x] 三种角色都可以登录（`AuthFlowIT`，以及演示账号的本机冒烟检查）
- [x] 401 / 403 / 404 语义符合约定：未登录 401、角色不足 403 且业务代码不被调用、已登录访问不存在的资源 404（接口切片测试 + `AuthFlowIT`）
- [x] 登出后旧 Access Token 立即失效，Refresh Token 一并撤销
- [x] 账号停用后立即失效：已签发的 Token 下一次请求就返回 401，且无法再刷新或登录
- [x] Refresh Token 轮换；旧令牌被重复使用时撤销整个令牌族
- [x] 登录限流：超出后返回 429 和 `Retry-After`（`RedisRateLimiterIT`，以及本机实测）

## M2 验收清单（2026-11-04 在本机执行完毕）

- [x] 入库：上传后轮询到 READY，chunk 数量和页码正确且不跨页；重复上传幂等；并发上传同一个文件只留下一条记录和一个文件（`DocumentIngestionIT`）
- [x] 补偿：数据库插入失败时删除已移动的文件；孤儿文件过了宽限期才清理，被引用的文件保留（`UploadCompensationIT`、`StorageReconcilerIT`）
- [x] 任务恢复：执行者死亡后被接管，旧执行者被 fencing 拒绝；提交事件丢失时任务仍被扫描器执行；前两次失败、第三次成功时 attempt 为 3（`TaskRecoveryIT`、`AsyncTaskRepositoryIT`）
- [x] 事务边界：Embedding 阻塞期间数据库活跃连接数为 0，且调用时不在事务中（`IngestionConsistencyIT`）
- [x] 删除竞争：入库处理过程中删除文档，最终没有残留 chunk，文件被删除（`IngestionConsistencyIT`）
- [x] 真实 PDF：54 页英文文档（1.5MB）使用通义 `text-embedding-v3`，9.3 秒变为 READY，共 186 个 chunk，没有重试

## M3 验收清单（2026-11-05 在本机执行完毕）

- [x] 引用合法性：`[1]`、`[1][2]`、`[1,2]`、`【1】` 等格式，越界、0、负数、重复、代码块内的编号（`CitationParserTest`）；合法编号映射到本轮检索到的文档、页码和片段，越界编号进入 `invalidIndices`；没有引用时进入 FALLBACK（`KbQaIT`）
- [x] 问知识库里没有的内容时拒答，且不调用模型（`KbQaIT`、`KbQaServiceTest`；真实模型下 0.13–0.23 秒返回，没有 Chat 调用记录）
- [x] 断开连接后消息为 INTERRUPTED，已生成的部分被保存，上游流被取消（`ChatLifecycleIT`；真实模型下 0.3 秒内完成）
- [x] 日志中没有原文敏感信息：上游报错回显了手机号、身份证号和邮箱时，`llm_call_log` 和应用日志（包括第三方库打印的异常堆栈）中都没有原文（`KbQaIT`、`SensitiveDataMaskerTest`、`MaskingMessageConverterTest`）
- [x] 其他：用户只能检索有权访问的知识库；别人的会话返回 404 且不写入任何数据；上游失败为 FAILED 并推送 error 事件；超时收尾、心跳、用量估算、游标分页；Spring AI observation 不记录内容（`ApplicationIT`）
- [x] 真实模型端到端：qwen-plus + text-embedding-v3 完成检索阈值校准和引用提示词校准（见上文）

## M4 验收清单（2026-11-05 在本机执行完毕）

- [x] 权限矩阵的参数化测试：角色 × 工具（`ToolPolicyTest`）、角色 × 区域（`DataScopeTest`）；GuardedToolCallback 的固定执行顺序（`GuardedToolCallbackTest`）
- [x] 越权集成测试：ANALYST（EAST）请求 NORTH、USER 调用没有暴露的 `countAnomalies`，都被拒绝，审计为 DENIED，业务仓储没有被调用（`AgentIT`，用 spy 验证）
- [x] 报告数字与 SQL 一致：报告中的订单数、成交订单数、成交金额、取消数和各品类订单数，等于直接执行 SQL 的结果；只有本人和 ADMIN 能查看（`AgentIT`）
- [x] 超出调用预算时安全收尾：第 7 次调用返回 BUDGET_EXCEEDED，模型仍然给出回答；第 10 次尝试终止本轮，消息为 FAILED，已执行的工具步骤被保存（`AgentIT`）
- [x] SSE 中能看到工具调用的完整过程：meta → tool START → tool END → delta… → citations → done；工具步骤随消息保存（`AgentIT`）
- [x] 通义流式分片回放测试通过（`TongyiStreamReplayTest`），并确认它在 Spring AI 2.0.0 下会失败
- [x] 真实模型端到端：qwen-plus 完成本区统计、拒绝越权、生成报告、跨区对比、知识库检索加引用；提示词和检索结果格式按实测改进（见上文）
- [x] 数字核对：模型不调用工具直接给出的数字会被标出，追加提示并写入 `unverifiedNumbers`；来自工具结果的数字、可由结果推算的占比和时长换算不会被误标；含未核实数字的轮次不进入后续历史（`NumberGroundingTest`、`AgentIT`，以及 qwen-plus 复测）
- [x] 追问时也要检索（2026-11-06 补充）：历史中保留工具调用后，“先问住宿标准、再问报销补助”从 0/5 变为 15/15 先检索；没有检索却标了引用编号时追加引用核对，这一轮不进入历史（`AgentIT`，以及 qwen-plus 复测）

## M5 验收清单（2026-11-05 在本机执行完毕）

- [x] 在浏览器里完成整条演示流程，不借助 Postman（qwen-plus + text-embedding-v3）：新建知识库 → 上传 PDF（自动轮询到“可用”）→ 分片预览、检索调试 → 知识库问答（流式输出、`[1]` 角标与引用面板联动）→ Agent 分析（工具调用时间线、数字核对）→ 生成报告、预览、下载 → 管理员查看调用日志（按 traceId 串联出 1 次模型调用和 3 次工具调用）
- [x] Token 过期：并发 3 个请求同时 401，只刷新 1 次，全部重放成功；流式接口 401 时刷新后重新发起（浏览器实测，以及 `http.spec.ts`、`chatStream.spec.ts`）
- [x] 停止生成：页面显示“已停止生成”；刷新页面后，历史消息保留已生成的部分和工具步骤，状态为中断
- [x] 角色：普通员工看不到系统管理菜单，直接访问后台地址进入 403 页；后台接口对非 ADMIN 返回 403（接口切片测试）
- [x] 前端单元测试 26 个（`npm test`）：XSS 过滤、引用角标规则、SSE 事件合并、串行刷新 Token、流式接口的错误处理、文件名解析、会话分组
- [x] 新增管理接口：DEAD 任务重试后文档变为可用、非 DEAD 任务返回 409、文档已被重新处理时整体回滚（`AdminTaskIT`）；统计按 Asia/Shanghai 自然日分组、百分位、合计行、补零（`MetricsQueryServiceIT`）；非 ADMIN 返回 403（接口切片测试）

## M6 验收清单（2026-11-06 在本机执行完毕）

- [x] 在隔离的 Compose 项目和全新数据卷上执行 `cp .env.example .env`（填写 JWT_SECRET、DEMO_USER_PASSWORD、AI_API_KEY）和 `docker compose up -d --build`，四个服务按依赖顺序变为 healthy，浏览器访问 `http://localhost:<WEB_PORT>` 即可使用
- [x] 浏览器中完整的演示流程（自动化脚本，真实模型）：上传 → 问答（流式、引用）→ Agent 分析（工具过程）→ 报告预览 → 管理员按 traceId 查看日志
- [x] 流式输出经过 Nginx 不被缓冲；安全响应头和 CSP 生效且页面没有报错；后端和 `/actuator` 不直接对外
- [x] README 改为面向读者的首页（架构图、截图、启动步骤、演示脚本、设计取舍、已知限制），各阶段的详细记录移到本文件
- 与原计划的差别：原定“只填 Key”就能运行，实际还要填写 JWT 签名密钥和演示账号密码。没有在示例配置里放默认值，避免有人直接用默认密钥部署到公网
