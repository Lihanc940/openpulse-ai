# Java 第四步：提供本地分析 API

## 任务定位

- 负责人：Java 负责人
- 建议分支：`feat/platform-local-analysis-api`
- 所属阶段：P1 本地最小闭环收尾
- 前置任务：C++ real FileScanner、Java report parser、Java analyzer process runner 已合并到 `main`

本任务给已经完成的 `AnalyzerProcessRunner` 增加一个最小 HTTP 入口，让调用方可以通过 API 提交一个本地仓库目录并得到分析报告。

这不是 GitHub 仓库下载任务，也不接数据库。它只把“本地目录 -> C++ analyzer -> JSON report -> Java response”这条链路暴露出来，作为进入 Week 2 的验收门。

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
- **MockMvc**：Spring 测试 HTTP Controller 的工具，不需要真的启动浏览器。

## 建议代码边界

新增业务模块：

```text
openpulse-platform/src/main/java/io/github/lihanc940/openpulse/analysis/
  api/
    LocalAnalysisController.java
    LocalAnalysisRequest.java
    AnalysisErrorResponse.java
  application/
    LocalAnalysisService.java
```

职责建议：

- `LocalAnalysisController`：只处理 HTTP、参数校验和响应状态码。
- `LocalAnalysisRequest`：保存请求字段，例如 `repositoryPath`。
- `LocalAnalysisService`：把请求转换成 `Path`，调用 `AnalyzerProcessRunner`。
- `AnalysisErrorResponse`：提供稳定错误格式。
- `AnalyzerProcessRunner`：继续只负责进程调用，不关心 HTTP。

不要把 Controller 直接写成一个很长的方法，也不要在 Controller 中创建 `ProcessBuilder`。

## API 设计

建议路径：

```http
POST /api/v1/analysis/local
```

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
  "message": "Analyzer repository path does not exist: ..."
}
```

## 状态码建议

先保持简单稳定：

- `200 OK`：分析成功。
- `400 Bad Request`：请求字段为空、路径不存在、路径不是目录。
- `502 Bad Gateway`：analyzer 启动失败、非零退出码、报告缺失或报告不合法。
- `504 Gateway Timeout`：analyzer 超时。
- `500 Internal Server Error`：临时目录创建失败或清理失败等平台内部错误。

错误响应不要包含完整 stdout/stderr，只允许包含 runner 已经截断的诊断摘要。

## 参数校验要求

- `repositoryPath` 不能为空。
- 不在 API 层接受 GitHub URL，本任务只接受本地目录路径。
- 不在项目配置中写个人电脑绝对路径。
- 不自动创建用户传入的仓库目录。
- 不允许把普通文件当作仓库目录。

路径是否存在和是否是目录可以继续由 `AnalyzerProcessRunner` 做最终校验，API 层只做基本请求校验。

## 自动化测试要求

测试不依赖真实 C++ analyzer。应使用 mock 或测试替身覆盖 Controller 和 Service 行为。

至少覆盖：

### 1. 成功返回报告

- 请求路径中可以包含空格。
- `LocalAnalysisService` 调用 runner。
- 响应状态为 `200`。
- 响应 JSON 包含 `protocolVersion`、`taskId`、`summary`。

### 2. 空请求字段

- `repositoryPath` 为空字符串或缺失。
- 响应状态为 `400`。
- 错误类型稳定。

### 3. 非目录或不存在路径

- runner 抛出 `AnalyzerExecutionFailure.INVALID_REPOSITORY_PATH`。
- 响应状态为 `400`。

### 4. analyzer 超时

- runner 抛出 `AnalyzerExecutionFailure.TIMEOUT`。
- 响应状态为 `504`。

### 5. analyzer 执行失败

- 至少覆盖 `SCAN_FAILED` 或 `REPORT_INVALID`。
- 响应状态为 `502`。
- 不泄露无限诊断输出。

### 6. 原有测试继续通过

完整执行：

```powershell
.\openpulse-platform\mvnw.cmd clean verify
```

现有 23 个测试必须继续通过。

## 真实本地验证

如果当前机器已有真实 `openpulse-analyzer.exe`，可以手动验证：

```powershell
$env:OPENPULSE_ANALYZER_EXECUTABLE = "C:\path\to\openpulse-analyzer.exe"
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
- API 层和 runner 职责分离。
- 成功和失败响应格式稳定。
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
自动化测试不得依赖真实 C++ analyzer、个人路径、网络或 Windows 专属脚本。
完成 Controller、DTO、Application Service、错误响应映射和测试。
最后运行 .\openpulse-platform\mvnw.cmd clean verify，
创建范围单一的本地实现 commit，但不要 push 或创建 PR。
真实 C++ 二进制不可用时如实记录，不要伪造冒烟验证结果。
最后按任务书格式生成回主线对话的验收报告。
```
