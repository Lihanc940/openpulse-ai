# OpenPulse AI 第十二步：报告协议 v2 联合契约验收

## 任务定位

- 任务状态：`PREPARED`，执行状态：`NOT_STARTED`
- 建议分支：`test/analyzer-report-v2-contract`
- 共同负责人：Java / Platform 负责人、C++ Analyzer 负责人
- 已完成前置：任务 10 已由 PR #24 合并到 `main`，合并提交为 `897964c`
- 待完成前置：任务 11 的 C++ 显式 v2 输出经过主线验收并合并到 `main`
- 任务性质：固定联合 fixture，验证生产端与消费端遵守同一份已接受协议

本任务把 C++ 真实生成的协议 `2.0` 报告交给仓库正式 Draft 2020-12 Schema 和 Java `AnalyzerReportReader` 验证，证明生产端与消费端能够在同一份报告上协作。它同时保留默认 v1 回归，不切换默认协议，不删除 v1，也不执行 v0.2 正式仓库实验。

本文档只是任务 12 的准备工作。文档中列出的 fixture、命令、哈希和验收项在任务 11 合并后才执行；未勾选的项目和空白结果不能被解释为已通过。

## 为什么必须单独做这一步

任务 10 证明 Java 能严格读取人工固定的 v2 示例，任务 11 负责让 C++ 能显式生成 v2。两边各自测试通过，仍不能自动证明以下边界已经对齐：

- C++ 写出的真实字段、枚举、排序和稳定 ID 正好能被 Java 接受。
- C++ 使用的实际 Analyzer / rule set 版本与联合基线一致。
- 默认命令仍生成 v1，只有显式 `--protocol 2.0` 才生成 v2。
- 同一输入重复运行时，除 `taskId` 和 `generatedAt` 外的内容完全一致。
- `PARTIAL_SUCCESS`、`FAILED`、非法报告和进程退出码不会在模块交界处改变含义。
- 报告经过 Java 持久化白名单后不会重新引入绝对路径或未批准字段。

因此，本任务只验收已经实现的协议边界，不增加扫描能力，也不把测试通过直接等同于默认版本可以切换。

## 本步骤需要理解的名词

- **联合契约测试**：生产端生成真实输出，消费端直接读取同一份输出，检查双方是否遵守共同协议。
- **fixture（测试夹具）**：内容固定、只用于测试的虚构输入目录或场景描述。
- **测试预期（oracle）**：测试用来判断结果对错的固定断言或规范化 JSON，不是实验结论。
- **规范化报告**：移除顶层 `taskId` 和 `generatedAt` 后，按协议固定规则序列化的报告。
- **规范化哈希**：对规范化报告的 UTF-8 字节计算 SHA-256，用于快速发现未预期的输出变化。
- **生产构造逻辑**：正式 C++ v2 输出实际调用的 builder / serializer；测试不能复制另一套“看起来一样”的生成器。
- **测试边界 fixture**：把受控扫描事实直接交给生产构造逻辑，用于稳定覆盖权限错误等难以跨平台制造的场景。
- **回归门**：新能力通过的同时，旧的默认 v1、Java 回归和 C++ 回归也必须继续通过。

## 开始门槛

任务 12 只有在以下条件全部满足后才能开始执行（先阅读并钉死下方「联合基线：开工前必须钉死」一节，再进入这些门槛）：

1. 从包含任务 10 和任务 11 的最新 `main` 创建任务分支，记录起始 commit。
2. 任务 11 已提供真实 `--protocol 2.0` 输出、默认 v1 回归、稳定 ID、排序、状态与限制测试。
3. C++ 任务报告写明 Analyzer 版本来源、rule set ID / version、SHA-256 实现及许可证、Schema 验证器和真实测试结果。
4. Java 与 C++ 负责人确认本任务不需要修改已接受的协议字段或含义。
5. 若任务 11 尚未合并、真实 v2 输出不能通过正式 Schema，或双方发现协议分歧，本任务保持 `NOT_STARTED` 或标记 `BLOCKED`，不能用手写 JSON 代替生产端结果。

开始后必须先重新阅读：

1. `docs/protocol/analyzer-report-v2.md`
2. `docs/protocol/analyzer-report-v2.schema.json`
3. `docs/protocol/analyzer-report-v2-migration.md`
4. `docs/tasks/10-java-analyzer-report-v2-reader.md`
5. `docs/tasks/11-cpp-analyzer-report-v2-output.md`
6. C++ v2 builder、CLI 和相关测试
7. Java `AnalyzerReportReader`、`AnalyzerReportV2SemanticValidator`、`AnalysisReportV2SnapshotMapper` 和相关测试

## 联合基线：开工前必须钉死

以下五项在任务 12 执行前必须由 Java 与 C++ 负责人共同确认并写入起始记录。本准备提交只钉住基线要求，不代替任务 11 的实现，也不擅自决定协议字段。

### 1. 生产端身份

- `analyzer.name` 固定为 `openpulse-analyzer`。
- `analyzer.version` 必须来自 `CMakeLists.txt` 的 `project(openpulse-analyzer VERSION ...)`（当前为 `0.1.0`），不得复制 `docs/examples/*.sample.json` 里的 `0.2.0`。
- `ruleSet.id` 固定为 `openpulse-default`。
- `ruleSet.version` 固定为 `0.2.0`，与已接受的示例和 Java 规则目录保持一致。

> 约束：Java `AnalyzerReportV2SemanticValidator` 目前只识别 `("openpulse-default", "0.2.0")` 这一个规则目录键。任务 11 若使用其他键或版本，Java 会以 `SEMANTIC_VIOLATION` 拒绝整份报告，而这不是契约缺陷；修复需要改动 Java 产品代码，超出本任务“原则上不修改产品代码”的范围。因此这里钉死上述值，任何改键/改版需求先回主线另立任务。

### 2. SUCCESS 与限制策略

- `complete` 与 `missing-structure` 两个目录 fixture 的目标状态都是 `SUCCESS + COMPLETE`，`limitations` 为空；`findings` 允许为空或有发现。
- 生产者不得仅因“尚未实现语法树 / 文本规则”就为每种语言生成 `UNSUPPORTED_CAPABILITY`。本任务的计划扫描范围是“仓库统计 + 三条结构规则”，语法树与文本规则不在范围内。
- `UNSUPPORTED_CAPABILITY` 只用于：生产者对某语言/规则类型承诺了分析能力，但本次明确没有执行。
- 若任务 11 的真实实现按协议要求必须生成这类限制，导致无法达到 `SUCCESS`，先回主线按协议分歧处理（`BLOCKED`），不得改 Schema、改产品代码或改 fixture 来迁就。

### 3. 三条结构的固定向量

任务 11 必须在回主线报告中发布当前三条规则的固定向量，格式与协议第 8 节一致。任务 12 开工前必须确认下表第三列全部有真实 SHA-256，且由 C++ 生产构造逻辑复算得出，不得手工改写：

| ruleId | expectedPaths（码点升序、去重） | 固定 `findingId`（`sha256:` + 64 位小写十六进制） |
| --- | --- | --- |
| `MISSING_README` | 当前全仓库未发布，任务 11 必须补齐 | 同上，任务 11 必须补齐 |
| `MISSING_LICENSE` | `["COPYING","LICENSE"]` | `sha256:b9dcda3c6f619d65163780c30df7792cc3baf402ba42e2adeab4d8031ac161a8` |
| `MISSING_CI` | `[".github/workflows","azure-pipelines.yml"]` | `sha256:1c090a45b827a6ed23196817880ef2a05968acf5280a0158a7b67ab0ff98eff7` |

`MISSING_CI` 一行的值以已接受的示例为准，任务 11 按实际规则目录复算确认；`MISSING_README` 是唯一在协议与示例中都没有发布向量的规则，属于任务 11 报告必须补齐的缺口。

### 4. Java 联合测试的交接接口

- 脚本把 C++ 生成的报告目录通过明确的系统属性交给 Java 联合测试，固定为 `-Dopenpulse.contract.report.dir=<临时目录>`（命名可在实现阶段由双方一致调整，但必须在脚本与测试里唯一对应）。
- 该属性缺失时，依赖 C++ 真实输出的用例（C03–C06、C08）必须显式 `SKIPPED` 并单独计数，不得当作通过，也不得让 `mvn clean verify` 变红。
- 不依赖外部输出的断言（Schema 反例、语义反例、白名单、固定向量复算）保持默认运行。

### 5. 规范化 golden 与反例输入

- `expected/*.v2.normalized.json` 已移除 `taskId`/`generatedAt`，不能直接作为 Java `AnalyzerReportReader` 或 N01–N10 变异器的输入（Schema 把 `taskId` 列为必填）。
- Java 用例与反例变异一律基于原始 C++ 输出（含固定 `taskId`/`generatedAt`）。若要让 Java 用例在无 C++ 环境下自包含运行，需额外提交一份固定 `taskId`/`generatedAt` 的 raw v2 报告作为受控输入。
- `cases.json` 中的预期哈希与 `expected/*.json` 不一致时判定失败；以 golden 为权威、`cases.json` 为冗余校验，禁止用实际输出反向覆盖 golden。

## 联合 fixture 总体设计

### 1. 单一共享入口

执行任务 12 时建立以下共享目录；具体占位文件只在任务分支中创建，本准备提交不提前创建 fixture 或预期结果：

```text
docs/fixtures/analyzer-report-v2-contract/
  README.md
  cases.json
  repositories/
    complete/
    missing-structure/
  scenarios/
    partial-success.json
    failed.json
  expected/
    complete.v2.normalized.json
    missing-structure.v2.normalized.json
    partial-success.v2.normalized.json
    failed.v2.normalized.json
  invalid/
    mutations.json
```

职责如下：

- `README.md` 记录每个 fixture 的虚构性、预期状态、允许动态字段和更新规则。
- `cases.json` 是用例清单，只保存输入、生成入口、预期版本/状态、预期 finding rule IDs、预期失败类别和规范化哈希；不保存“通过/失败”等执行结果。
- `repositories/` 是 CLI 真实扫描的最小目录树。
- `scenarios/` 是测试专用的受控场景描述，只允许 C++ 测试代码把它转换为扫描事实，再调用与产品相同的 v2 构造逻辑。
- `expected/` 保存移除 `taskId` 与 `generatedAt` 后的完整规范化输出，作为可评审的契约预期。
- `invalid/mutations.json` 描述如何从一份有效 C++ 输出产生反例，避免长期维护多份会漂移的大型 JSON 副本。

`cases.json`、场景描述和预期文件都必须有明确的测试格式版本。它们是测试输入和预期，不是协议的一部分，不能被产品代码读取。

### 2. 真实目录 fixture

#### `complete`

这是一个很小的虚构仓库，至少包含：

- `README.md`
- `LICENSE`
- `.github/workflows/ci.yml`
- 一个受支持语言的最小源码文件
- 一个最小测试文件
- 一个 Analyzer 已真实识别的构建文件

它用于验证：

- 不传 `--protocol` 时仍输出 `1.0`。
- 显式 `--protocol 1.0` 仍输出 `1.0`。
- 显式 `--protocol 2.0` 输出可用的 `SUCCESS + COMPLETE` 报告。
- `repository.root` 为 `.`，输出不含 fixture 的绝对路径。
- 当前三个结构规则不会产生发现。

#### `missing-structure`

这是另一个小型虚构仓库，有最小源码和构建文件，但有意不放置 README、许可证和已识别的 CI 配置。它用于验证 C++ 当前真实实现的三个规则：

- `MISSING_README`
- `MISSING_LICENSE`
- `MISSING_CI`

预期仍是 `SUCCESS + COMPLETE`。发现仓库问题不等于扫描失败；三条 finding 必须使用 `PROJECT_STRUCTURE`、`REPOSITORY`、`location: null` 和 `EXPECTED_PATHS_ABSENT`，并按 `findingId` 排序。

fixture 中不得加入 `LONG_FUNCTION`、文本规则、依赖分析或其他任务 11 没有实现的能力。目录不能包含真实第三方仓库、真实用户数据、符号链接、Git 元数据、凭据样式文本或个人绝对路径。

### 3. `PARTIAL_SUCCESS` 与 `FAILED` 场景 fixture

权限错误、文件占用和某些遍历失败在 Windows、Linux、管理员账户与 CI 中表现不同，不能靠修改真实文件权限来制造稳定 fixture。因此：

- `partial-success.json` 描述至少一个安全相对路径的受控跳过事实，并预期 `PARTIAL_SUCCESS + PARTIAL` 和相应 limitation。
- `failed.json` 描述不可继续的受控扫描失败，并预期 `FAILED + NOT_USABLE`、空 findings、至少一个 `SCAN_FAILED`。
- C++ 测试适配器只负责把描述转换成已有扫描事实类型；报告必须由任务 11 的生产 v2 builder 生成。
- 不得在产品 CLI 增加 `--test-mode`、隐藏环境变量或只为测试存在的失败开关。
- Java 必须读取同一份 C++ builder 输出。`PARTIAL_SUCCESS` 可以进入可用报告流程；`FAILED` 只能作为受限诊断，不能由快照或任务状态逻辑伪装为成功。
- C++ 原有测试仍须单独证明真实进程失败返回非零退出码；联合任务不得仅凭一份合法 `FAILED` JSON 推断退出码已经正确。

如果任务 11 合并后的真实代码边界无法在不修改产品行为的前提下复用 builder，先回主线调整测试设计，不复制第二套报告生成代码。

### 4. 反例 fixture

反例从 C++ 为 `complete` 或 `missing-structure` 生成的有效 v2 报告复制到临时目录后再进行单一变异。每个用例只改变一个契约条件，并记录 Java 预期失败类别。至少包括：

| 编号 | 单一变异 | Java 预期 |
| --- | --- | --- |
| N01 | 删除 `protocolVersion` | `INVALID_PROTOCOL_VERSION` |
| N02 | 把版本改为 `2.1` | `UNSUPPORTED_PROTOCOL_VERSION` |
| N03 | 增加未知顶层字段 | `SCHEMA_VIOLATION` |
| N04 | 将路径改为 Windows 或 Unix 绝对路径 | `SCHEMA_VIOLATION` |
| N05 | 反转 `languages` 或 `findings` 顺序 | `SEMANTIC_VIOLATION` |
| N06 | 修改一条 finding 的 `findingId` | `SEMANTIC_VIOLATION` |
| N07 | 在消息中加入凭据样式文本 | `SEMANTIC_VIOLATION` |
| N08 | 制造错误的 status / reviewability / limitations 组合 | `SCHEMA_VIOLATION` |
| N09 | 将位置结束点放在开始点之前 | `SEMANTIC_VIOLATION` |
| N10 | 增加未批准的 evidence 字段 | `SCHEMA_VIOLATION` |

如果任务 10 已固定更精确的失败类别，联合测试必须断言该类别，不能只断言“抛出了某个异常”。变异工具只能位于测试代码或验证脚本中，不能进入产品运行路径。

## 规范化与 golden 更新规则

每份有效 v2 输出必须先通过 Schema 和 Java 语义校验，再生成规范化结果：

1. 只删除顶层 `taskId` 和 `generatedAt`。
2. 保留协议版本、Analyzer 版本、rule set 版本、仓库身份、统计、结构、发现、证据、限制和修复方向。
3. 按协议第 9 节确认数组已排序且稳定键唯一；规范化工具不能替生产端重新排序来掩盖错误。
4. 对象键按 Unicode 码点升序，以 UTF-8、无 BOM、无多余空白写出。
5. 计算 SHA-256，并与 `cases.json` 中的预期哈希比较。
6. 同一 fixture 连续生成两次，规范化 JSON 必须逐字节相同，哈希也必须相同。

首次创建 golden 时，两位负责人必须共同审查完整 JSON，确认它来自当前 C++ 生产构造逻辑，而不是从协议示例手工改写。以后只有有意的 Analyzer、rule set、协议或扫描事实变化才能更新 golden；更新时必须在提交说明中解释原因。

golden 与哈希是自动化测试预期，不得写入 `docs/validation/repository-runs.csv`、`finding-reviews.csv` 或 `ai-comparisons.csv`，也不得包装成正式实验结论。

## 自动化入口设计

任务 12 执行时增加一个仓库级验证脚本：

```text
scripts/verify-analyzer-report-v2-contract.ps1
```

脚本只负责流程编排，不重新实现协议规则。它必须：

1. 从脚本位置解析仓库根目录，不依赖调用者当前目录。
2. 使用系统临时目录保存构建产物、原始报告、反例和日志；结束时清理，失败时给出可定位但不泄露报告正文的摘要。
3. 检查所需工具并打印实际版本。
4. 配置和构建 C++，运行 CTest。
5. 对真实目录 fixture 分别运行默认、显式 v1 和显式 v2 CLI。
6. 通过 C++ 测试入口生成 `PARTIAL_SUCCESS` 与 `FAILED` 报告。
7. 使用任务 11 记录的明确名称和版本的 Draft 2020-12 验证器验证所有有效 v2 报告。
8. 把同一批报告路径交给 Java 联合契约测试，由 `AnalyzerReportReader` 和 v2 语义校验器读取。
9. 验证 Java v2 快照白名单，并确认 `FAILED` 不进入可用快照流程。
10. 生成单一变异反例并检查精确失败类别。
11. 比较两次运行的规范化 JSON 与 SHA-256。
12. 扫描报告、golden 和 Java 快照中是否出现 fixture 绝对路径、当前用户名、Token / 私钥 / Authorization Header、stdout、stderr 或堆栈文本；另行确认本地日志的诊断有长度上限且不包含凭据。

脚本遇到任一步失败必须返回非零退出码，不得继续输出“整体通过”。脚本不下载运行时依赖、不写入仓库 fixture、不修改 validation CSV，也不提交生成报告。

## 验收流程

### 阶段 A：锁定基线

1. 记录分支、HEAD、`main` 和 `origin/main` 状态。
2. 确认工作区干净，任务 10 与任务 11 都已包含在起始 commit 中。
3. 记录 CMake、编译器、CTest、Java、Maven、Schema 验证器和操作系统版本。
4. 确认正式 Schema 仍只有 `docs/protocol/analyzer-report-v2.schema.json` 这一份来源，Java classpath 资源由构建复制。

### 阶段 B：生产端回归与真实生成

1. 执行任务 11 的 CMake 配置、构建和完整 CTest。
2. 对 `complete` 运行默认命令和显式 `--protocol 1.0`，确认都是 v1。
3. 对 `complete` 和 `missing-structure` 显式运行 `--protocol 2.0`。
4. 通过测试边界生成 partial 和 failed 报告。
5. 检查所有进程退出码、报告文件存在性和安全诊断边界。

### 阶段 C：Schema 与 Java 消费

对每份 C++ 有效 v2 输出按同一顺序执行：

```text
C++ 生产构造逻辑
  -> 正式 Draft 2020-12 Schema
  -> Java AnalyzerReportReader
  -> Java 语义校验
  -> Java v2 快照白名单（仅可用状态）
```

任何一步失败都保留原始失败阶段。不能由后一步重新序列化报告后再让前一步通过，也不能由 Java 补写 Analyzer 版本、rule set 版本、findingId、排序或 remediation。

### 阶段 D：确定性、反例与安全

1. 对每个可重复场景连续运行两次并比较规范化字节与哈希。
2. 逐个运行 N01 至 N10，每次从新的有效报告开始，只做一个变异。
3. 检查跨平台路径分隔、稳定 ID 固定向量和当前三个规则的 metadata。
4. 对原始报告、规范化报告、golden 和 Java 快照执行协议敏感信息检查；对临时日志检查凭据与诊断长度边界。

### 阶段 E：完整回归与差异审查

1. 执行 C++ 完整 CTest。
2. 在 `openpulse-platform` 执行 `mvn clean verify`，如 Docker / Testcontainers 不可用必须如实记录，不能声称完整通过。
3. 运行仓库级联合验证脚本，报告用例总数、通过数和失败数。
4. 执行 `git diff --check`。
5. 确认差异只包含批准的 fixture、测试、验证脚本和必要文档；不得包含构建目录、生成报告、实验 CSV 数据或产品默认切换。
6. Java 与 C++ 负责人分别复核自己边界，再共同复核 golden 与未解决项。

## 最小验收矩阵

| ID | 输入/场景 | 生产入口 | 必须证明 |
| --- | --- | --- | --- |
| C01 | `complete` | CLI 默认参数 | 仍生成 v1，Java v1 路径继续接受 |
| C02 | `complete` | CLI `--protocol 1.0` | 显式 v1 与默认 v1 结构一致 |
| C03 | `complete` | CLI `--protocol 2.0` | C++ v2 -> Schema -> Java 全链通过，无当前结构发现 |
| C04 | `missing-structure` | CLI `--protocol 2.0` | 三条真实结构 finding、稳定 ID、证据和排序一致 |
| C05 | partial 场景 | C++ 生产 builder 测试边界 | `PARTIAL_SUCCESS + PARTIAL`，限制完整，Java 作为可用受限报告读取 |
| C06 | failed 场景 | C++ 生产 builder 测试边界 | `FAILED + NOT_USABLE`，Java 不把它保存为可用成功报告 |
| C07 | C03 至 C05 重复两次 | 相同入口 | 只移除两个动态字段后字节和 SHA-256 相同 |
| C08 | N01 至 N10 | 测试变异器 | Java 按固定安全类别拒绝每个反例 |
| C09 | 报告、快照与日志 | 安全扫描 | 报告和快照无绝对路径、用户名、凭据、进程输出、堆栈或未知字段；本地日志诊断有界且无凭据 |

C05、C06 的测试边界通过不等于真实操作系统故障已端到端覆盖。报告必须分别列出“真实 CLI 联合覆盖”和“生产 builder 测试边界覆盖”，不能合并成一句“全部端到端通过”。

## 允许的实现范围

任务 12 执行阶段允许修改：

- `docs/fixtures/analyzer-report-v2-contract/` 下的虚构 fixture、清单和 golden
- C++ 测试代码与最小测试适配器
- Java 联合契约测试与测试资源
- `scripts/verify-analyzer-report-v2-contract.ps1`
- 本任务书、README 和必要的测试说明

原则上不修改 Java/C++ 产品代码。如果联合测试暴露真实兼容缺陷，应先停止、记录最小复现和责任边界，再创建范围单一的修复任务；不能把产品修复、fixture 建设和默认协议切换混在同一个提交中。

## 明确不做

- 不改变 CLI 默认协议，默认仍为 v1。
- 不删除、弃用或收紧 v1 兼容行为。
- 不修改已接受的 v2 协议或 Schema 来迁就实现。
- 不新增规则、Tree-sitter、Clang AST、依赖分析或健康分。
- 不把协议示例中的 `LONG_FUNCTION` 当作 C++ 已实现能力。
- 不改变 HTTP API、Java 默认 `AnalyzerProcessRunner` 命令或生产配置。
- 不在产品代码加入测试模式、故障注入开关或 fixture 解析器。
- 不使用真实第三方源码、用户仓库、个人路径或凭据。
- 不运行正式 v0.2 样本，不填写任何 validation CSV，不生成实验指标。
- 不决定 v2 默认切换，也不决定 v1 弃用或移除。
- 不 push、不创建 PR，直到本地验收报告回到主线对话并获得下一步授权。

## 完成标准

以下条件全部满足后，任务 12 才能标记完成：

1. C01 至 C09 全部有可复现的自动化断言和真实结果。
2. 默认和显式 v1 回归通过，v2 只在显式选择时生成。
3. C++ 真实 CLI 生成的 SUCCESS 报告通过正式 Schema 和 Java Reader。
4. C++ 生产 builder 生成的 PARTIAL_SUCCESS / FAILED 报告由 Java 按相同语义处理。
5. 当前三个 C++ 结构规则的 findingId、证据、修复方向、排序和 rule set metadata 一致。
6. 两次运行只移除 `taskId` 与 `generatedAt` 后逐字节一致，规范化哈希与评审后的 golden 一致。
7. N01 至 N10 全部以预期安全类别失败，没有宽松回退。
8. Java v2 快照保持白名单，FAILED 不进入可用成功记录。
9. 敏感信息检查通过，fixture 和输出不含真实仓库内容或个人信息。
10. C++ 完整测试通过；Java `mvn clean verify` 的普通测试和 MySQL/Testcontainers 结果按实际环境分别报告。
11. `git diff --check` 通过，工作区不含构建产物、临时报告或实验结果。
12. Java 与 C++ 负责人分别确认边界并共同确认 golden；没有未解决分歧。
13. 创建一个范围单一的本地 test/docs commit，不 push、不创建 PR，回主线验收。

即使任务 12 完成，也只满足“默认切换条件”中的联合契约部分。默认协议切换仍必须由独立任务和评审批准。

## 回主线对话时提交的信息

```text
分支：
起始 main commit：
任务 10 合并 commit：
任务 11 合并 commit：
本地 commit：

fixture 清单版本：
Analyzer 版本：
Rule set ID / version：
Schema 验证器和版本：
Java Schema 库和版本：

C01 默认 v1：
C02 显式 v1：
C03 v2 complete：
C04 v2 missing-structure：
C05 v2 partial：
C06 v2 failed：
C07 重复运行确定性：
C08 反例数量和结果：
C09 敏感信息检查：

规范化 golden：
规范化 SHA-256：
固定 findingId 向量：
Java v2 快照白名单：
FAILED 状态边界：

CMake 配置和构建：
CTest 数量和结果：
Java 普通测试数量和结果：
MySQL/Testcontainers 数量和结果：
联合脚本命令和结果：
git diff --check：
工作区状态：

真实 CLI 联合覆盖：
生产 builder 测试边界覆盖：
Java 负责人复核：
C++ 负责人复核：
未完成或阻塞项：

确认没有执行：
- 默认协议切换或 v1 移除
- 新规则、AI、Skill、MCP、前端或安装器实现
- 正式仓库实验或 validation CSV 填写
- push 或 PR 创建
```

## 新对话启动提示词

```text
请先检查最新 main，并阅读：

- docs/tasks/12-v2-contract-integration.md
- docs/tasks/10-java-analyzer-report-v2-reader.md
- docs/tasks/11-cpp-analyzer-report-v2-output.md
- docs/protocol/analyzer-report-v2.md
- docs/protocol/analyzer-report-v2.schema.json
- docs/protocol/analyzer-report-v2-migration.md

只有在任务 11 已通过主线验收并合并后，才开始“OpenPulse AI 第十二步：
报告协议 v2 联合契约验收”。从包含任务 10 和任务 11 的最新 main 创建
test/analyzer-report-v2-contract 分支。

建立虚构的 complete 与 missing-structure 仓库 fixture，并用测试边界场景
覆盖 PARTIAL_SUCCESS 与 FAILED。所有报告必须由 C++ 真实 CLI 或生产 v2
builder 生成到临时目录，再依次通过正式 Draft 2020-12 Schema、Java
AnalyzerReportReader、语义校验和适用的 v2 快照白名单。连续运行两次，
只能移除 taskId 与 generatedAt，比较规范化 JSON 和 SHA-256；同时执行任务书
规定的反例、安全检查以及 Java/C++ 完整回归。

不要修改产品代码或协议来迁就 fixture，不要增加产品测试模式，不要切换默认
协议，不要删除 v1，不要实现新规则，也不要填写正式实验结果。完成后创建一个
范围单一的本地 test/docs commit，不 push、不创建 PR，并按任务书格式回报。
```
