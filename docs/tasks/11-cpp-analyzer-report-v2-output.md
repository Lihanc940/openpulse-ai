# OpenPulse AI 第十一步：C++ 显式输出报告协议 v2

## 任务定位

- 建议分支：`feat/analyzer-report-v2-output`
- 负责人：C++ Analyzer 负责人
- 前置任务：报告协议 v2 已标记为 `ACCEPTED` 并合并到 `main`
- 并行任务：`10-java-analyzer-report-v2-reader.md`
- 任务性质：实现已接受协议，不重新设计协议

本任务让 `openpulse-analyzer` 在调用方明确选择时输出协议 `2.0`，同时继续把协议 `1.0` 作为默认输出。它只实现当前已经存在的仓库统计和三条项目结构规则，不借机加入 Tree-sitter、文本规则或新的产品能力。

Java 与 C++ 可以并行开发，因为双方都依赖同一份已经接受的 Schema。任何实现困难都应先作为协议问题回到主线讨论，不能在 C++ 分支中悄悄改变字段含义。

## 为什么单独做这一步

当前 C++ Analyzer 已经能遍历目录、统计文件和行数、识别语言，并生成 `MISSING_README`、`MISSING_LICENSE`、`MISSING_CI` 三类结构发现。但是协议 v1 仍包含本机绝对路径、占位健康分和自由证据对象，不能直接用于 v0.2 正式验证。

本任务解决的是“C++ 能否稳定生产可信 v2 报告”，不是“增加更多扫描规则”。先把版本、路径、证据、限制、排序和稳定 ID 做正确，后续规则才能安全增长。

## 开始前阅读

按顺序阅读：

1. `docs/protocol/analyzer-report-v2.md`
2. `docs/protocol/analyzer-report-v2.schema.json`
3. `docs/protocol/analyzer-report-v2-migration.md`
4. `docs/examples/analyzer-report-v2.success.sample.json`
5. `docs/examples/analyzer-report-v2.partial-success.sample.json`
6. `docs/02-analyzer-json-protocol.md`
7. `openpulse-analyzer/CMakeLists.txt`
8. `openpulse-analyzer/include/Analyzer.h`
9. `openpulse-analyzer/src/Analyzer.cpp`
10. `openpulse-analyzer/src/main.cpp`
11. `openpulse-analyzer/tests/test_analyzer.cpp`

Schema 和协议 Markdown 共同构成契约。示例用于理解结构，不代表示例中的所有规则已经实现。

## 本步骤需要理解的名词

- **显式选择**：只有调用方写出版本参数时才输出 v2；没有参数时仍输出 v1。
- **报告生产端**：生成 JSON 报告的 C++ Analyzer。
- **规则目录**：集中保存规则编号、严重程度、修复方向和允许证据类型的确定性定义。
- **构建版本注入**：由 CMake 把真实项目版本写进编译结果，代码不重复手写版本字符串。
- **规范 JSON**：对象键、数组顺序、数字和空白都有固定规则的 JSON 表示。
- **稳定 ID**：同一规则、位置和证据不变时，多次运行得到相同标识。
- **SHA-256**：用于计算稳定 ID 的标准哈希算法，不是加密业务数据。
- **限制项**：说明哪些文件或能力没有完成分析，避免把“不支持”误写成“没有问题”。
- **确定性**：同一输入、版本和配置重复运行时，除约定动态字段外结果一致。
- **回归测试**：确认增加 v2 后，原来的 v1 行为没有被破坏。

## 本任务交付物

允许修改：

- `openpulse-analyzer/CMakeLists.txt`
- `openpulse-analyzer/include/`
- `openpulse-analyzer/src/`
- `openpulse-analyzer/tests/`
- 为版本注入、规则目录或 SHA-256 依赖新增的最小 C++/CMake 文件

如果引入第三方 SHA-256 实现，必须：

- 使用成熟、可审计、许可证兼容的实现；不要自己发明密码算法。
- 锁定明确版本或提交。
- 保留许可证要求和依赖来源。
- 同时支持当前 Windows/MSVC 开发环境和后续 Linux 构建。
- 不把下载后的构建目录、可执行文件或个人绝对路径提交进 Git。

本任务不得修改 Java、Vue、数据库迁移、验证实验结果或协议字段定义。

## CLI 行为

新增明确的版本参数：

```text
--protocol 1.0
--protocol 2.0
```

行为必须满足：

- 不传 `--protocol`：继续输出 v1，保持现有调用兼容。
- 传 `--protocol 1.0`：输出 v1。
- 传 `--protocol 2.0`：输出 v2。
- 缺少参数值或使用未知版本：输出安全错误并返回退出码 `1`。
- `--help`：列出参数、默认值和支持版本，并正常返回 `0`。
- 不通过 Shell 拼接命令，不解释用户输入为额外命令。

现有 `--path`、`--output` 和退出码 `0` 至 `4` 的含义继续保留。若实现发现现有退出码无法表达真实失败，先回主线提出问题，不在本分支自行增加未记录语义。

## 建议代码边界

具体类名可以按现有代码风格调整，但职责必须分开：

- CLI 参数解析：只解析 `--path`、`--output`、`--protocol` 和帮助参数。
- 扫描事实收集：继续负责文件、行数、语言、结构和扫描限制事实。
- v1 报告构造：保留当前输出，不因 v2 重写而改变字段。
- v2 报告构造：只根据扫描事实和规则目录产生协议 v2 JSON。
- 规则目录：集中定义当前三条结构规则的元数据。
- 稳定 ID/规范 JSON：集中实现并可独立测试。

不要让 `main.cpp` 直接堆积全部 v2 字段，也不要复制两套目录遍历逻辑。v1 和 v2 应共享事实收集结果，但使用不同报告构造边界。

## 版本和规则集

### Analyzer 版本

- 版本来源必须是 `project(openpulse-analyzer VERSION ...)`。
- 使用 CMake 生成头文件或等价方式注入编译产物。
- v2 的 `analyzer.name` 固定为 `openpulse-analyzer`。
- v2 的 `analyzer.version` 必须是实际构建版本，不得写 `unknown` 或复制示例版本。
- CLI 启动文字和报告版本不得继续分别手写成不同值。

### Rule set 版本

- 建立一个明确的规则集 ID，例如协议评审通过的 `openpulse-default`。
- 规则集版本使用语义版本，初始值必须与本次真实规则目录一致。
- 当前只登记 `MISSING_README`、`MISSING_LICENSE`、`MISSING_CI`。
- 每条规则固定声明 `ruleId`、类型、严重程度、允许的证据种类、消息和修复方向。
- 不登记尚未实现的 `LONG_FUNCTION`、文本规则或依赖分析。

## v2 报告内容

严格按照已接受 Schema 输出：

- `protocolVersion`
- `taskId`
- `analyzer`
- `ruleSet`
- `status`
- `reviewability`
- `repository`
- 成功或部分成功时的 `summary`、`languages`、`structure`
- `findings`
- `limitations`
- `generatedAt`

### 仓库身份与路径

- `repository.root` 固定为 `.`。
- `repository.name` 只保存仓库目录名称，不保存父目录。
- 能可靠取得 revision 时才写 `repository.revision`；本任务不要求为了得到 revision 启动 Git 命令。
- 报告中的文件路径统一使用 `/` 分隔的安全相对路径。
- 拒绝绝对路径、盘符、URI、反斜杠、空路径和 `.`/`..` 路径段。
- 输出不得包含本机用户名、工作目录或临时目录。

### 当前结构发现

三条现有规则映射为：

- `type: PROJECT_STRUCTURE`
- `scope: REPOSITORY`
- `location: null`
- `evidence.kind: EXPECTED_PATHS_ABSENT`
- 规则目录提供固定、非空的 `remediation`

`expectedPaths` 必须是实际检查过的候选路径，去重并按协议稳定排序。不能为了匹配示例而声明扫描器从未检查的路径。

### 状态与限制

- `SUCCESS`：计划扫描范围完整完成，`reviewability` 为 `COMPLETE`，限制数组为空。
- `PARTIAL_SUCCESS`：报告仍可使用，但至少有一个真实限制，`reviewability` 为 `PARTIAL`。
- `FAILED`：报告不可使用，`reviewability` 为 `NOT_USABLE`，发现为空，并至少包含一个 `SCAN_FAILED`。

配置中明确排除的 `.git`、`node_modules`、`target` 等目录属于扫描范围定义，不应自动算作限制。读取失败、编码不支持、真实资源上限或能力不支持，只有确实发生时才生成对应限制；不得为了测试伪造生产结果。

进程发生失败时，现有非零退出码仍然是真实失败信号。若可以安全写出最小 `FAILED` 报告，可写出受限诊断，但不能返回退出码 `0` 或把它当作成功报告。

## 稳定 ID、排序与规范化

- 严格实现协议第 8 节 `findingId` 算法。
- 使用真实 `U+001F` 分隔符、UTF-8 和 SHA-256。
- 固定向量必须得到协议文档记录的哈希。
- `languages`、`buildFiles`、`findings`、`limitations` 和 `expectedPaths` 按协议键排序。
- 稳定键重复时拒绝报告构造，不靠 JSON `uniqueItems` 事后碰运气。
- v2 报告输出不能使用缩进、运行地址或非确定性容器顺序制造差异。
- `taskId` 和 `generatedAt` 仍是动态字段；测试比较时只能按协议移除这两个字段。

如果 nlohmann/json 的默认行为不能完全满足协议，必须显式补齐并写测试，不能只凭印象认定一致。

## 安全要求

报告中不得出现：

- 绝对路径和用户名
- 源码正文或任意大段文本
- stdout、stderr 或异常堆栈
- 环境变量、Token、私钥、Authorization Header
- 未受长度限制的错误消息

底层错误可以写入本地 stderr 供开发者诊断，但协议报告中的消息必须是固定、安全、可预测的文案。

## 自动化测试要求

至少覆盖：

1. 不传协议参数仍生成合法 v1，现有 v1 测试继续通过。
2. 显式 `1.0` 与默认 v1 的结构一致。
3. 显式 `2.0` 生成完整 SUCCESS 报告。
4. 未知版本、缺少版本值和重复参数被拒绝。
5. `--help` 返回 `0`。
6. Analyzer 版本来自 CMake 项目版本。
7. 规则集 ID、版本和三条规则元数据固定。
8. 仓库级发现使用 `location: null` 和受约束证据。
9. 输出不包含输入目录的绝对路径。
10. 固定 `findingId` 向量复现一致。
11. 同一 fixture 连续运行，移除 `taskId` 与 `generatedAt` 后完全一致。
12. 语言、构建文件、发现、限制和 expectedPaths 排序正确。
13. 文件读取失败等受控场景生成 PARTIAL_SUCCESS 或真实失败，不伪装 SUCCESS。
14. FAILED 报告字段满足状态约束，进程仍返回对应非零退出码。
15. v2 输出通过仓库内正式 Draft 2020-12 Schema。
16. Windows/MSVC 和非 MSVC 条件代码不会产生平台专属报告内容。

跨平台难以稳定制造的权限错误，可以在事实模型或报告构造边界使用测试输入覆盖；不要在生产代码加入“测试模式”。

## 验证命令

在 `openpulse-analyzer` 目录使用独立构建目录，记录实际生成器：

```powershell
cmake -S . -B build -DBUILD_TESTS=ON
cmake --build build --config Release
ctest --test-dir build -C Release --output-on-failure
```

还必须真实执行：

```powershell
openpulse-analyzer --help
openpulse-analyzer --path <fixture> --output <v1.json>
openpulse-analyzer --protocol 1.0 --path <fixture> --output <explicit-v1.json>
openpulse-analyzer --protocol 2.0 --path <fixture> --output <v2.json>
```

使用明确名称和版本的 Draft 2020-12 验证器检查真实生成的 v2 JSON。验证报告必须写出工具版本和结果，不能只检查仓库中的手写示例。

构建目录和报告输出放在 Git 忽略目录或系统临时目录，验证后清理。没有工具或网络依赖无法下载时，如实报告阻塞，不要伪造成功结果。

## 明确不做

- 不改变默认输出版本。
- 不删除协议 v1。
- 不修改 Java Reader、数据库或 HTTP API。
- 不加入 Tree-sitter、Clang AST 或其他语法树引擎。
- 不实现 LONG_FUNCTION、危险函数、TODO/FIXME 或依赖分析。
- 不实现健康评分、AI 总结、Skill、MCP、安装器或自动更新。
- 不打包或提交 exe、dll、构建缓存和第三方下载目录。
- 不修改已接受协议来迁就实现。

## 完成标准

1. 默认 v1 行为和现有测试保持通过。
2. `--protocol 2.0` 能从真实 fixture 生成严格合法 v2 报告。
3. Analyzer 和规则集版本来自明确来源，不使用占位值。
4. 当前三条结构发现具有稳定 ID、严格证据和修复方向。
5. 路径、状态、限制、排序、时间和敏感信息边界全部有测试。
6. 两次运行的规范化结果一致。
7. 真实生成报告通过正式 Schema。
8. 没有新增未实现能力或改变默认协议。
9. `git diff --check` 通过，工作区只包含本任务允许的 C++/测试/CMake 文件。
10. 创建一个范围单一的本地 commit，不 push、不创建 PR，先回主线验收。

## 回主线对话时提交的信息

```text
分支：
commit：

CLI 协议参数：
默认协议版本：
Analyzer 版本来源：
Rule set ID / version：
SHA-256 实现及许可证：

v1 回归结果：
v2 SUCCESS 验证：
v2 PARTIAL_SUCCESS 验证：
v2 FAILED 验证：
Schema 验证工具和版本：
固定 findingId 向量：
重复运行确定性：
敏感信息检查：

CMake 配置命令和结果：
构建命令和结果：
CTest 数量和结果：
真实 CLI 命令和输出摘要：

git diff --check：
工作区状态：
未完成或阻塞项：
我理解的新名词：
遇到的问题：
```

## 新对话启动提示词

```text
我是 OpenPulse AI 的 C++ Analyzer 负责人。请先阅读：

- docs/tasks/11-cpp-analyzer-report-v2-output.md
- docs/protocol/analyzer-report-v2.md
- docs/protocol/analyzer-report-v2.schema.json
- docs/protocol/analyzer-report-v2-migration.md
- docs/examples/analyzer-report-v2.success.sample.json
- docs/examples/analyzer-report-v2.partial-success.sample.json

然后严格完成“OpenPulse AI 第十一步：C++ 显式输出报告协议 v2”。

从最新 main 创建 feat/analyzer-report-v2-output 分支。保留协议 v1 为默认，
只在 --protocol 2.0 时输出 v2。实现真实版本、规则目录、安全相对路径、
当前三条结构发现的严格证据与修复方向、状态和限制、稳定 findingId、
排序及确定性测试。

不要修改 Java，不要改变默认协议，不要加入新规则、Tree-sitter、AI、
安装器或实验数据。每使用一个新名词先用简单语言解释，再执行对应操作
并验证。完成后创建一个范围单一的本地 commit，但不要 push 或创建 PR，
按任务书格式返回主线验收报告。
```
