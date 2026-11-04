# M0 技术验证（Spike S1–S5）

这是一个独立的 Maven 工程，用来在正式编码前验证 `docs/ARCHITECTURE.md` §2.2 中的假设。它与主工程互不影响。

**状态：已于 2026-11-04 在 Spring Boot 4.1.2 + Spring AI 2.0.2 上执行，14 个用例全部通过，结论已回填到 [ARCHITECTURE.md §2.2](../docs/ARCHITECTURE.md)。**

## 运行

在项目根目录执行（Windows PowerShell 示例）：

```powershell
# S1：查看可升级的版本（只读，不修改 pom）
.\mvnw.cmd -f spikes/pom.xml versions:display-parent-updates versions:display-property-updates
# S1：检查依赖树中的版本冲突
.\mvnw.cmd -f spikes/pom.xml dependency:tree "-Dverbose"

# S5：SSE + Spring Security，不需要 API Key
.\mvnw.cmd -f spikes/pom.xml test -Dtest=S5SseSecuritySpikeTest

# S2 / S3 / S4：需要通义 DashScope Key（会产生少量真实调用费用，十几次短请求）
$env:AI_API_KEY = "sk-xxxx"          # 已经设置过 DASHSCOPE_API_KEY 的话：$env:AI_API_KEY = $env:DASHSCOPE_API_KEY
.\mvnw.cmd -f spikes/pom.xml test -Dtest=AiSpikeTest
```

未设置 `AI_API_KEY` 时，`AiSpikeTest` 会自动跳过，不会报错。

`versions:display-*` 只显示最新版本（可能是 RC），某条补丁线内的最新版本要查 Maven Central 的 `maven-metadata.xml`。

## 结果

每项观察结果会追加写入 `spikes/target/spike-report.md`，同时打印在控制台，形如 `[SPIKE S4] ...`。报告是追加写入的，重新验证前可以先删除旧报告。

| 编号 | 验证内容 | 对应测试 |
|---|---|---|
| S1 | Boot / Spring AI 的最新补丁版本；依赖树冲突 | `versions:*`、`dependency:tree` 命令输出 |
| S2 | 通义 `call()` 和 `stream()` 能否拿到 usage；流式模式下能否调用工具 | `AiSpikeTest#s2*` |
| S3 | Embedding 维度参数是否生效；一次请求最多能提交多少条文本 | `AiSpikeTest#s3*`（可用 `AI_EMBEDDING_DIMENSIONS=512` 验证维度参数） |
| S4 | 流式和非流式模式下 `ToolContext` 能否传到工具、工具在哪个线程执行；工具方法内部异常和包装层异常是否会中断调用 | `AiSpikeTest#s4*` |
| S5 | MVC 返回 `Flux<ServerSentEvent>` 时，与 Spring Security 是否兼容；客户端断开后上游 Flux 是否被取消；流内能否读到 SecurityContext | `S5SseSecuritySpikeTest` |

## 什么时候需要重新运行

升级 Spring Boot 或 Spring AI 版本、更换模型服务商或模型时，先在本工程里改版本并重新运行全部用例，再改主工程。
重点复查 §2.2 中 S2 的三个问题（base-url 约定、属性是否仍然存在、通义流式工具调用分片的合并）。
