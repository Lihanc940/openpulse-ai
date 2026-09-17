# OpenPulse AI 第十步：Java 严格读取报告协议 v2

## 任务定位

- 建议分支：`feat/platform-report-v2-reader`
- 负责人：Java / Platform 负责人
- 前置任务：报告协议 v2 已标记为 `ACCEPTED` 并合并到 `main`
- 并行任务：`11-cpp-analyzer-report-v2-output.md`
- 任务性质：增加严格消费端，不改变 C++ 默认输出

本任务让 Java 能准确区分并读取协议 `1.0` 和 `2.0`。v1 继续保持现有兼容行为；v2 使用独立明确模型、Draft 2020-12 Schema 和协议语义校验，不能把未知字段或未知版本悄悄吞掉。

本任务只建立 Reader、v2 模型、校验器和 v2 持久化快照白名单。不修改 C++，不切换 AnalyzerProcessRunner 默认协议，不新增 HTTP 接口。Java/C++ 实现完成后，再由联合契约任务接通真实 v2 子进程链路。

## 为什么单独做这一步

协议 v1 的 Java 模型允许未知字段，证据使用 `Map<String, Object>`，并依赖绝对 `repository.path`。这是旧兼容边界，不能直接改成严格模式，否则会破坏已经通过测试的调用方。

协议 v2 要求未知字段拒绝、精确版本、安全相对路径、明确证据类型、状态组合一致，以及稳定 ID、排序和敏感信息语义检查。因此 v1 和 v2 必须使用独立模型与校验流程，由一个很小的版本分派边界选择，不能在同一个宽松模型上不断增加可空字段。

## 开始前阅读

按顺序阅读：

1. `docs/protocol/analyzer-report-v2.md`
2. `docs/protocol/analyzer-report-v2.schema.json`
3. `docs/protocol/analyzer-report-v2-migration.md`
4. 两个 `docs/examples/analyzer-report-v2.*.sample.json`
5. `openpulse-platform/src/main/java/io/github/lihanc940/openpulse/integration/analyzer/AnalyzerReportReader.java`
6. `openpulse-platform/src/main/java/io/github/lihanc940/openpulse/integration/analyzer/model/AnalyzerReport.java`
7. `openpulse-platform/src/main/java/io/github/lihanc940/openpulse/report/application/AnalysisReportSnapshotFactory.java`
8. 对应 Reader、Runner、SnapshotFactory 和 MySQL 测试
9. `openpulse-platform/pom.xml`

## 本步骤需要理解的名词

- **版本分派**：先只读取 `protocolVersion`，再选择 v1 或 v2 解析器。
- **JSON Schema 验证器**：按照 Schema 自动检查字段、类型、枚举和条件约束的库。
- **语义校验**：Schema 不容易表达、仍需 Java 代码检查的规则，例如结束位置不能早于开始位置。
- **严格未知字段**：JSON 多出模型未声明字段时立即失败。
- **重复键**：同一个 JSON 对象内出现两次同名字段；必须拒绝，不能只保留最后一个。
- **密封接口**：限定只有项目明确声明的类型能够实现的 Java 接口，可表达 v1/v2 两类报告。
- **判别联合**：根据 `kind` 选择四种 evidence 类型，而不是使用自由 Map。
- **白名单快照**：持久化前重新构造只包含批准字段的 JSON，不原样保存输入。
- **规范化**：按协议固定顺序和格式表示内容，便于稳定比较和哈希。
- **资源上限**：读取前限制报告文件大小和数组长度，避免异常文件耗尽内存。

## 本任务交付物

允许修改：

- `openpulse-platform/pom.xml`
- `integration/analyzer` 下的 Reader、版本分派、v2 校验器和异常类型
- `integration/analyzer/model` 下的 v2 明确模型
- `report/application` 下的 v2 快照白名单
- 对应 Java 单元测试和集成测试
- 构建时从仓库正式协议文件复制到 classpath 的最小资源配置

正式 Schema 的单一来源仍是：

```text
docs/protocol/analyzer-report-v2.schema.json
```

如果运行时需要 classpath 资源，应在构建阶段从正式文件复制，不手工维护第二份内容不同的 Schema。测试必须确认打包资源与正式文件一致。

## 建议代码边界

具体类名可以按项目风格调整，职责应至少分为：

- 报告文件边界：检查 null、存在性、普通文件和文件大小。
- 最小版本读取：拒绝 JSON 损坏、重复版本字段、缺失版本和非字符串版本。
- v1 Reader：复用现有 `AnalyzerReport` 和当前兼容校验。
- v2 Reader：使用独立 `AnalyzerReportV2` 模型并拒绝未知字段。
- Schema Validator：只负责 Draft 2020-12 Schema 验证和错误归一化。
- Semantic Validator：负责路径、位置、排序、稳定 ID、规则目录、日期和敏感文本。
- Snapshot Factory：v1/v2 分别使用独立白名单。

可以引入一个小型公共报告接口，只暴露 `protocolVersion`、`taskId`、`status` 和 `generatedAt`。不要为了复用而把 v1 与 v2 所有字段塞进同一个大量可空的基类，也不要把 v2 evidence 降级成 Map。

## 依赖要求

选择一个维护中的 Java JSON Schema 验证库，必须满足：

- 明确支持 Draft 2020-12。
- 与当前 Java 21、Spring Boot 和 Jackson 版本兼容。
- 版本在 `pom.xml` 中固定。
- 许可证允许项目使用。
- 不在运行时联网下载远程 `$ref`。
- 能使用仓库内 Schema 完成本地验证。

引入前记录选择理由和替代方案。不能因为依赖兼容困难就删除 Schema 验证、改成正则拼凑，或全靠手写字段检查。

## 版本分派规则

处理顺序必须是：

1. 验证路径和文件大小。
2. 使用拒绝重复键的 JSON 解析配置读取最小根对象。
3. 根节点不是对象时拒绝。
4. `protocolVersion` 缺失、null、非字符串或重复时拒绝。
5. 精确值 `1.0` 交给 v1 Reader。
6. 精确值 `2.0` 交给 v2 Reader。
7. 其他值报告“不支持的协议版本”，不能回退到最接近版本。

错误消息不得包含 JSON 正文、绝对路径、源码、凭据、堆栈或底层验证器的大段诊断。内部异常可以保留为 cause 供日志和测试判断，但 HTTP 安全边界继续输出固定消息。

## v2 模型要求

为协议中的对象建立明确 Java 类型：

- Analyzer identity、Rule set identity、Repository
- Summary、Language、Structure
- Finding、Location
- 四种 Evidence
- 四种 Limitation
- Status 和 Reviewability 枚举

Evidence 根据 `kind` 映射为：

- `EXPECTED_PATHS_ABSENT`
- `FILE_METRIC`
- `TEXT_MATCH`
- `SYNTAX_METRIC`

Limitation 根据 `kind` 映射为：

- `FILE_SKIPPED`
- `UNSUPPORTED_CAPABILITY`
- `RESOURCE_LIMIT_REACHED`
- `SCAN_FAILED`

所有 v2 对象拒绝未知字段。仓库级 finding 的 `location` 允许且必须为 null；其他字段不能用 null 代替缺失。

## Schema 验证

- 使用仓库正式 Draft 2020-12 Schema。
- Schema 在应用启动或组件构造时编译一次，不要每份报告重复下载或重复编译。
- 验证器必须启用协议依赖的格式检查，尤其是 `date-time`。
- 正例必须通过，未知字段、非法路径、错误状态组合等必须失败。
- 对外异常只提供稳定错误类别和安全摘要，不返回 Schema 内部路径的敏感输入值。

Schema 通过不代表报告已经符合协议，之后还必须执行语义校验。

## v2 语义校验

至少实现协议第 11 节的不变量：

1. 文件位置结束点不早于开始点。
2. 行统计和总行数关系有效。
3. 所有集合按协议稳定键排序且稳定键唯一。
4. 每个 `findingId` 根据内容重新计算并完全一致。
5. 规则 ID、类型和 evidence 种类符合声明的规则目录。
6. SUCCESS、PARTIAL_SUCCESS、FAILED 与 reviewability、字段和 limitations 一致。
7. 路径是 `/` 分隔的安全相对路径，不含 URI、盘符、反斜杠、`.` 或 `..` 段。
8. 文本不含绝对路径、凭据、stdout/stderr 或异常堆栈。
9. `generatedAt` 能被严格解析为带时区 RFC 3339 时间，不接受不存在的日期。
10. `repository.revision` 缺失时保持缺失，不接受占位值。

稳定 ID 使用 UTF-8、真实 `U+001F` 和 SHA-256。必须用协议固定向量和两个示例中的全部 finding 验证 Java 计算结果。

## v1 兼容要求

- 原 `AnalyzerReport` 的 v1 JSON 读取行为保持兼容。
- v1 仍可忽略历史允许的未知字段。
- v1 的绝对 `repository.path` 不进入新的 v2 模型或 v2 快照。
- 原有 Reader、Runner、API 和持久化测试继续通过。
- 不把 v1 报告转换并伪装成 v2。
- 不删除 `quality`、`risks` 或 `dependencies` 的 v1 支持。

## v2 持久化白名单

为 v2 增加独立快照构造和校验，不复用 v1 的字段集合：

- 只保存 v2 批准字段。
- 不保存绝对路径、源码、进程输出、堆栈或未知 evidence。
- 保存前重新验证稳定 ID、排序和敏感文本。
- `FAILED` 报告不能伪装成可用 SUCCESS/PARTIAL_SUCCESS 记录。
- 数据库现有 `protocol_version` 和 JSON 列能够容纳 v2 时，不为字段内容机械增加新列。
- 如确实需要数据库迁移，先停止并回主线说明，不能顺手扩大任务。

本任务不要求把真实 C++ v2 进程接到 API；SnapshotFactory 使用固定 v2 fixture 测试即可。

## 自动化测试要求

至少覆盖：

### 版本和文件边界

- null、文件不存在、目录、超出大小上限。
- JSON 损坏、根节点非对象、重复键。
- 版本缺失、null、非字符串、`1`、`2`、`2.1` 和未知版本。
- v1 fixture 继续通过，v2 两个正式示例通过。

### Schema 反例

- 未知顶层或嵌套字段。
- Windows/Unix 绝对路径、URI、反斜杠和路径逃逸。
- 仓库级伪造空路径/第 0 行。
- FILE finding 缺失 location。
- 未批准 evidence 字段、空 remediation 和非法枚举。
- 错误的状态、reviewability、字段和 limitation 组合。
- 无效日期形状和缺少时区。

### 语义反例

- 结束位置早于开始位置。
- 计数关系错误。
- 数组未排序、稳定键重复、语言名重复。
- findingId 不一致。
- 规则与 evidence 类型不匹配。
- 不存在的日历日期。
- 敏感路径、凭据样式、进程输出和堆栈文本。

### 快照与回归

- v2 SUCCESS 和 PARTIAL_SUCCESS 产生严格白名单快照。
- v2 FAILED 不被当作可用报告。
- 手工构造的额外字段不能进入快照。
- v1 快照字段和现有数据库集成测试继续通过。
- 正式 Schema 的 classpath 资源与仓库源文件一致。

## 验证命令

从 `openpulse-platform` 目录执行：

```powershell
.\mvnw.cmd clean verify
```

必须报告 Maven、Java 和新增 Schema 库版本，普通测试与集成测试数量，两个正例和全部反例，固定 findingId，v1 回归，以及 MySQL/Testcontainers 是否真实运行。Docker 不可用时如实说明。

不得为了让测试通过关闭严格未知字段、跳过 Schema、删除 v1 回归或伪造 Docker 结果。

## 明确不做

- 不修改 C++ Analyzer。
- 不增加 `--protocol` 参数或改变 ProcessRunner 命令。
- 不让 HTTP API 默认返回 v2。
- 不切换 CLI 默认协议。
- 不删除或弃用 v1。
- 不实现新分析规则、Tree-sitter、AI、Skill、MCP 或前端。
- 不运行正式验证样本或填写实验结果。
- 不重构无关 Java 模块。

## 完成标准

1. Java 能精确分派 v1 和 v2，未知版本拒绝。
2. v2 使用明确类型、Schema 和完整语义校验。
3. 正式正例通过，结构和语义反例均失败。
4. 固定 findingId 在 Java 中复现一致。
5. v1 Reader、Runner、API 和快照回归通过。
6. v2 快照严格白名单且不扩大敏感信息入口。
7. 没有改变 C++、HTTP 行为或默认协议。
8. `mvnw clean verify` 成功，测试数量和环境如实记录。
9. `git diff --check` 通过，工作区只包含允许的 Java、测试、资源和 Maven 文件。
10. 创建一个范围单一的本地 commit，不 push、不创建 PR，先回主线验收。

## 回主线对话时提交的信息

```text
分支：
commit：

版本分派入口：
v1 模型/Reader：
v2 模型/Reader：
Schema 验证库和版本：
Schema 资源来源：
语义校验器：
v2 快照白名单：

正例结果：
Schema 反例结果：
语义反例结果：
findingId 固定向量：
v1 回归结果：
敏感信息检查：

全部测试数量和结果：
MySQL/Testcontainers 结果：
验证命令：
git diff --check：
工作区状态：
未完成或阻塞项：
我理解的新名词：
遇到的问题：
```

## 新对话启动提示词

```text
我是 OpenPulse AI 的 Java / Platform 负责人，也是 Java 初学者。请先阅读：

- docs/tasks/10-java-analyzer-report-v2-reader.md
- docs/protocol/analyzer-report-v2.md
- docs/protocol/analyzer-report-v2.schema.json
- docs/protocol/analyzer-report-v2-migration.md
- docs/examples/analyzer-report-v2.success.sample.json
- docs/examples/analyzer-report-v2.partial-success.sample.json

然后严格完成“OpenPulse AI 第十步：Java 严格读取报告协议 v2”。

从最新 main 创建 feat/platform-report-v2-reader 分支。保留 v1 兼容行为，
增加精确版本分派、独立 v2 明确模型、Draft 2020-12 Schema 校验、
协议语义校验、稳定 findingId 复算和 v2 持久化白名单。

本任务不修改 C++，不改变 ProcessRunner 命令、HTTP API 或默认协议，
不实现新规则、AI、前端或实验。每使用一个新名词前先用简单语言解释，
再执行对应操作并验证。完成后创建一个范围单一的本地 commit，
但不要 push 或创建 PR，按任务书格式返回主线验收报告。
```
