# OpenPulse AI 第十三步：Java 运行链路接入报告协议 v2

## 任务定位

- 建议分支：`feat/platform-analyzer-runtime-v2`
- 负责人：Java / Platform 负责人
- 前置任务：任务 12 已由 PR #28 合并到 `main`（`5c63638`）
- 可并行任务：`14-windows-cli-distribution.md`
- 任务性质：把已经验收的 v2 Reader 接入真实 Java 子进程链路，不切换 C++ CLI 默认版本

本任务让 Java 在启动 `openpulse-analyzer` 时明确传递所需协议版本，并让 `AnalyzerProcessRunner` 使用统一版本分派 Reader 读取实际报告。默认配置仍保持 `1.0`，因此现有本地 API 行为不变；把配置改成 `2.0` 时，Java 必须能接收、校验和返回真实 v2 报告。

任务完成后，Java 不再依赖“C++ 没传参数时刚好默认 v1”这一隐含约定。以后 C++ 是否切换默认输出，可以在独立任务中决定，不会突然破坏 Java。

## 为什么单独做这一步

任务 10 已让 `AnalyzerReportReader` 能严格读取 v1/v2，任务 12 也证明真实 C++ v2 输出能通过 Java 联合校验。但是正式运行链路仍有两个 v1 假设：

- `ConfiguredAnalyzerCommandFactory` 没有传 `--protocol`。
- `AnalyzerProcessRunner` 返回 `AnalyzerReport` 并调用 `readV1()`。

如果现在直接把 C++ CLI 默认值切成 v2，Java 会收到 v2 文件却按 v1 读取，导致现有本地分析失败。本任务先消除这个隐含依赖，但不顺手改变公开默认行为。

## 开始前阅读

按顺序阅读：

1. `docs/protocol/analyzer-report-v2.md`
2. `docs/protocol/analyzer-report-v2-migration.md`
3. `docs/tasks/10-java-analyzer-report-v2-reader.md`
4. `docs/tasks/12-v2-contract-integration.md`
5. `AnalyzerProcessProperties`
6. `AnalyzerCommandFactory` 与 `ConfiguredAnalyzerCommandFactory`
7. `AnalyzerProcessRunner`
8. `AnalyzerReportReader` 与 `AnalyzerReportDocument`
9. `LocalAnalysisService` 与 `LocalAnalysisController`
10. 对应 Runner、CommandFactory、API、SnapshotFactory 和 MySQL 测试

## 本步骤需要理解的名词

- **运行链路**：Java 从接收路径、组装命令、启动 C++、等待退出、读取报告到返回结果的完整流程。
- **显式协议选择**：Java 命令始终携带 `--protocol 1.0` 或 `--protocol 2.0`，不猜测 C++ 默认值。
- **隐含默认值**：调用方不传参数，把正确行为寄托在另一模块当前默认设置上；模块升级后容易悄悄失效。
- **版本化返回类型**：方法返回 `AnalyzerReportDocument`，实际对象可以是 v1 的 `AnalyzerReport` 或 v2 的 `AnalyzerReportV2`。
- **配置绑定**：Spring 把配置文件或环境变量转换成受校验的 Java 配置对象。
- **兼容默认值**：新增能力后，未修改配置的现有用户仍得到原来的行为。
- **协议错配**：请求的是一种协议，但分析器输出另一种协议；必须明确失败，不能静默接受。
- **回退路径**：v2 出现兼容问题时，通过配置明确恢复 v1，而不是降级代码或改数据库。

## 本任务交付物

允许修改：

- `openpulse-platform/src/main/java/.../integration/analyzer/`
- `AnalyzerReportDocument` 必要的最小公共边界
- `LocalAnalysisService` 与默认关闭的本地诊断 API 的返回类型
- `application.yml` 中 Analyzer 协议配置
- 对应 Java 单元测试、Spring 配置测试和集成测试
- README 或开发环境文档中与新增配置直接相关的最小说明

不得修改：

- C++ Analyzer、协议 Markdown、Schema 和示例
- 数据库表结构
- GitHub 下载流程
- Vue、AI、Skill、MCP 和正式实验 CSV

## 配置设计

新增一个明确配置项，建议为：

```yaml
openpulse:
  analyzer:
    protocol-version: ${OPENPULSE_ANALYZER_PROTOCOL_VERSION:1.0}
```

要求：

- 只接受精确值 `1.0` 和 `2.0`。
- 未配置时默认 `1.0`，保持现有行为。
- 空值、未知值、前后多余空白和近似版本在应用启动或配置构造时失败。
- 不使用布尔值 `use-v2`，避免未来增加版本时继续堆开关。
- 配置值进入参数列表中的独立元素，不经过 Shell 拼接。
- 环境变量名称和默认值写入开发文档，但不记录个人环境路径。

可以建立一个小型 `AnalyzerProtocolVersion` 值类型或枚举集中保存 CLI 值。不要在 CommandFactory、Runner 和测试中重复散落字符串。

## 建议代码边界

### CommandFactory

生成的参数列表应明确包含：

```text
<executable> --protocol <1.0|2.0> --path <repository> --output <report>
```

路径包含空格时仍必须保持为单个参数。不要调用 `cmd /c`、PowerShell 或字符串拼接。

### ProcessRunner

- 返回公共版本化类型 `AnalyzerReportDocument`，或提供语义等价且不会重复启动进程的小型版本化入口。
- 成功退出后调用 `AnalyzerReportReader.read(...)` 进行版本分派，不再无条件调用 `readV1()`。
- 校验报告中的 `protocolVersion` 与本次请求版本一致；错配统一映射为 `REPORT_INVALID`。
- v2 `FAILED` 报告不能因为 JSON 合法就被当作成功分析结果；继续遵守任务状态和快照规则。
- 原有超时、进程树终止、stdout/stderr 有界诊断、临时目录清理和退出码映射保持不变。

### Service 与本地 API

- `LocalAnalysisService` 可以返回版本化报告接口，不把 v2 压回 v1 模型。
- 本地 API 仍默认关闭，不新增公网入口。
- 默认配置 `1.0` 下，现有 HTTP 状态码和响应 JSON 必须保持兼容。
- 显式配置 `2.0` 时，成功响应可以序列化具体 `AnalyzerReportV2`，但必须增加真实 Spring Web 上下文测试，确认不会只输出接口公共字段。
- 错误响应仍使用固定安全消息，不返回绝对路径、报告正文、stdout、stderr 或堆栈。

## v1 与 v2 行为要求

### 配置为 `1.0`

- Java 显式传递 `--protocol 1.0`。
- Runner 返回 v1 `AnalyzerReport`。
- 现有本地 API、状态映射和 v1 快照回归通过。

### 配置为 `2.0`

- Java 显式传递 `--protocol 2.0`。
- Runner 返回 v2 `AnalyzerReportV2`。
- 报告依次通过文件边界、Schema、语义和规则目录校验。
- SUCCESS 与 PARTIAL_SUCCESS 可以进入相应可用流程。
- FAILED、版本错配和非法报告不能伪装成功。
- v2 快照只保存任务 10 已批准的白名单字段。

## 自动化测试要求

至少覆盖：

1. 默认配置值为 `1.0`。
2. `1.0` 与 `2.0` 配置均能正确绑定。
3. 空白、`1`、`2`、`2.1`、`latest` 和未知值被拒绝。
4. CommandFactory 对 v1/v2 都生成独立参数列表，含空格路径不拆分。
5. v1 替身报告经真实 Runner 返回 v1 类型。
6. v2 替身或受控真实报告经真实 Runner 返回 v2 类型。
7. 请求 v1 却返回 v2、请求 v2 却返回 v1，均映射为 `REPORT_INVALID`。
8. v2 Schema、语义或规则目录不合法时映射为 `REPORT_INVALID`，不泄露底层正文。
9. v2 FAILED 不进入成功快照或成功 API 语义。
10. 本地 API 默认关闭回归。
11. 本地 API 默认 v1 响应保持兼容；显式 v2 时序列化完整具体模型。
12. 原有退出码、启动失败、超时、中断、后代进程、大输出和清理测试全部继续通过。
13. v1/v2 快照和 MySQL/Testcontainers 集成测试真实通过。
14. 任务 12 联合契约脚本继续通过，证明没有破坏 C++ 边界。

测试不能只 mock `AnalyzerReportReader` 然后断言被调用；至少有 v1、v2 和错配场景经过真实 JSON Reader。

## 验证命令

在 `openpulse-platform` 目录执行：

```powershell
.\mvnw.cmd clean verify
```

在仓库根目录执行：

```powershell
.\scripts\verify-analyzer-report-v2-contract.ps1
```

必须记录 Java、Maven、Docker/Testcontainers、CMake/CTest 版本，以及普通测试、MySQL 集成测试和联合用例结果。Docker 或 C++ 工具链不可用时如实报告，不能把跳过写成通过。

## 明确不做

- 不修改 C++ 默认协议。
- 不删除或弃用 v1。
- 不发布安装包。
- 不新增 GitHub 分析 HTTP 接口、前端页面或异步队列。
- 不增加分析规则、健康评分、AI 总结、Skill 或 MCP。
- 不改变协议字段、Schema、规则目录或 findingId 算法。
- 不填写正式实验结果。
- 不重构无关 Java 模块。

## 完成标准

1. Java 总是显式请求经过校验的协议版本。
2. Runner 能通过真实 Reader 返回 v1 或 v2 明确模型。
3. 请求版本与报告版本错配时安全失败。
4. 默认 `1.0` 下现有 API 行为保持兼容。
5. 显式 `2.0` 下完整模型、校验、状态和快照行为正确。
6. 原有进程安全和错误映射没有回归。
7. Maven 完整验证、MySQL/Testcontainers 和任务 12 联合脚本通过。
8. `git diff --check` 通过，改动只在允许范围。
9. 创建一个范围单一的本地 commit，不 push、不创建 PR，先回主线验收。

## 回主线验收信息

```text
分支：
commit：

新增配置项：
环境变量：
默认值：
允许值：

CommandFactory 最终参数顺序：
Runner 返回边界：
版本错配处理：
v1 默认兼容结果：
v2 真实读取结果：
本地 API v1/v2 序列化结果：

普通测试数量和结果：
MySQL/Testcontainers 结果：
任务 12 联合脚本结果：
Java/Maven/Docker/CMake/CTest 版本：

工作区状态：
未完成或阻塞项：
我理解的新名词：
遇到的问题：
```

## 新对话启动提示词

```text
请执行 OpenPulse AI 第十三步：Java 运行链路接入报告协议 v2。

开始前先阅读 README.md、CONTRIBUTING.md、docs/tasks/13-java-analyzer-runtime-v2.md，
以及任务书列出的协议、Runner、Reader、API 和测试文件。先用自己的话解释本任务调用链、
显式协议选择、版本错配、兼容默认值和不做范围，再从最新 main 创建任务书建议分支。

严格按任务书实现和验证。保留默认协议 1.0，不修改 C++、Schema、协议和正式实验数据。
完成后创建一个范围单一的本地 commit，不要 push，不要创建 PR，并按“回主线验收信息”汇报。
```
