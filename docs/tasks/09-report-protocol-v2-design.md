# OpenPulse AI 第九步：报告协议 v2 设计

## 任务定位

- 负责人：Java 与 C++ 共同负责，C++ 负责人确认生产端可实现，Java 负责人确认消费端可安全解析
- 建议分支：`docs/report-protocol-v2-design`
- 所属阶段：P3 本地产品与透明报告
- 前置任务：任务 8 验证基线已经激活并合并到 `main`
- 任务性质：只设计协议、JSON Schema、示例和迁移方案

本任务不修改 Java 或 C++ 实现。它要先把“C++ 生成什么、Java 和 Skill 可以相信什么、协议如何升级”写成双方都能执行的契约，再分别进入生产端和消费端实现。

协议 v1 是当前已经运行的兼容边界。设计 v2 不等于立即删除 v1，也不允许在同一个任务里悄悄改变 CLI 默认输出。

## 为什么单独做这一步

JSON 能被解析，不代表它已经是一份可靠协议。当前 v1 存在这些已知限制：

- `repository.path` 可能包含用户电脑的绝对路径。
- 报告没有独立的分析器版本和规则集版本。
- `risks` 没有统一的作用范围、完整位置和修复方向。
- `evidence` 是自由对象，Java 持久化只能依靠固定白名单防止敏感字段进入数据库。
- `quality` 是占位分数，不能作为可信健康评分。
- `dependencies` 当前为空，但字段名称容易让用户误以为已经完成依赖分析。
- 数组顺序、稳定发现 ID 和规范化边界没有成为正式契约。

如果 C++ 和 Java 分别凭感觉修改这些字段，就会产生“C++ 能输出但 Java 读不了”或“Java 接受了不安全内容”的问题。因此先完成契约设计，再拆成两个实现任务。

## 开始前阅读

- `README.md`
- `docs/00-glossary.md`
- `docs/02-analyzer-json-protocol.md`
- `docs/04-architecture.md`
- `docs/06-roadmap.md`
- `docs/09-product-direction-v0.2.md`
- `docs/validation/v0.2-validation-baseline.md`
- `docs/validation/repository-runs.csv`
- `docs/validation/finding-reviews.csv`
- `docs/examples/analyzer-report-v1.sample.json`
- `openpulse-analyzer/src/Analyzer.cpp`
- `openpulse-analyzer/src/main.cpp`
- `openpulse-analyzer/tests/test_analyzer.cpp`
- Java `AnalyzerReport`、`AnalyzerReportReader`、`AnalyzerProcessRunner`
- Java `AnalysisReportSnapshotFactory` 及对应测试

阅读时分别列出“v1 当前真实行为”和“v2 目标行为”，不要把文档示例里尚未实现的规则当成现有能力。

## 本步骤需要理解的名词

新对话在使用这些概念前，应先用简单语言解释：

- **协议（Protocol）**：不同模块交换数据时共同遵守的字段、类型和行为约定。
- **契约（Contract）**：生产端承诺怎样输出，消费端据此校验和使用。
- **生产端（Producer）**：生成报告的 C++ Analyzer。
- **消费端（Consumer）**：读取报告的 Java、CLI 展示层和后续 Skill。
- **JSON Schema**：一份机器可读取的 JSON 结构说明，可以校验必填字段、类型、枚举和格式。
- **不变量（Invariant）**：无论实现怎样变化都必须成立的规则，例如文件位置只能是仓库内相对路径。
- **破坏性变更（Breaking Change）**：让旧消费端无法继续正确读取的变化，例如删除必填字段或改变字段类型。
- **兼容性变更（Compatible Change）**：旧行为仍成立的扩展，但是否允许增加字段仍由本协议的严格模式决定。
- **严格模式**：对象只允许 Schema 明确列出的字段，未知字段不能被悄悄接受。
- **稳定标识（Stable ID）**：同一版本、同一仓库内容和同一条发现重复运行时保持一致的标识。
- **作用范围（Scope）**：发现针对整个仓库还是某个文件。
- **位置（Location）**：文件相对路径以及起止行列；仓库级发现可以明确表示位置不适用。
- **证据（Evidence）**：支持一条发现的可验证事实，不是整段源码或模型猜测。
- **修复方向（Remediation）**：用户下一步可以检查或修改什么。
- **规范化（Normalization）**：比较报告前只移除约定的动态字段并按协议规则稳定序列化。
- **夹具（Fixture）**：用于测试解析器和 Schema 的固定样例文件。
- **弃用（Deprecation）**：暂时继续支持旧协议，同时明确停止新增能力和未来移除条件。

## 本任务交付物

本任务应创建或更新：

```text
docs/02-analyzer-json-protocol.md
docs/protocol/analyzer-report-v2.md
docs/protocol/analyzer-report-v2.schema.json
docs/protocol/analyzer-report-v2-migration.md
docs/examples/analyzer-report-v2.success.sample.json
docs/examples/analyzer-report-v2.partial-success.sample.json
```

职责如下：

- `docs/02-analyzer-json-protocol.md`：继续保存 v1 现状，并把读者引导到 v2 设计和迁移说明；不得把尚未实现的 v2 写成当前能力。
- `analyzer-report-v2.md`：v2 的人类可读规范，解释每个字段、枚举、不变量、失败语义和安全限制。
- `analyzer-report-v2.schema.json`：可由工具验证的正式结构，使用 JSON Schema Draft 2020-12。
- `analyzer-report-v2-migration.md`：记录 v1 到 v2 的字段映射、双版本读取顺序、默认版本切换和弃用条件。
- 两个示例：分别展示完整成功报告和包含受控缺项的部分成功报告；只能使用虚构仓库和虚构证据。

如果设计阶段发现必须增加第三个最小失败示例，可以在任务报告中说明原因后添加，但不能生成真实实验结果。

## 协议设计原则

### 1. C++ 是唯一分析事实来源

报告中的仓库事实和规则发现只能由 C++ Analyzer Core 产生。Java、Skill 和未来入口可以校验、保存、筛选和解释，但不能各自重新实现同名规则。

### 2. 不使用 AI 也必须完整

人只查看终端摘要和 JSON，也应理解发现是什么、证据在哪里、下一步做什么。协议不能依赖 AI 补写缺失依据。

### 3. 默认最少暴露

报告只保存验证所需的最少信息。默认不包含绝对路径、源码正文、stdout、stderr、异常堆栈、环境变量、用户名或疑似凭据。

### 4. 同一输入应产生稳定内容

除明确声明的运行时字段外，相同仓库 commit、分析器版本、规则集版本和配置应生成相同内容。不能依靠验证脚本删除真实字段来制造一致。

### 5. 协议必须机器可验证

Markdown 负责解释，JSON Schema 负责约束。两者冲突时任务不能验收，必须先统一；不能只写一份看起来合理的 JSON 示例。

## 必须冻结的顶层语义

最终规范必须逐项决定并写清：

- 协议版本字段及 `2.0` 的精确表示。
- 分析器版本和规则集版本的位置、格式和必填性。
- `taskId` 的用途、格式和是否只用于一次运行。
- 报告状态 `SUCCESS`、`PARTIAL_SUCCESS`、`FAILED` 的含义。
- 仓库身份信息允许保存什么，明确不得保存本机绝对路径。
- 文件与行数统计、语言统计和结构事实是否沿用 v1，以及每个数字的边界。
- 发现集合的正式字段名；如果把 v1 的 `risks` 改为 `findings`，迁移表必须明确记录。
- 扫描限制或跳过项如何表达，避免把“不支持”误写成“没有问题”。
- `generatedAt` 等动态字段如何表示和规范化。
- v1 的 `quality` 与空 `dependencies` 在 v2 中删除、弃用或替换的结论。

顶层设计至少应覆盖以下语义，但字段分组和最终名字要在双方评审后冻结：

```text
protocol identity
analyzer identity
report status
repository identity without host path
summary and language facts
structure facts
findings
limitations or skipped work
generated time
```

不得为了“以后可能有用”加入用户账号、数据库 ID、GitHub Token、AI 结果或团队权限字段。

## 发现模型

每条发现必须能够独立理解和复核。最终模型至少表达：

- 稳定 `findingId`。
- 稳定 `ruleId`。
- `type`：只允许 `PROJECT_STRUCTURE`、`TEXT`、`SYNTAX_TREE`。
- 严重程度：与当前 `LOW`、`MEDIUM`、`HIGH`、`CRITICAL` 的兼容结论。
- `scope`：v0.2 至少区分 `REPOSITORY` 和 `FILE`。
- `location`：文件级发现使用仓库内相对路径和起止行列；仓库级发现明确表示位置不适用。
- 简短、确定性的用户消息。
- 结构化证据。
- 非空修复方向。

### 稳定 findingId

规范必须给出可复现算法或明确组成字段。至少考虑：

```text
ruleId + scope + relative path + normalized location + normalized evidence
```

不能使用 `taskId`、生成时间、临时目录、绝对路径或随机数。哈希算法、字符编码、分隔方法和空位置表示都必须写清，并给出至少一个固定输入和预期结果。

### 位置规则

- JSON 内统一使用 `/` 作为路径分隔符，即使分析器运行在 Windows。
- 路径必须相对仓库根目录，不能以 `/`、`\\`、盘符、URI 或 `..` 开头。
- 文件级发现的起始行至少为 1；结束位置不能早于开始位置。
- 必须明确列和行采用 1-based 还是 0-based，v2 内不得混用。
- 仓库级发现不能使用空字符串和第 0 行冒充文件位置，应由 `scope` 和 Schema 明确表达“不适用”。

### 证据规则

`evidence` 不能继续是不受约束的任意对象。协议设计必须选择一种可扩展但可校验的结构，并满足：

- 每种证据都有明确类型或键集合。
- Schema 默认拒绝未知字段。
- 证据只包含证明规则所需的事实，不复制整段源码。
- 字符串有公开长度上限，数组有公开条数上限。
- 相对路径继续遵守位置安全规则。
- 疑似凭据、stdout、stderr、堆栈和本机路径不能进入证据。
- 每个 v0.2 首批规则都能在规则任务中声明自己允许的证据结构。

本任务只设计通用证据容器和首批结构规则所需的证据示例，不实现所有未来规则的证据类型。

## 状态、限制和失败语义

报告状态和进程退出不是同一个概念，规范必须分别解释：

- 发现问题不等于程序执行失败。
- `SUCCESS` 表示计划扫描范围已完成。
- `PARTIAL_SUCCESS` 表示报告仍可用，但存在明确记录的跳过项或能力限制。
- `FAILED` 是否允许生成最小报告、哪些字段仍必填，必须在 Schema 和迁移说明中一致。
- 进程没有生成合法报告时，由 CLI/Java 运行器记录失败，不能伪造一个成功报告。

限制信息至少要让用户区分：

- 文件因权限、大小、类型或编码被跳过。
- 某种语言或规则类型尚不支持。
- 达到公开资源限制后停止部分扫描。
- 报告是否仍足以用于人工复核。

限制说明不能包含原始异常、绝对路径或命令输出。

## 确定性与规范化

协议必须为每个数组声明它是有序列表还是无序集合：

- 有序列表必须说明顺序的业务含义。
- 无序集合必须指定稳定排序键。
- 对象键顺序不属于 JSON 语义，但规范化输出按验证基线要求排序。
- 数字使用明确的整数范围，不能让同一个值在不同实现中出现 `1`、`1.0` 等差异。
- 文本统一使用 UTF-8，时间统一使用带时区 ISO 8601。

迁移说明必须列出验证基线规范化时允许移除的动态字段。不能移除发现、状态、版本、路径、统计、证据、限制或修复方向。

## JSON Schema 要求

Schema 必须：

- 声明 Draft 2020-12 和稳定 `$id`。
- 顶层及嵌套对象默认使用 `additionalProperties: false`。
- 明确所有必填字段、类型、枚举、最小值、最大值和字符串格式。
- 对相对路径使用可解释的约束，并在 Markdown 补充 Schema 正则无法完整表达的校验。
- 使用条件约束区分仓库级和文件级发现。
- 对状态与限制字段建立一致约束。
- 不允许 `null` 代替缺失，除非协议明确把 `null` 定义为唯一的不适用表示。
- 不引用需要联网下载才能完成本地校验的远程 Schema 片段。

两个示例必须通过该 Schema；至少再构造以下反例确认会失败，但反例不必提交：

- 缺少必填版本。
- 出现未知顶层字段。
- 文件位置使用 Windows 绝对路径。
- 仓库级发现伪造空文件和第 0 行。
- 证据包含未批准字段。
- 修复方向为空。
- 枚举值拼写错误。

## v1 到 v2 迁移

迁移文档必须提供字段映射表，至少处理：

- `protocolVersion`。
- `repository.path` 与 `repository.name`。
- `risks` 到 v2 发现集合。
- `file`、`line` 到 v2 位置对象。
- `message`、`evidence` 与新增修复方向。
- `quality` 占位分数。
- 空 `dependencies`。
- `status`、`taskId` 和 `generatedAt`。

迁移顺序默认采用：

1. 先合并本协议设计，不改变任何运行时行为。
2. Java 新增 v2 模型与严格校验，同时继续读取 v1。
3. C++ 新增可明确选择的 v2 输出，并保留 v1 路径。
4. 使用固定 fixture 完成 C++ 生成、Schema 校验和 Java 读取的联合测试。
5. 联合测试通过后，才由独立任务决定 CLI 默认版本何时切换到 v2。
6. v1 的移除必须有单独决策、迁移说明和已知调用方核对，不能在 v2 首次实现时删除。

Java 不能把未知协议静默当成 v1 或 v2；C++ 也不能只改字段却继续标记为 `1.0`。

## 两人评审分工

### C++ 负责人检查

- 每个必填字段都能由 Analyzer 在确定性条件下生成。
- `findingId`、数组顺序和证据结构可以稳定实现。
- 仓库级发现不再依赖空文件名和第 0 行。
- v2 输出不会迫使 CLI 在本任务中提前改变默认行为。
- Schema 没有要求当前 C++ 必须伪造尚未实现的分析能力。

### Java 负责人检查

- Schema 可以映射为明确的 Java 类型，而不是到处使用 `Map<String, Object>`。
- 未知字段、非法枚举、负数、越界位置和不安全路径能够被拒绝。
- v1 与 v2 可以明确分派，不会互相误读。
- 持久化白名单能按 v2 单独演进，不会扩大敏感信息入口。
- 失败报告和部分成功报告不会破坏现有任务状态约束。

### 共同确认

- v1 到 v2 字段映射和弃用策略。
- 协议版本规则。
- 发现 ID、位置、证据和修复方向语义。
- 哪些数组排序、哪些字段参与规范化哈希。
- 哪些决定留给 Java 实现任务、C++ 实现任务和 CLI 分发任务。

最终协议文档应包含双方评审表；任一项存在未解决分歧时保持 `DRAFT`，不得宣称协议 `2.0` 已冻结。

协作分为两个阶段：

1. Java 负责人先完成本地 `DRAFT` 初稿、Schema 和示例校验，创建本地 docs commit 后回主线验收；此时不代替 C++ 负责人签字。
2. 主线验收通过后再 push 并创建 PR。C++ 负责人检查生产端可实现性，必要时在同一分支修订；Java 负责人复核最终差异。双方意见解决后，才把协议状态改为 `ACCEPTED` 并合并。

## 验证要求

本任务只有文档、Schema 和虚构示例变化，不运行 Java/C++ 产品测试。必须完成：

- 所有 JSON 文件能由标准 JSON 解析器读取。
- Schema 自身通过 Draft 2020-12 校验。
- 两个正例通过 Schema。
- 规定的反例全部被 Schema 拒绝。
- Markdown 中的字段名、枚举和必填性与 Schema 一致。
- 示例不包含绝对路径、凭据样式文本、真实仓库内容或个人信息。
- 内部相对链接全部存在。
- `git diff --check` 通过。
- `git diff --name-only` 只包含任务允许的文档、Schema 和示例。

验证报告必须写明使用的 Schema 校验工具和版本。不得因为本机没有工具就声称已通过；如被环境阻塞，应保留 `DRAFT` 并如实报告。

## 明确不做

本任务禁止提前加入：

- Java `AnalyzerReport`、Reader、持久化或 API 代码修改。
- C++ 报告生成、CLI 参数、退出码或扫描代码修改。
- Tree-sitter、Clang AST 或新规则实现。
- Windows 安装包、发布脚本和 PATH 配置。
- Codex Skill、MCP、GitHub Action 或 Vue 页面。
- AI API 调用、AI 对照实验或正式用户试用。
- 黑盒健康总分、规则权重或项目排名。
- 真实第三方源码、真实报告、凭据或个人绝对路径。
- 删除协议 v1 或立即切换默认输出。

## 本地初稿完成标准

以下条件全部满足后，初稿才可以回主线对话申请协作评审：

1. v2 人类可读规范、JSON Schema、迁移文档和两个虚构示例全部存在。
2. 顶层字段、状态、版本、发现、位置、证据、限制和修复方向已经在初稿中逐项定义且内部一致。
3. Schema 与 Markdown、示例完全一致，正例和反例验证结果可复现。
4. 报告默认不包含绝对路径、源码正文、进程输出、堆栈或凭据。
5. 稳定 `findingId` 算法、数组排序和规范化边界有固定示例。
6. v1 到 v2 映射、双版本读取和默认版本切换条件写清楚。
7. Java 负责人完成消费端自查，协议状态保持 `DRAFT`，C++ 负责人评审栏保持 `PENDING`。
8. 改动只有文档、Schema 和虚构示例，没有产品代码、依赖或实验数据。
9. 创建一个范围单一的本地 docs commit，不 push、不创建 PR，回主线对话复核并准备协作 PR。

## 最终接受与合并标准

以下条件全部满足后，第九步才算真正完成：

1. 本地初稿通过主线验收并创建协作 PR。
2. C++ 负责人逐项确认必填字段、稳定 ID、排序、证据和状态语义能够实现。
3. Java 负责人复核 C++ 评审期间的全部最终差异。
4. 双方意见和 Schema/文档不一致项全部解决。
5. 协议文档记录两位负责人和评审时间，状态由 `DRAFT` 改为 `ACCEPTED`。
6. 最终 Schema、正例、反例、内部链接和 `git diff --check` 重新验证通过。
7. PR 经过正式 review 后合并到 `main`；在此之前不得开始 Java/C++ v2 实现任务。

## 回主线对话时提交的信息

任务完成后请按以下格式汇报：

```text
分支：
commit：

协议状态：
协议版本：
Schema Draft：
交付文件：

顶层必填字段：
发现必填字段：
规则类型：
作用范围：
路径和位置规则：
证据结构与安全上限：
稳定 findingId 算法：
数组排序规则：
状态与限制语义：

v1 -> v2 主要变化：
双版本迁移顺序：
默认版本切换条件：

JSON 解析检查：
Schema 自校验：
正例验证：
反例验证：
内部链接检查：
git diff --check：
工作区状态：

Java 负责人评审：
C++ 负责人评审：
未解决分歧：

确认没有执行：
- Java/C++ 产品代码修改
- CLI 默认版本切换
- 新规则、Tree-sitter 或安装器实现
- Skill、MCP、AI 实验或用户试用

我理解的新名词：
遇到的问题：
后续应拆分的实现任务：
```

## 新对话启动提示词

在相同项目工作区创建新对话后发送：

```text
请先阅读 README.md、docs/00-glossary.md、docs/02-analyzer-json-protocol.md、
docs/04-architecture.md、docs/06-roadmap.md、docs/09-product-direction-v0.2.md、
docs/validation/v0.2-validation-baseline.md 和
docs/tasks/09-report-protocol-v2-design.md，并检查当前 main、C++ Analyzer
报告生成与测试、Java AnalyzerReport/AnalyzerReportReader、
AnalyzerProcessRunner 和 AnalysisReportSnapshotFactory。

我是 Java 初学者，这是一项 Java/C++ 共同设计任务。每使用一个新名词前
先用简单语言解释，再执行对应操作并验证。从最新 main 创建
docs/report-protocol-v2-design 分支。

请严格完成“OpenPulse AI 第九步：报告协议 v2 设计”。只创建协议说明、
JSON Schema、虚构成功/部分成功示例和 v1 到 v2 迁移方案。
协议必须满足已激活验证基线，默认使用安全相对路径，报告携带分析器和
规则集版本，每条发现包含稳定 ID、规则编号、类型、严重程度、作用范围、
位置、受约束证据和修复方向，并明确状态、限制、数组排序和规范化规则。

先保持协议状态 DRAFT。完成 Java 消费端检查后，请生成一份给 C++ 负责人
逐项评审的清单；收到真实评审意见前不要代替他签字，也不要擅自改成 ACCEPTED。

不要修改 Java、C++、Vue 或构建配置；不要实现 CLI、Tree-sitter、新规则、
安装器、Codex Skill、MCP 或 AI；不要删除协议 v1，不要切换默认输出，
不要使用真实第三方源码或伪造实验结果。

使用明确版本的 JSON Schema Draft 2020-12 校验工具验证 Schema、两个正例
和规定反例，检查内部链接和 git diff。创建一个范围单一的本地 docs commit，
但不要 push 或创建 PR。最后按任务书格式生成回主线对话的验收报告。
```
