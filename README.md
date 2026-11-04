# 企业智能知识库与业务分析 Agent 平台

基于 Java 21 + Spring Boot 4.1 + Spring AI 2.0 的企业知识库问答（RAG）与业务分析 Agent 平台，作为 Java 后端工程实践作品。完整设计见 [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)。

> **当前进度：M0（工程骨架 + 技术验证）、M1（认证与权限）、M2（知识库与文档入库）、M3（知识库问答与会话）已完成（2026-11-05），下一阶段是 M4 Agent 与工具。** 本 README 只描述已经落地的内容，各阶段完成后再逐步补充。

## 当前状态

验证环境：Windows 10、JDK 21.0.4、Docker Desktop 29.9。版本：Spring Boot 4.1.2 + Spring AI 2.0.2（Spike S1 锁定）。

| 项目 | 状态 |
|---|---|
| 工程骨架、统一响应、全局异常、traceId | ✅ 以 local profile 启动后冒烟检查通过：health 为 `UP`，ping 返回统一响应和 `X-Trace-Id`；401/403/404/409/429 都返回带 traceId 的统一错误体 |
| Flyway V1（13 张表 + pgvector + HNSW 索引） | ✅ 在 Testcontainers（`pgvector/pgvector:pg17`）和 Compose 的 PostgreSQL 上都执行成功 |
| Docker Compose（PostgreSQL + pgvector、Redis） | ✅ 两个容器都为 healthy；停掉 Redis 时 health 变为 `DOWN`（503），恢复后回到 `UP` |
| 认证与权限（M1） | ✅ JWT 登录、Refresh Token 轮换与重用检测、登出黑名单、三种角色与区域范围、登录限流、用户管理接口；集成测试和本机冒烟检查都已通过 |
| 知识库与文档入库（M2） | ✅ 知识库与成员权限、PDF 上传（去重、补偿、对账）、`async_task` 任务表（并发领取、租约、fencing、重试）、中文切分、Embedding 入库、异步删除和重新处理；真实的 54 页 PDF 9.3 秒变为 READY |
| 知识库问答（M3） | ✅ 检索调试接口、KB_QA 流程（检索不到时拒答且不调用模型）、SSE 事件协议、引用解析、会话与消息状态机、调用日志与脱敏；已用真实的 qwen-plus 和 text-embedding-v3 校准检索阈值和引用提示词 |
| 测试 | ✅ `mvnw verify` 通过：单元 + 接口切片 + 架构测试 235 个，集成测试 65 个，不需要 API Key |
| Spike S1–S5 | ✅ 已在真实通义接口上执行（14 个用例全部通过），结论见 [ARCHITECTURE.md §2.2](docs/ARCHITECTURE.md) |

### M0 技术验证的主要结论

完整结论、证据和选定方案见 [ARCHITECTURE.md §2.2](docs/ARCHITECTURE.md)。这里只列出会直接影响后续编码的几条：

- **Spring AI 不得低于 2.0.2。** 2.0.0 在通义兼容模式下“流式 + 工具调用”会失败：通义的续传分片带空字符串 `id`，2.0.0 的分片合并逻辑处理不了。
- **通义 base-url 必须以 `/v1` 结尾**（`https://dashscope.aliyuncs.com/compatible-mode/v1`）。Spring AI 2.0 底层换成了 OpenAI 官方 Java SDK，照搬 1.x 的写法会得到 404。
- **Embedding 一次最多提交 10 条**，入库时按批切分。
- 工具在 `stream()` 模式下运行在 Reactor 线程上，读不到 `SecurityContext`，身份要通过 `ToolContext` 显式传递；在工具包装层抛出异常可以终止整轮工具调用。
- MVC 直接返回 `Flux<ServerSentEvent>` 与 Spring Security 兼容，客户端断开后上游会被取消。

## 认证与权限（M1）

设计细节见 [ARCHITECTURE.md §4.8](docs/ARCHITECTURE.md)。所有接口都以 `/api/v1` 为前缀，除登录、刷新、ping 和 health 外都需要 `Authorization: Bearer <accessToken>`。

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

设计见 [ARCHITECTURE.md §4.1–§4.3](docs/ARCHITECTURE.md)，实现细节和设计调整见 §4.9。

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

设计见 [ARCHITECTURE.md §4.3、§4.5、§4.6、§6.3、§6.4](docs/ARCHITECTURE.md)，实现细节、实测数据和设计调整见 §4.10。

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

## 环境要求

- JDK 21
- Maven：不需要单独安装，项目自带 Maven Wrapper（`mvnw.cmd`），首次运行时会自动下载 Maven 3.9.11
- Docker Desktop（运行基础设施和集成测试时需要）

## 快速开始

```powershell
# 1. 准备配置：复制模板，修改其中的密码，并填写 JWT_SECRET（至少 32 字节，可用 openssl rand -base64 48 生成）
#    和 DEMO_USER_PASSWORD（演示账号密码，至少 8 位）
copy .env.example .env

# 2. 启动 PostgreSQL（含 pgvector）和 Redis
docker compose up -d
docker compose ps        # 两个服务都应显示 healthy

# 3. 启动后端（local profile 会读取项目根目录的 .env，并创建演示账号）
.\mvnw.cmd spring-boot:run -Dspring-boot.run.profiles=local

# 4. 冒烟检查
curl http://localhost:8080/actuator/health
curl -i http://localhost:8080/api/v1/system/ping
```

登录并查看当前用户（PowerShell，把 `<密码>` 换成 `.env` 里的 `DEMO_USER_PASSWORD`）：

```powershell
$login = Invoke-RestMethod -Method Post http://localhost:8080/api/v1/auth/login -ContentType 'application/json' -Body '{"username":"analyst","password":"<密码>"}'
Invoke-RestMethod http://localhost:8080/api/v1/users/me -Headers @{ Authorization = "Bearer $($login.data.accessToken)" }
```

### 手动测试接口

[http/kb-agent.http](http/kb-agent.http) 是 IntelliJ IDEA 自带 HTTP Client 的请求脚本，覆盖登录、知识库、上传 PDF、查看入库进度和切分结果、权限、检索调试、问答（流式和非流式）、拒答、历史消息、调用日志、刷新、删除、登出。配套的中文示例文档是 [http/sample-travel-policy.pdf](http/sample-travel-policy.pdf)（虚构的差旅制度）。

1. 启动基础设施和后端（见上文）。文档入库和问答都要调用通义，需要先在 `.env` 中填写 `AI_API_KEY`。
2. 在 `http/` 目录创建 `http-client.private.env.json`（已被 `.gitignore` 忽略）：`{ "local": { "password": "<.env 中的 DEMO_USER_PASSWORD>" } }`。
3. 打开 `kb-agent.http`，右上角的环境选 `local`，从上到下依次点击每个请求左侧的 ▶。

### 在 IntelliJ IDEA 中运行

使用项目自带的运行配置 **KbAgentApplication (local)**（位于 `.run/`，IntelliJ 会自动识别），它已经激活了 `local` profile。如果直接点 `main` 方法旁边的运行按钮，IntelliJ 生成的配置不带 profile，`.env` 不会被读取，启动时会报：

```
The server requested SCRAM-based authentication, but no password was provided.
```

这时在 Run → Edit Configurations 中把 Active profiles 设为 `local` 即可。`.env` 只在 local profile 下导入，这样测试不会读到本地配置。

### 常见问题：Windows 上容器端口绑定失败

**现象**：`docker compose up -d` 报错 `ports are not available ... bind: An attempt was made to access a socket in a way forbidden by its access permissions`，容器一直停在 `Created` 状态。

**原因**：Hyper-V / WinNAT 会动态保留一些端口段，保留段内的端口无法绑定。用下面的命令查看当前的保留段：

```powershell
netsh interface ipv4 show excludedportrange protocol=tcp
```

**解决**：在 `.env` 里把冲突的端口（`REDIS_PORT` 或 `POSTGRES_PORT`）改成不在保留段内的值，例如 `REDIS_PORT=16379`。Compose 和 local profile 都从 `.env` 读取端口，其他文件不需要改。用 `netsh int ipv4 show dynamicport tcp` 可以查看动态端口范围，选它之外的端口，以后就不会再被保留。

## 测试

```powershell
.\mvnw.cmd test      # 单元测试、接口切片测试、架构测试，不需要 Docker
.\mvnw.cmd verify    # 额外运行 *IT 集成测试，需要 Docker（Testcontainers 会自动拉起 pgvector 和 Redis）
```

`verify` 不需要任何 API Key。测试使用 `test` profile（`src/test/resources/application-test.yml`），其中的 JWT 密钥只用于测试。

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

## 目录结构

```
.
├── docs/ARCHITECTURE.md        架构设计（v0.2.5）
├── docker-compose.yml          基础设施
├── .env.example                环境变量模板
├── pom.xml
├── src/main/java/com/example/kbagent
│   ├── common/api              统一响应 ApiResponse、分页 PageResponse、游标分页 CursorPage
│   ├── common/error            错误码、业务异常、全局异常处理
│   ├── common/ratelimit        Redis 令牌桶限流
│   ├── common/trace            traceId 过滤器
│   ├── common/vector           pgvector 字面量转换
│   ├── config                  安全配置（JWT 资源服务器）、定时调度、Clock
│   ├── security                JWT 签发、Refresh Token 轮换、登出黑名单、当前用户加载、登录/刷新/登出
│   ├── user                    用户、角色、区域范围、用户管理接口、演示账号初始化
│   ├── knowledge               知识库、成员、访问级别判断（KbPermissionService）
│   ├── document                文档上传、删除、重新处理；入库流水线（PDF 解析、切分、Embedding）
│   ├── storage                 文件存储（本地实现）与孤儿文件对账
│   ├── task                    通用异步任务：扫描、领取、租约、fencing、重试
│   ├── retrieval               带权限过滤的向量检索、检索调试接口
│   ├── chat                    会话与消息、KB_QA 流程（SSE）、引用解析、提示词、超时收尾
│   ├── observability           脱敏（SensitiveDataMasker、Logback 转换器）、模型调用日志、日志查询与清理
│   └── system/web              冒烟接口
├── src/main/resources
│   ├── application.yml
│   ├── application-local.yml   本地开发 profile（读取 .env，创建演示账号）
│   ├── logback-spring.xml      应用日志脱敏（消息和异常堆栈）
│   └── db/migration/V1__init.sql
├── src/test/java/...           单元测试、接口切片测试、ArchUnit、集成测试
├── http/                       手动测试脚本（IntelliJ HTTP Client）和示例 PDF
└── spikes/                     M0 技术验证（独立 Maven 工程，结论见 ARCHITECTURE.md §2.2）
```
