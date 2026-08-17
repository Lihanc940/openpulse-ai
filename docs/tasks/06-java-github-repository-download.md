# Java 第六步：受控下载并解压 GitHub 公开仓库

## 任务定位

- 负责人：Java 负责人
- 建议分支：`feat/platform-github-repository-download`
- 所属阶段：P2 真实 GitHub 仓库分析
- 前置任务：GitHub URL 解析和公开仓库元数据查询已合并到 `main`

本任务建立“可信公开仓库元数据 -> 下载 GitHub ZIP archive -> 安全解压到隔离临时目录 -> 返回可清理工作区”的内部能力。

本步骤不新增用户 HTTP 接口，不接数据库，也不调用 C++ analyzer。先把外部下载、重定向、大小限制、恶意 ZIP 和临时目录生命周期处理清楚，下一步才能把下载结果交给分析器。

## 为什么要单独做这一步

下载代码不是简单地把一个 URL 写入文件：

- GitHub archive API 会先返回重定向，Token 不能被转发到不可信主机。
- `Content-Length` 可能缺失或不可信，必须在实际读取时继续计数。
- 很小的压缩包可能解压出大量内容，形成 ZIP bomb。
- ZIP entry 可能包含 `../`、绝对路径或反斜杠，尝试写出目标目录。
- 下载、解压、调用方使用或清理任何一步失败，都不能遗留无限增长的临时目录。
- 自动化测试不能依赖 GitHub 网络，否则会受网络、限流和仓库变化影响。

因此本任务把“网络下载”和“本地解压”放在明确的 integration 边界中，并由 application service 统一管理临时工作区所有权。

## 开始前阅读

- `README.md`
- `docs/00-glossary.md`
- `docs/01-mvp-v0.1.md`
- `docs/04-architecture.md`
- `docs/06-roadmap.md`
- `docs/08-week-2-checklist.md`
- `docs/tasks/03-java-analyzer-process-runner.md`
- `docs/tasks/05-java-github-repository-metadata.md`
- GitHub 官方 `Download a repository archive (zip)` 文档：`https://docs.github.com/en/rest/repos/contents?apiVersion=2026-03-10#download-a-repository-archive-zip`
- Java 21 `HttpClient` 文档：`https://docs.oracle.com/en/java/javase/21/docs/api/java.net.http/java/net/http/HttpClient.html`
- Java 21 `java.util.zip` 文档：`https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/zip/package-summary.html`

## 本步骤需要理解的名词

新对话在使用这些概念前，应先用简单语言解释：

- **Archive**：把许多文件打包成一个文件的归档，本任务使用 ZIP。
- **流式读取（Streaming）**：边接收边处理数据，不把整个下载一次性放入内存。
- **重定向（Redirect）**：服务器通过 `3xx` 和 `Location` 告诉客户端去另一个地址获取内容。
- **同源（Same Origin）**：协议、主机和端口都相同的网络来源。
- **SSRF**：服务端被诱导访问攻击者指定地址的风险，本任务通过固定 endpoint 和重定向白名单避免它。
- **ZIP Slip**：恶意 ZIP entry 利用 `../` 或绝对路径写出解压目录的漏洞。
- **ZIP bomb**：压缩时很小、解压后极大的恶意归档。
- **解压后大小**：真正写入磁盘的未压缩字节总数，不能只相信 ZIP 声明值。
- **Entry**：ZIP 中的一个文件或目录条目。
- **临时工作区**：Java 为一次下载创建并拥有的隔离目录。
- **资源所有权**：谁创建资源，谁负责在明确时机释放或删除它。
- **`AutoCloseable`**：Java 中可以被 `try-with-resources` 自动关闭的资源契约。
- **`try-with-resources`**：代码块结束时自动调用 `close()` 的 Java 语法。
- **测试 fixture**：测试中生成的可控 HTTP 响应或 ZIP 内容，不访问真实 GitHub。

## 外部契约

只使用 GitHub 的公开仓库 ZIP archive endpoint：

```http
GET {apiBaseUrl}/repos/{owner}/{repository}/zipball
```

本任务省略 `ref`，让 GitHub 使用仓库当前默认分支。不要把用户原始 URL 或未经校验的分支名直接拼进下载 URL。

第一跳请求沿用任务 5 的固定 Header：

```text
Accept: application/vnd.github+json
X-GitHub-Api-Version: 2026-03-10
User-Agent: openpulse-ai
```

只有已有 `GithubClientProperties` 中 Token 非空时，第一跳才增加：

```text
Authorization: Bearer <token>
```

GitHub 正常返回 `302 Found` 和 `Location`。客户端必须手动验证并请求重定向地址，不能开启无边界自动重定向。

第二跳下载 archive 时绝不转发 `Authorization` Header。当前版本只支持已经由任务 5 确认为公开的仓库。

## 建议代码边界

沿用现有模块化单体结构：

```text
openpulse-platform/src/main/java/io/github/lihanc940/openpulse/
  project/
    application/
      GithubRepositoryDownloadService.java
    domain/
      DownloadedRepositoryWorkspace.java
  integration/
    github/
      GithubArchiveProperties.java
      GithubRepositoryArchiveClient.java
      GithubArchiveExtractor.java
      GithubArchiveException.java
      GithubArchiveFailure.java
```

职责建议：

- `GithubRepositoryArchiveClient`：构造固定 archive endpoint，处理请求 Header、超时、状态码、安全重定向和压缩包字节上限。
- `GithubArchiveExtractor`：只负责校验 ZIP entry，并在限制内解压到指定目录。
- `GithubRepositoryDownloadService`：创建临时工作区，编排下载和解压，确保失败路径清理。
- `DownloadedRepositoryWorkspace`：保存工作区目录和实际仓库根目录，实现 `AutoCloseable` 并负责成功路径的最终清理。
- `GithubArchiveFailure`：提供稳定失败分类，不让后续 API 依赖底层 `IOException` 文本。

不要让未来 Controller 直接操作 `HttpClient`、`ZipInputStream` 或系统临时目录。

## 对外方法建议

Application Service 对外方法：

```java
DownloadedRepositoryWorkspace download(GithubRepositoryMetadata metadata)
```

返回对象至少提供：

```java
Path repositoryRoot();
Path workspaceRoot();
void close();
```

正常调用方式：

```java
try (DownloadedRepositoryWorkspace workspace = downloadService.download(metadata)) {
    Path repositoryRoot = workspace.repositoryRoot();
    // 下一任务才会把 repositoryRoot 交给 AnalyzerProcessRunner。
}
```

生命周期规则：

- 下载或解压在返回对象前失败：`GithubRepositoryDownloadService` 必须立即清理整个工作区。
- 下载成功：工作区在返回后继续存在，供调用方读取。
- 调用方结束使用：`close()` 删除 archive、解压目录和工作区根目录。
- `close()` 应允许安全重复调用；第一次清理失败必须报告 `CLEANUP_FAILED`，不能静默假装成功。
- 不使用 finalizer 或 JVM shutdown hook 代替明确关闭。

## 配置要求

继续复用任务 5 的：

```text
openpulse.github.api-base-url
openpulse.github.token
openpulse.github.connect-timeout
```

新增 archive 专用配置：

```yaml
openpulse:
  github:
    archive:
      read-timeout: ${OPENPULSE_GITHUB_ARCHIVE_READ_TIMEOUT:60s}
      max-archive-size: ${OPENPULSE_GITHUB_ARCHIVE_MAX_SIZE:50MB}
      max-extracted-size: ${OPENPULSE_GITHUB_ARCHIVE_MAX_EXTRACTED_SIZE:250MB}
      max-entry-count: ${OPENPULSE_GITHUB_ARCHIVE_MAX_ENTRY_COUNT:50000}
```

要求：

- 使用 `Duration` 和 Spring `DataSize` 等结构化类型，不手写字符串单位解析。
- 所有时间、大小和 entry 数限制必须大于零，并在应用启动绑定配置时校验。
- 测试使用很小的限制触发边界，不生成几十 MB 或几百 MB 的测试文件。
- 配置对象的 `toString()` 不包含 Token；archive 配置本身不新增密钥。
- 不允许用户通过 HTTP 请求覆盖 API base URL、大小限制或临时目录根路径。

默认值是 MVP 的保护线，不代表以后永远不变。真实仓库超出限制时应明确失败，由部署配置有意识地调整，而不是自动取消限制。

## 重定向安全边界

必须使用“不自动跟随重定向”的客户端，手动处理最多一次重定向。

生产默认地址 `https://api.github.com` 的 `Location` 只允许：

- `https` 协议。
- 主机精确等于 `codeload.github.com`。
- 不含 user-info、fragment、反斜杠或控制字符。
- 不含非默认显式端口。

测试使用可配置 loopback API base URL 时，只允许重定向到与测试 base URL 同协议、同主机、同端口的地址。已有配置边界必须继续保证 HTTP fixture 无 Token。

其他目标，包括相似域名、降级到 HTTP、额外重定向、相对地址和缺少 `Location`，都映射为 `UNTRUSTED_REDIRECT`。

无论第一跳是否带 Token，第二跳都不得带 `Authorization`。不要依赖 HTTP 客户端“通常会删除 Header”的默认行为，测试必须明确断言。

## 下载大小与超时边界

archive 下载必须流式写入工作区中的临时 ZIP 文件，不能使用 `byte[]`、`String` 或无界内存缓冲保存完整响应。

要求：

- `Content-Length` 存在且超过上限时，可以在读取前拒绝。
- 即使 `Content-Length` 缺失、为负、格式错误或小于实际内容，也必须按实际读取字节计数。
- 一旦实际压缩字节超过 `max-archive-size`，立即停止读取并返回 `ARCHIVE_TOO_LARGE`。
- 连接和读取必须受超时限制；超时后关闭响应流并清理工作区。
- 中断时恢复当前线程的中断标记，并映射为 `INTERRUPTED`。
- 网络流、文件流和 HTTP 响应必须在所有路径关闭。
- 不把 archive 响应体、Token、重定向完整 query 或本机绝对路径放入异常消息。

不要只依据 Content-Type 判断 ZIP；可以把它作为辅助校验，但最终仍要由 ZIP 解析器验证内容。

## 安全解压规则

使用 Java 标准库 `ZipInputStream`、`ZipFile` 或等价的受控 ZIP API。不要调用 PowerShell、`tar`、`unzip` 或其他 shell 命令。

每个 entry 写入前必须：

1. 拒绝空名称、控制字符和反斜杠。
2. 拒绝绝对路径、Windows 盘符路径和任何规范化后逃出解压根目录的路径。
3. 使用 `destinationRoot.resolve(entryName).normalize()`，并验证结果仍以规范化后的 `destinationRoot` 开头。
4. 拒绝重复目标路径、文件/目录冲突和试图覆盖已有条目。
5. 只创建普通目录和普通文件，不创建符号链接，不恢复可执行位或其他 Unix 特殊文件类型。
6. 在写入实际解压字节时累计总量，超过 `max-extracted-size` 立即失败。
7. 每读取一个 entry 累计数量，超过 `max-entry-count` 立即失败。

不能只相信 `ZipEntry.getSize()` 或 ZIP 中声明的大小，因为它可能未知或伪造。限制必须依据实际写出的未压缩字节执行。

GitHub ZIP 通常包含一个顶层包装目录。解压完成后必须确认：

- archive 非空。
- 只有一个顶层目录。
- 顶层目录内至少存在一个条目。
- 返回的 `repositoryRoot` 是这个顶层目录，而不是包含 ZIP 文件的工作区。

不需要校验它的随机后缀或把目录名解析成 commit SHA。

## 临时目录与清理规则

- 使用 `Files.createTempDirectory("openpulse-github-")` 或等价 API 创建唯一工作区。
- ZIP 文件和解压内容只能位于这个工作区内部。
- 不接受用户传入输出目录，不写入项目源码目录、当前工作目录或用户仓库目录。
- 清理前对目标做绝对路径和归属校验，只允许删除本次对象拥有的工作区。
- 递归清理不得跟随符号链接。
- Windows 文件句柄可能短暂占用文件，可以采用次数有限、间隔有限的重试；不能无限循环。
- 清理异常不能覆盖更早的主要失败。需要保留主要失败类型，并把清理问题作为受控附加诊断；如果只有清理失败，则返回 `CLEANUP_FAILED`。
- 自动化测试结束后不得残留以 `openpulse-github-` 开头的测试目录。

## 失败类型

建议新增稳定枚举：

```text
INVALID_REPOSITORY_METADATA
TEMPORARY_DIRECTORY_CREATION_FAILED
ARCHIVE_WRITE_FAILED
AUTHENTICATION_FAILED
REPOSITORY_NOT_FOUND_OR_INACCESSIBLE
RATE_LIMITED
GITHUB_UNAVAILABLE
DOWNLOAD_TIMEOUT
INTERRUPTED
UNTRUSTED_REDIRECT
ARCHIVE_TOO_LARGE
ARCHIVE_INVALID
ARCHIVE_ENTRY_LIMIT_EXCEEDED
EXTRACTED_CONTENT_TOO_LARGE
UNSAFE_ARCHIVE_ENTRY
CLEANUP_FAILED
```

建议映射：

| 场景 | 失败类型 |
| --- | --- |
| metadata 必填字段非法 | `INVALID_REPOSITORY_METADATA` |
| 临时目录无法创建 | `TEMPORARY_DIRECTORY_CREATION_FAILED` |
| ZIP 临时文件无法创建或写入 | `ARCHIVE_WRITE_FAILED` |
| GitHub `401` | `AUTHENTICATION_FAILED` |
| GitHub 或下载地址 `404` | `REPOSITORY_NOT_FOUND_OR_INACCESSIBLE` |
| GitHub `429`，或 `403` 且有任务 5 定义的明确限流信号 | `RATE_LIMITED` |
| 其他 `403`、`5xx` 或连接失败 | `GITHUB_UNAVAILABLE` |
| 连接或读取超时 | `DOWNLOAD_TIMEOUT` |
| 当前线程被中断 | `INTERRUPTED` |
| Location 缺失、不可信或出现第二次重定向 | `UNTRUSTED_REDIRECT` |
| 实际下载压缩字节超限 | `ARCHIVE_TOO_LARGE` |
| ZIP 损坏、为空或顶层结构不合法 | `ARCHIVE_INVALID` |
| entry 数量超限 | `ARCHIVE_ENTRY_LIMIT_EXCEEDED` |
| 实际解压字节超限 | `EXTRACTED_CONTENT_TOO_LARGE` |
| 路径穿越、绝对路径、重复目标或特殊条目 | `UNSAFE_ARCHIVE_ENTRY` |
| 最终工作区无法完整删除 | `CLEANUP_FAILED` |

可以复用任务 5 的安全状态码识别逻辑，但不要让下载器抛出 `GithubRepositoryException` 后再靠异常消息猜原因。失败类型必须由代码直接映射。

## 自动化测试要求

自动化测试不得访问 `github.com`、`api.github.com` 或 `codeload.github.com`。使用 `MockRestServiceServer`、loopback HTTP fixture、可注入 transport 或等价测试替身。

ZIP fixture 应在测试中用 `ZipOutputStream` 动态生成，避免向 Git 提交大型二进制测试文件。

至少覆盖：

### 1. 配置校验

- 合法默认结构化配置。
- 零值和负数 timeout、大小和 entry 数被拒绝。
- 测试可使用很小上限。

### 2. 正常下载

- 第一跳路径为 `/repos/{owner}/{repository}/zipball`。
- 第一跳包含固定三个 Header。
- Token 为空时没有 Authorization。
- Token 非空时只在第一跳使用 Bearer Header。
- `302` 后请求允许的下载地址，第二跳没有 Authorization。
- 内容流式写入，解压后返回唯一顶层仓库目录。
- 文件内容和包含空格、Unicode 的合法相对路径保持正确。

### 3. 重定向拒绝

- 缺少或无法解析的 `Location`。
- 相似恶意主机、HTTP 降级、user-info、显式非默认端口。
- 相对地址和第二次重定向。
- loopback fixture 只能重定向到同源。
- 所有拒绝场景都不向目标发送 Token。

### 4. HTTP 和网络失败

- `401`、`404`、普通 `403`、限流 `403/429` 和 `5xx`。
- 连接失败、读取超时和中断。
- 错误响应体很大时，不进入异常或无界内存。

### 5. 压缩包大小限制

- `Content-Length` 已经超限。
- 没有 `Content-Length` 时实际读取超限。
- Header 声明小于实际内容时仍按实际字节拒绝。
- 恰好等于上限时允许，超过一个字节时拒绝。

### 6. 解压安全

- 标准单顶层目录 ZIP 成功。
- `../evil`、嵌套穿越、绝对路径、盘符路径和反斜杠被拒绝。
- 重复 entry、文件/目录冲突被拒绝。
- 损坏 ZIP、空 ZIP、多顶层目录和顶层普通文件被拒绝。
- entry 数量在边界内成功，超过一个时失败。
- 解压字节恰好等于上限成功，超过一个字节失败。
- 不依赖 ZIP 声明大小，测试实际写出字节计数。

### 7. 生命周期与清理

- 下载失败、重定向失败、压缩包超限、解压失败时工作区被清理。
- 成功返回时目录存在，`close()` 后目录消失。
- `close()` 重复调用安全。
- 调用方代码块抛出异常时，`try-with-resources` 仍完成清理。
- 模拟清理失败时得到稳定 `CLEANUP_FAILED` 或保留主要失败并附加受控诊断。

### 8. 原有测试继续通过

在 `openpulse-platform` 目录执行：

```powershell
.\mvnw.cmd clean verify
```

现有 125 个测试必须继续通过。

## 可选真实冒烟验证

自动化测试通过后，可以下载一个体积很小的公开仓库，验证：

- 第一跳 archive 请求成功。
- 重定向目标通过白名单校验。
- 返回的 `repositoryRoot` 存在且位于临时工作区内。
- 能读取仓库中的预期文件。
- 关闭 workspace 后整个临时目录已删除。

记录仓库 URL、是否使用 Token、压缩字节数、解压字节数、entry 数和清理结果，但绝不记录 Token、重定向签名 query 或本机完整临时路径。

真实网络不可用时如实记录，不伪造结果。不要为了冒烟验证提交临时 Controller、命令行入口、Token 或个人路径。

## 明确不做

本任务禁止提前加入：

- 新的用户 HTTP API。
- `git clone`、Git 凭据管理或子模块递归下载。
- 私有仓库、GitHub OAuth 或用户授权。
- 数据库实体、迁移脚本和持久化。
- 分析任务状态。
- 调用 `AnalyzerProcessRunner` 或 C++ analyzer。
- 健康评分、AI 和前端。
- 重试框架、缓存、队列、并发下载或断点续传。
- Docker、GitHub Actions 或生产部署配置。
- 自动执行 archive 中的任何脚本或可执行文件。

## 完成标准

- 下载入口只接受项目内部可信 `GithubRepositoryMetadata`。
- archive endpoint 使用结构化 URI 构造，未拼接用户原始 URL。
- 重定向次数、协议、主机和 Header 转发边界明确。
- Token 只出现在允许的第一跳，不进入日志、异常、测试输出或第二跳。
- 压缩字节、解压字节和 entry 数都有实际计数上限。
- ZIP Slip、ZIP bomb、重复路径和非法顶层结构有测试。
- 下载或解压失败会立即清理；成功工作区通过 `AutoCloseable` 明确清理。
- 自动化测试不依赖真实网络、个人路径、操作系统 shell 或大文件。
- `git diff --check` 通过。
- `.\mvnw.cmd clean verify` 通过。
- 没有 archive、解压仓库、构建产物、密钥或个人路径进入 Git。
- 创建范围单一的本地实现 commit。
- 先不要 push，回主线对话验收。

## 回主线对话时提交的信息

```text
分支：
commit：
下载服务对外方法：
临时工作区生命周期：
配置项和环境变量：
第一跳和第二跳 Header：
重定向白名单：
压缩大小、解压大小和 entry 数限制：
ZIP Slip 与 ZIP bomb 防护：
失败类型：
自动化测试场景：
全部测试数量和结果：
真实 GitHub 下载冒烟验证：
Token 防泄露验证：
临时目录清理验证：
我理解的新名词：
遇到的问题：
```

## 新对话启动提示词

在相同项目工作区创建新对话后发送：

```text
请先阅读 README.md、docs/00-glossary.md、docs/01-mvp-v0.1.md、
docs/04-architecture.md、docs/06-roadmap.md、docs/08-week-2-checklist.md、
docs/tasks/03-java-analyzer-process-runner.md、
docs/tasks/05-java-github-repository-metadata.md、
docs/tasks/06-java-github-repository-download.md，
以及现有 GitHub metadata、AnalyzerProcessRunner 代码和测试。

我是 Java 初学者，请严格完成“Java 第六步：受控下载并解压 GitHub 公开仓库”。
从最新 main 创建 feat/platform-github-repository-download 分支。
每使用一个新名词前先用简单语言解释，再执行对应操作并验证。
不要加入任务书明确禁止的功能。
本任务只做公开仓库 ZIP archive 的受控下载、安全解压和临时工作区生命周期，
不新增用户 HTTP API，不接数据库，不调用 analyzer，也不做 AI 或前端。
必须手动校验最多一次重定向，Token 只允许出现在第一跳，绝不能发送到下载地址。
压缩字节、实际解压字节和 entry 数必须有可配置上限；
必须防止 ZIP Slip、ZIP bomb、重复路径和非法顶层结构。
成功工作区使用 AutoCloseable 明确清理，所有失败路径也必须清理。
自动化测试不得访问真实 GitHub 网络，不得依赖个人路径、shell 命令或大型二进制 fixture。
最后在 openpulse-platform 目录运行 .\mvnw.cmd clean verify，
创建范围单一的本地实现 commit，但不要 push 或创建 PR。
真实 GitHub 验证不可用时如实记录，不要伪造结果，也不要提交临时调试入口。
最后按任务书格式生成回主线对话的验收报告。
```
