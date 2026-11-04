# 企业智能知识库与业务分析 Agent 平台

基于 Java 21 + Spring Boot 4.1 + Spring AI 2.0 的企业知识库问答（RAG）与业务分析 Agent 平台，作为 Java 后端工程实践作品。完整设计见 [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)。

> **当前进度：M0（工程骨架 + 技术验证）已完成（2026-11-04），下一阶段是 M1 认证与权限。** 本 README 只描述已经落地的内容，各阶段完成后再逐步补充。

## 当前状态

验证环境：Windows 10、JDK 21.0.4、Docker Desktop 29.9。版本：Spring Boot 4.1.2 + Spring AI 2.0.2（Spike S1 锁定）。

| 项目 | 状态 |
|---|---|
| 工程骨架、统一响应、全局异常、traceId | ✅ 编译通过；以 local profile 启动后冒烟检查通过：health 为 `UP`，ping 返回统一响应和 `X-Trace-Id`，未知路径返回统一的 404 错误体 |
| Flyway V1（13 张表 + pgvector + HNSW 索引） | ✅ 在 Testcontainers（`pgvector/pgvector:pg17`）和 Compose 的 PostgreSQL 上都执行成功 |
| Docker Compose（PostgreSQL + pgvector、Redis） | ✅ 两个容器都为 healthy；停掉 Redis 时 health 变为 `DOWN`（503），恢复后回到 `UP` |
| 单元测试、ArchUnit 架构测试、集成测试 | ✅ `mvnw verify` 通过：单元 + 架构测试 15 个，集成测试 6 个，不需要 API Key |
| Spike S1–S5 | ✅ 已在真实通义接口上执行（14 个用例全部通过），结论见 [ARCHITECTURE.md §2.2](docs/ARCHITECTURE.md) |

### M0 技术验证的主要结论

完整结论、证据和选定方案见 [ARCHITECTURE.md §2.2](docs/ARCHITECTURE.md)。这里只列出会直接影响后续编码的几条：

- **Spring AI 不得低于 2.0.2。** 2.0.0 在通义兼容模式下“流式 + 工具调用”会失败：通义的续传分片带空字符串 `id`，2.0.0 的分片合并逻辑处理不了。
- **通义 base-url 必须以 `/v1` 结尾**（`https://dashscope.aliyuncs.com/compatible-mode/v1`）。Spring AI 2.0 底层换成了 OpenAI 官方 Java SDK，照搬 1.x 的写法会得到 404。
- **Embedding 一次最多提交 10 条**，入库时按批切分。
- 工具在 `stream()` 模式下运行在 Reactor 线程上，读不到 `SecurityContext`，身份要通过 `ToolContext` 显式传递；在工具包装层抛出异常可以终止整轮工具调用。
- MVC 直接返回 `Flux<ServerSentEvent>` 与 Spring Security 兼容，客户端断开后上游会被取消。

## 环境要求

- JDK 21
- Maven：不需要单独安装，项目自带 Maven Wrapper（`mvnw.cmd`），首次运行时会自动下载 Maven 3.9.11
- Docker Desktop（运行基础设施和集成测试时需要）

## 快速开始

```powershell
# 1. 准备配置：复制模板，然后修改其中的密码
copy .env.example .env

# 2. 启动 PostgreSQL（含 pgvector）和 Redis
docker compose up -d
docker compose ps        # 两个服务都应显示 healthy

# 3. 启动后端（local profile 会读取项目根目录的 .env）
.\mvnw.cmd spring-boot:run -Dspring-boot.run.profiles=local

# 4. 冒烟检查
curl http://localhost:8080/actuator/health
curl -i http://localhost:8080/api/v1/system/ping
```

### 常见问题：Windows 上容器端口绑定失败

**现象**：`docker compose up -d` 报错 `ports are not available ... bind: An attempt was made to access a socket in a way forbidden by its access permissions`，容器一直停在 `Created` 状态。

**原因**：Hyper-V / WinNAT 会动态保留一些端口段，保留段内的端口无法绑定。用下面的命令查看当前的保留段：

```powershell
netsh interface ipv4 show excludedportrange protocol=tcp
```

**解决**：在 `.env` 里把冲突的端口（`REDIS_PORT` 或 `POSTGRES_PORT`）改成不在保留段内的值，例如 `REDIS_PORT=16379`。Compose 和 local profile 都从 `.env` 读取端口，其他文件不需要改。用 `netsh int ipv4 show dynamicport tcp` 可以查看动态端口范围，选它之外的端口，以后就不会再被保留。

## 测试

```powershell
.\mvnw.cmd test      # 单元测试 + 架构测试，不需要 Docker
.\mvnw.cmd verify    # 额外运行 *IT 集成测试，需要 Docker（Testcontainers 会自动拉起 pgvector 和 Redis）
```

`verify` 不需要任何 API Key。

## M0 验收清单（2026-11-04 在本机执行完毕）

- [x] `.\mvnw.cmd test` 通过
- [x] `.\mvnw.cmd verify` 通过（`ApplicationIT`：Flyway 建表、pgvector 扩展、HNSW 索引、部分唯一索引、健康检查、统一响应、404）
- [x] `docker compose up -d` 后两个容器均为 healthy；应用启动后 `/actuator/health` 返回 UP
- [x] Spike S1–S5 已执行，`spikes/target/spike-report.md` 已回填到 ARCHITECTURE.md §2.2
- [x] 仓库中没有任何密钥（`.env` 已被 `.gitignore` 忽略；扫描结果只有 `change-me` 占位符）

## 目录结构

```
.
├── docs/ARCHITECTURE.md        架构设计（v0.2.2）
├── docker-compose.yml          基础设施
├── .env.example                环境变量模板
├── pom.xml
├── src/main/java/com/example/kbagent
│   ├── common/api              统一响应 ApiResponse
│   ├── common/error            错误码、业务异常、全局异常处理
│   ├── common/trace            traceId 过滤器
│   └── system/web              冒烟接口
├── src/main/resources
│   ├── application.yml
│   ├── application-local.yml   本地开发 profile（读取 .env）
│   └── db/migration/V1__init.sql
├── src/test/java/...           单元测试、ArchUnit、集成测试
└── spikes/                     M0 技术验证（独立 Maven 工程，结论见 ARCHITECTURE.md §2.2）
```
