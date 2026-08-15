# Java 第五步：解析 GitHub URL 并获取公开仓库元数据

## 任务定位

- 负责人：Java 负责人
- 建议分支：`feat/platform-github-metadata`
- 所属阶段：P2 真实 GitHub 仓库分析
- 前置任务：本地分析 API 及测试隔离修复已合并到 `main`

本任务建立“用户提交 GitHub 仓库地址 -> Java 得到可信仓库坐标 -> GitHub REST API 返回公开仓库元数据”的内部能力。

本步骤不新增用户 HTTP 接口，不下载仓库，也不接数据库。先把不可信 URL、外部 HTTP 请求、Token、超时、限流和错误分类处理清楚，下一步才能安全下载代码。

## 为什么要单独做这一步

GitHub URL 和 GitHub API 看起来简单，但它们都是系统边界：

- 用户输入可能不是 GitHub URL，也可能伪装成相似域名。
- 仓库可能不存在、不可访问、已归档或是私有仓库。
- GitHub 可能返回限流、超时、服务错误或无法解析的 JSON。
- Token 一旦进入日志、异常消息或 Git，就会成为安全事故。
- 自动化测试如果依赖真实 GitHub，会受网络和限流影响而不稳定。

因此本任务先形成一个可替换、可测试的 GitHub 元数据适配边界，不把网络细节扩散到后续业务代码。

## 开始前阅读

- `README.md`
- `docs/00-glossary.md`
- `docs/01-mvp-v0.1.md`
- `docs/04-architecture.md`
- `docs/06-roadmap.md`
- `docs/08-week-2-checklist.md`
- `docs/tasks/04-java-local-analysis-api.md`
- GitHub 官方 `Get a repository` 文档：`https://docs.github.com/en/rest/repos/repos#get-a-repository`
- GitHub 官方 REST API 入门：`https://docs.github.com/en/rest/using-the-rest-api/getting-started-with-the-rest-api`
- GitHub 官方限流说明：`https://docs.github.com/en/rest/using-the-rest-api/rate-limits-for-the-rest-api`

## 本步骤需要理解的名词

新对话在使用这些概念前，应先用简单语言解释：

- **URI**：结构化的网址对象，可以分别读取协议、主机、路径、查询参数和 fragment。
- **规范化（Normalization）**：把 `.git`、尾部斜杠等不同写法转换成统一仓库坐标。
- **仓库坐标**：唯一定位仓库的 `owner` 和 `repository`，例如 `openai/openai`。
- **REST API**：通过 HTTP 方法和 JSON 操作资源的接口风格。
- **HTTP Header**：随请求或响应传递的元数据，例如 API 版本、Token 和限流信息。
- **Bearer Token**：放在 `Authorization` 请求头中的访问凭据。
- **限流（Rate Limit）**：GitHub 限制单位时间内的 API 请求次数。
- **连接超时**：无法及时与 GitHub 建立网络连接时停止等待。
- **读取超时**：连接成功后，GitHub 长时间没有返回完整响应时停止等待。
- **适配器（Adapter）**：把 GitHub 的 HTTP/JSON 形式转换成项目内部对象的代码。
- **测试替身（Fixture/Mock Server）**：在测试中模拟 GitHub 响应，避免访问真实网络。

## 外部契约

只调用 GitHub 的单仓库查询接口：

```http
GET https://api.github.com/repos/{owner}/{repo}
```

每次请求必须包含：

```text
Accept: application/vnd.github+json
X-GitHub-Api-Version: 2026-03-10
User-Agent: openpulse-ai
```

只有 Token 非空时才增加：

```text
Authorization: Bearer <token>
```

本任务不调用搜索 API、GraphQL API、仓库内容 API 或语言明细 API。`Get a repository` 响应中的 `language` 字段已经足够作为本步骤的主要语言信息。

## 建议代码边界

沿用模块化单体结构：

```text
openpulse-platform/src/main/java/io/github/lihanc940/openpulse/
  project/
    application/
      GithubRepositoryLookupService.java
    domain/
      GithubRepositoryCoordinates.java
      GithubRepositoryMetadata.java
  integration/
    github/
      GithubClientProperties.java
      GithubRepositoryUrlParser.java
      GithubRepositoryClient.java
      GithubRepositoryResponse.java
      GithubRepositoryException.java
      GithubRepositoryFailure.java
```

职责建议：

- `GithubRepositoryUrlParser`：只解析和校验用户 URL，返回可信 `owner/repository`。
- `GithubRepositoryClient`：只负责 GitHub HTTP、请求头、超时、响应解析和外部错误映射。
- `GithubRepositoryResponse`：只表示 GitHub JSON，不作为业务模型向外传播。
- `GithubRepositoryLookupService`：编排 URL parser 和 client，返回项目自己的元数据对象。
- `GithubRepositoryMetadata`：保存后续下载和持久化真正需要的字段。

不要让 Controller、下载器或未来数据库代码直接使用 GitHub 原始响应 DTO。

## URL 解析规则

支持这些输入：

```text
https://github.com/{owner}/{repo}
https://github.com/{owner}/{repo}/
https://github.com/{owner}/{repo}.git
https://github.com/{owner}/{repo}?tab=readme
https://github.com/{owner}/{repo}#readme
```

统一得到：

```text
owner: {owner}
repository: {repo}
canonicalUrl: https://github.com/{owner}/{repo}
```

必须拒绝：

- 空字符串或无法构造为 `URI` 的输入。
- 非 `https` 协议，包括 `http`、`ssh` 和 `git`。
- 主机不是精确的 `github.com`，例如 `github.com.example.com`。
- 含 user-info、显式端口、反斜杠或控制字符的 URL。
- 缺少 owner 或 repository。
- 多余路径，例如 `/owner/repo/issues/1`。
- 编码后的 `/` 或 `\\`，避免路径段被重新解释。
- 去掉 `.git` 后为空的仓库名。

必须使用 `java.net.URI` 和结构化字段校验，不使用正则表达式从整条 URL 中直接抓取 owner/repository。

## 元数据模型

项目内部至少保存：

```text
owner
name
fullName
canonicalUrl
description
defaultBranch
primaryLanguage
stars
forks
archived
```

要求：

- `owner`、`name`、`fullName`、`canonicalUrl`、`defaultBranch` 不能为空。
- `description` 和 `primaryLanguage` 可以为空。
- `stars` 和 `forks` 不得为负数。
- GitHub 返回 `private: true` 时拒绝，当前版本只支持公开仓库。
- 不把 GitHub 原始 JSON 整体塞入业务对象。

## 配置要求

建议配置：

```yaml
openpulse:
  github:
    api-base-url: ${OPENPULSE_GITHUB_API_BASE_URL:https://api.github.com}
    token: ${OPENPULSE_GITHUB_TOKEN:}
    connect-timeout: ${OPENPULSE_GITHUB_CONNECT_TIMEOUT:3s}
    read-timeout: ${OPENPULSE_GITHUB_READ_TIMEOUT:10s}
```

边界要求：

- Token 只能来自 `OPENPULSE_GITHUB_TOKEN`，默认允许为空以读取公开仓库。
- API base URL 可配置是为了测试使用本地替身；它不是用户请求参数。
- 配置启动时校验 URI、超时必须大于零。
- 任何 `toString()`、日志、异常和测试快照都不能包含 Token。
- 不把真实 Token 写入 `application.yml`、测试或 PR 描述。

未认证公开请求会受较低的 GitHub 限流约束，因此开发时可以不配 Token，但真实部署应使用最小权限凭据。

## 重定向边界

GitHub 的仓库查询可能返回重定向。允许跟随有限次数的重定向，但必须满足：

- 最多 3 次，防止循环。
- `Location` 必须仍与配置的 API base URL 同协议、同主机、同端口。
- 不把 Authorization Header 转发到其他源。
- 永远不跟随降级到 `http` 的地址。

如果当前 HTTP 客户端无法安全满足这些条件，本任务可以把重定向映射为明确失败，不能无边界自动跟随。

## 失败类型

建议定义稳定枚举：

```text
INVALID_REPOSITORY_URL
REPOSITORY_NOT_FOUND_OR_INACCESSIBLE
PRIVATE_REPOSITORY_UNSUPPORTED
AUTHENTICATION_FAILED
RATE_LIMITED
GITHUB_UNAVAILABLE
TIMEOUT
INVALID_RESPONSE
```

建议映射：

| 场景 | 失败类型 |
| --- | --- |
| URL 结构不合法 | `INVALID_REPOSITORY_URL` |
| GitHub `404` | `REPOSITORY_NOT_FOUND_OR_INACCESSIBLE` |
| 成功响应中 `private=true` | `PRIVATE_REPOSITORY_UNSUPPORTED` |
| GitHub `401` | `AUTHENTICATION_FAILED` |
| `403/429` 且有明确限流信号 | `RATE_LIMITED` |
| 其他 `403`、`5xx`、不可信重定向或网络连接失败 | `GITHUB_UNAVAILABLE` |
| 连接或读取超时 | `TIMEOUT` |
| `200` 但 JSON 损坏、字段缺失或字段非法 | `INVALID_RESPONSE` |

`403/429` 限流时读取 `Retry-After`、`X-RateLimit-Remaining` 和 `X-RateLimit-Reset`，但本任务不自动无限重试。异常只保留安全、有限的诊断，不包含 Token 或完整响应体。

## HTTP 客户端要求

- 优先使用 Spring 已提供的 `RestClient` 或项目现有 HTTP 能力，不为一个 GET 请求引入大型 SDK。
- 显式设置连接和读取超时。
- owner/repository 必须作为经过验证并编码的路径段使用，不能字符串拼接不可信原始 URL。
- 限制可读取的错误响应大小，不能把无限响应体放进内存或异常。
- 不记录 Authorization Header。
- 不在代码里自动打印完整 GitHub 响应。

## 自动化测试要求

自动化测试不得访问 `github.com` 或 `api.github.com`。使用 `MockRestServiceServer`、本地 HTTP fixture 或等价测试替身。

至少覆盖：

### 1. URL 解析成功

- 标准 URL。
- `.git` 后缀。
- 尾部斜杠。
- query 和 fragment。
- owner/repository 大小写保持或规范化策略明确。

### 2. URL 拒绝

- 空值和空白。
- `http`、SSH、错误主机和相似恶意主机。
- user-info、显式端口、多余路径。
- 缺少 owner/repository。
- 编码路径分隔符和控制字符。

### 3. 成功元数据

- 模拟 `200` GitHub JSON。
- 验证请求路径和三个必需 Header。
- 映射名称、默认分支、描述、stars、forks、语言和 archived。
- 可空的 description/language 能正常处理。

### 4. Token 边界

- Token 为空时没有 Authorization Header。
- Token 非空时使用 `Bearer` Header。
- 异常、日志断言或对象字符串中不出现测试 Token。

### 5. 外部失败

- `401`、`404`、普通 `403`。
- `403/429` 限流响应。
- `5xx`。
- 连接/读取超时。
- 损坏 JSON、缺少必填字段、负数计数和私有仓库。
- 重定向边界。

### 6. Application Service

- 确认先解析 URL，再查询元数据。
- URL 失败时不调用 GitHub client。
- 返回项目内部 `GithubRepositoryMetadata`，而不是 integration DTO。

### 7. 原有测试继续通过

在 `openpulse-platform` 目录执行：

```powershell
.\mvnw.cmd clean verify
```

现有 47 个测试必须继续通过。

## 可选真实冒烟验证

自动化测试通过后，可以用一个公开仓库做一次手动查询，例如当前项目自身。真实验证必须记录：

- 使用的公开仓库 URL。
- 是否使用 Token，但绝不记录 Token 内容。
- 返回的 `fullName`、`defaultBranch` 和 `primaryLanguage`。
- 剩余限流信息（如果客户端有安全暴露）。

真实网络不可用时如实记录，不伪造结果。不要为了冒烟验证提交临时 Controller、Token 或调试代码。

## 明确不做

本任务禁止提前加入：

- 新的用户 HTTP API。
- GitHub OAuth、私有仓库或用户授权。
- `git clone`、archive zip 或任何仓库下载。
- 数据库实体、迁移脚本和持久化。
- 分析任务状态。
- 调用 C++ analyzer。
- 重试框架、缓存、队列或并发请求。
- AI、前端、Docker 或 GitHub Actions。

## 完成标准

- URL parser 对允许与拒绝输入有明确测试。
- GitHub client 使用固定 API 版本、User-Agent、Accept、超时和可选 Token。
- 公开仓库响应被转换成项目内部元数据。
- 私有仓库、404、认证、限流、超时、服务失败和无效响应可区分。
- Token 不进入代码、Git、日志、异常或测试输出。
- 自动化测试不依赖真实网络和个人环境。
- `git diff --check` 通过。
- `.\mvnw.cmd clean verify` 通过。
- 创建范围单一的本地实现 commit。
- 先不要 push，回主线对话验收。

## 回主线对话时提交的信息

```text
分支：
commit：
URL parser 对外方法：
支持和拒绝的 URL：
GitHub client 对外方法：
配置项和环境变量：
请求 Header：
元数据字段：
失败类型：
自动化测试场景：
全部测试数量和结果：
真实 GitHub 冒烟验证：
Token 防泄露验证：
我理解的新名词：
遇到的问题：
```

## 新对话启动提示词

在相同项目工作区创建新对话后发送：

```text
请先阅读 README.md、docs/00-glossary.md、docs/01-mvp-v0.1.md、
docs/04-architecture.md、docs/06-roadmap.md、docs/08-week-2-checklist.md、
docs/tasks/04-java-local-analysis-api.md、
docs/tasks/05-java-github-repository-metadata.md，
以及现有 openpulse-platform 代码和测试。

我是 Java 初学者，请严格完成“Java 第五步：解析 GitHub URL 并获取公开仓库元数据”。
从最新 main 创建 feat/platform-github-metadata 分支。
每使用一个新名词前先用简单语言解释，再执行对应操作并验证。
不要加入任务书明确禁止的功能。
本任务只做 GitHub URL 解析、公开仓库元数据客户端和内部 lookup service，
不新增用户 HTTP API，不下载仓库，不接数据库，不调用 analyzer，也不做 AI 或前端。
Token 只能来自 OPENPULSE_GITHUB_TOKEN，绝不能写入代码、测试、日志或提交。
自动化测试不得访问真实 GitHub 网络；使用 MockRestServiceServer、本地 fixture 或等价替身。
必须覆盖 URL 欺骗、认证、404、私有仓库、限流、超时、5xx、无效 JSON 和安全重定向边界。
最后在 openpulse-platform 目录运行 .\mvnw.cmd clean verify，
创建范围单一的本地实现 commit，但不要 push 或创建 PR。
真实 GitHub 验证不可用时如实记录，不要伪造结果，也不要提交临时调试入口。
最后按任务书格式生成回主线对话的验收报告。
```
