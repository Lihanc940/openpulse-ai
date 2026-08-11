# Java 第四步：提供本地分析 API

## 任务定位

- 负责人：Java 负责人
- 建议分支：`feat/platform-local-analysis-api`
- 所属阶段：P1 本地最小闭环收尾
- 前置任务：C++ real FileScanner、Java report parser、Java analyzer process runner 已合并到 `main`

本任务给已经完成的 `AnalyzerProcessRunner` 增加一个最小 HTTP 入口，让本机开发者可以通过 API 提交一个本地仓库目录并得到分析报告。

这不是面向公网用户的正式接口，也不是 GitHub 仓库下载任务，并且不接数据库。它只在显式启用时把“本地目录 -> C++ analyzer -> JSON report -> Java response”这条链路开放给本机开发者，作为进入 Week 2 的验收门。

## 为什么先做本地分析 API

当前系统已经有内部能力：

```text
AnalyzerProcessRunner.analyze(Path repositoryPath)
  -> 启动 C++ analyzer
  -> 读取 report.json
  -> 返回 AnalyzerReport
```

但还没有外部入口。下一步做 GitHub URL 和下载前，先用本地 API 验证：

- HTTP 请求参数如何校验。
- 成功报告如何返回。
- analyzer 失败如何变成稳定错误响应。
- 未来 GitHub 分析任务可以复用同一套用例边界。

## 开始前阅读

- `README.md`
- `docs/00-glossary.md`
- `docs/02-analyzer-json-protocol.md`
- `docs/04-architecture.md`
- `docs/06-roadmap.md`
- `docs/08-week-2-checklist.md`
- `docs/tasks/03-java-analyzer-process-runner.md`
- `openpulse-platform/src/main/java/io/github/lihanc940/openpulse/integration/analyzer/AnalyzerProcessRunner.java`

## 本步骤需要理解的名词

新对话在使用这些概念前，应先用简单语言解释：

- **HTTP 接口**：前端或工具通过 URL 调用后端功能的入口。
- **请求体（Request Body）**：客户端发给后端的 JSON 数据。
- **响应体（Response Body）**：后端返回给客户端的 JSON 数据。
- **DTO**：专门用于 API 输入输出的数据对象，避免直接暴露内部实现细节。
- **Controller**：Spring MVC 中接收 HTTP 请求的类。
- **Application Service**：编排一个业务用例的类，例如“分析一个本地仓库”。
- **错误响应**：失败时返回给调用方的稳定 JSON，包含错误类型和可读消息。
- **功能开关（Feature Flag）**：通过配置决定某项功能是否启用。本任务的本地路径接口默认关闭，防止公网调用者读取或分析服务器上的任意目录。
- **异常处理器（Exception Handler）**：集中把 Java 异常转换成 HTTP 状态码和安全错误响应，避免 Controller 里堆积大量 `try/catch`。
- **MockMvc**：Spring 测试 HTTP Controller 的工具，不需要真的启动浏览器。

## 建议代码边界

新增业务模块：

```text
openpulse-platform/src/main/java/io/github/lihanc940/openpulse/analysis/
  api/
    LocalAnalysisController.java
    LocalAnalysisRequest.java
    AnalysisErrorResponse.java
    LocalAnalysisExceptionHandler.java
  application/
    LocalAnalysisService.java
```

职责建议：

- `LocalAnalysisController`：只处理 HTTP、参数校验和响应状态码。
- `LocalAnalysisRequest`：保存请求字段，例如 `repositoryPath`。
- `LocalAnalysisService`：把请求转换成 `Path`，调用 `AnalyzerProcessRunner`。
- `AnalysisErrorResponse`：提供稳定错误格式。
- `LocalAnalysisExceptionHandler`：集中映射 runner 失败类型、非法路径和无法读取的 JSON 请求。
- `AnalyzerProcessRunner`：继续只负责进程调用，不关心 HTTP。

不要把 Controller 直接写成一个很长的方法，也不要在 Controller 中创建 `ProcessBuilder`。

## API 设计

建议路径：

```http
POST /api/v1/analysis/local
```

该接口必须受配置开关保护：

```yaml
openpulse:
  analysis:
    local-api-enabled: ${OPENPULSE_LOCAL_ANALYSIS_API_ENABLED:false}
```

- 默认值必须是 `false`。
- 只有本机开发或验收时才设置 `OPENPULSE_LOCAL_ANALYSIS_API_ENABLED=true`。
- 可以使用 `@ConditionalOnProperty` 让 Controller 只在开关开启时注册。
- 开关关闭时，请求该路径应得到 `404 Not Found`。
- 不要仅依赖“大家不会部署它”或前端不展示入口；服务端必须执行这个边界。

请求：

```json
{
  "repositoryPath": "C:\\Users\\demo\\repositories\\sample project"
}
```

成功响应：

```http
200 OK
Content-Type: application/json
```

响应体可以先直接返回 `AnalyzerReport`。本任务暂时不需要包装任务 ID、数据库 ID 或异步状态。

失败响应建议：

```json
{
  "error": "INVALID_REPOSITORY_PATH",
  "message": "Repository path is invalid or unavailable."
}
```

## 状态码建议

先保持简单稳定：

- `200 OK`：分析成功。
- `400 Bad Request`：JSON 无法读取、请求字段为空、路径语法非法、路径不存在或路径不是目录。
- `503 Service Unavailable`：analyzer 可执行程序无法启动。
- `502 Bad Gateway`：analyzer 参数或仓库错误、扫描失败、未知退出码、报告缺失或报告不合法。
- `504 Gateway Timeout`：analyzer 超时。
- `500 Internal Server Error`：临时目录创建失败、中断或清理失败等平台内部错误。

必须显式覆盖现有 `AnalyzerExecutionFailure` 的每一种类型，避免新增或遗漏的失败类型意外返回不同格式。建议映射如下：

| 失败类型 | HTTP 状态 |
| --- | --- |
| `INVALID_REPOSITORY_PATH` | `400` |
| `START_FAILED` | `503` |
| `TIMEOUT` | `504` |
| `INVALID_ARGUMENTS`、`REPOSITORY_NOT_FOUND`、`SCAN_FAILED`、`REPORT_OUTPUT_FAILED`、`UNKNOWN_EXIT_CODE`、`REPORT_MISSING`、`REPORT_INVALID` | `502` |
| `TEMPORARY_DIRECTORY_CREATION_FAILED`、`INTERRUPTED`、`CLEANUP_FAILED` | `500` |

客户端错误响应不得包含服务器绝对路径、stdout、stderr、runner 诊断摘要或异常堆栈。需要排查问题的有界诊断可以记录到服务端日志，但不能原样返回给调用方。

## 参数校验要求

- `repositoryPath` 不能为空。
- `Path.of(...)` 导致的路径语法异常应转换成稳定的 `400` 响应。
- 不在 API 层接受 GitHub URL，本任务只接受本地目录路径。
- 不在项目配置中写个人电脑绝对路径。
- 不自动创建用户传入的仓库目录。
- 不允许把普通文件当作仓库目录。

路径是否存在和是否是目录可以继续由 `AnalyzerProcessRunner` 做最终校验，API 层只做基本请求校验。

## 自动化测试要求

测试不依赖真实 C++ analyzer。应使用 mock 或测试替身覆盖 Controller 和 Service 行为。

至少覆盖：

### 1. 接口默认关闭

- 不启用配置开关时，不注册本地分析 Controller。
- 请求接口得到 `404`，且 runner 未被调用。

### 2. 成功返回报告

- 测试显式启用本地 API 配置。
- 请求路径中可以包含空格。
- `LocalAnalysisService` 调用 runner。
- 响应状态为 `200`。
- 响应 JSON 包含 `protocolVersion`、`taskId`、`summary`。

### 3. 非法请求

- 覆盖无法读取的 JSON、`repositoryPath` 为空字符串或字段缺失。
- 响应状态为 `400`。
- 错误类型稳定。

### 4. 非目录或不存在路径

- runner 抛出 `AnalyzerExecutionFailure.INVALID_REPOSITORY_PATH`。
- 响应状态为 `400`。

### 5. analyzer 无法启动或超时

- runner 抛出 `AnalyzerExecutionFailure.START_FAILED` 时响应状态为 `503`。
- runner 抛出 `AnalyzerExecutionFailure.TIMEOUT`。
- 响应状态为 `504`。

### 6. analyzer 执行失败

- 至少覆盖 `SCAN_FAILED` 或 `REPORT_INVALID`。
- 响应状态为 `502`。
- 响应不泄露绝对路径、stdout、stderr、诊断摘要或异常堆栈。

### 7. 失败类型映射完整

- 对现有每一种 `AnalyzerExecutionFailure` 都有明确状态码断言，或通过参数化测试覆盖。
- 未知异常保持统一 `500` 格式，不把异常详情返回给客户端。

### 8. 原有测试继续通过

完整执行：

```powershell
.\openpulse-platform\mvnw.cmd clean verify
```

现有 23 个测试必须继续通过。

## 真实本地验证

如果当前机器已有真实 `openpulse-analyzer.exe`，可以手动验证：

```powershell
$env:OPENPULSE_ANALYZER_EXECUTABLE = "C:\path\to\openpulse-analyzer.exe"
$env:OPENPULSE_LOCAL_ANALYSIS_API_ENABLED = "true"
.\mvnw.cmd spring-boot:run
```

再用 Postman 或 PowerShell 调用：

```powershell
Invoke-RestMethod `
  -Method Post `
  -Uri "http://localhost:8080/api/v1/analysis/local" `
  -ContentType "application/json" `
  -Body '{"repositoryPath":"C:\\path\\to\\repo"}'
```

如果真实二进制不可用，不要伪造结果。在验收报告中写清楚“自动化测试通过，真实 C++ API 冒烟验证待 C++ 负责人配合”。

## 明确不做

本任务禁止提前加入：

- GitHub URL 解析。
- GitHub API 调用。
- 仓库下载或 clone。
- MySQL、实体表和迁移脚本。
- 异步任务队列。
- 任务状态轮询。
- 健康评分。
- AI 调用。
- 前端页面。
- Docker 或 GitHub Actions。
- 用户认证。

## 完成标准

- 新增本地分析 HTTP 入口。
- 接口受默认关闭的配置开关保护。
- API 层和 runner 职责分离。
- 成功和失败响应格式稳定。
- 客户端响应不泄露服务器路径、进程输出或异常详情。
- 错误状态码有测试覆盖。
- 自动化测试不依赖 C++ 工具链、个人路径或网络。
- `.\openpulse-platform\mvnw.cmd clean verify` 通过。
- 没有构建产物、可执行文件、密钥或个人路径进入 Git。
- 创建范围单一的实现 commit。
- 先不要 push，回主线对话验收。

## 回主线对话时提交的信息

```text
分支：
commit：
新增 API：
请求 JSON：
成功响应：
失败响应和状态码：
自动化测试场景：
全部测试数量和结果：
真实 C++ API 冒烟验证：
我理解的新名词：
遇到的问题：
```

## 新对话启动提示词

在相同项目工作区创建新对话后发送：

```text
请先阅读 README.md、docs/00-glossary.md、docs/02-analyzer-json-protocol.md、
docs/04-architecture.md、docs/06-roadmap.md、docs/08-week-2-checklist.md、
docs/tasks/03-java-analyzer-process-runner.md、
docs/tasks/04-java-local-analysis-api.md，
以及现有 AnalyzerProcessRunner、AnalyzerReportReader。

我是 Java 初学者，请严格完成“Java 第四步：提供本地分析 API”。
从最新 main 创建 feat/platform-local-analysis-api 分支。
每使用一个新名词前先用简单语言解释，再执行对应操作并验证。
不要加入任务书明确禁止的功能。
本任务只做本地目录分析 API，不做 GitHub URL、下载、数据库、AI 或前端。
本地分析 API 必须由配置显式启用且默认关闭，错误响应不得泄露服务器路径、进程输出或异常详情。
自动化测试不得依赖真实 C++ analyzer、个人路径、网络或 Windows 专属脚本。
完成 Controller、DTO、Application Service、错误响应映射和测试。
最后运行 .\openpulse-platform\mvnw.cmd clean verify，
创建范围单一的本地实现 commit，但不要 push 或创建 PR。
真实 C++ 二进制不可用时如实记录，不要伪造冒烟验证结果。
最后按任务书格式生成回主线对话的验收报告。
```
