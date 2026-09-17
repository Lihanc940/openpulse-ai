# OpenPulse Analyzer 报告协议 v1 到 v2 迁移方案

## 1. 状态与目标

- 迁移方案状态：`ACCEPTED`
- 来源协议：[v1 当前协议](../02-analyzer-json-protocol.md)
- 目标协议：[v2 报告协议](analyzer-report-v2.md)
- v2 Schema：[analyzer-report-v2.schema.json](analyzer-report-v2.schema.json)

本方案只规定迁移顺序和兼容边界，不修改运行时代码，不删除 v1，也不改变 CLI 默认输出。v2 是破坏性协议升级，消费端必须按 `protocolVersion` 明确分派，不能把未知版本静默解释为 v1 或 v2。

## 2. 为什么不能直接改字段名

v1 缺少分析器版本、规则集版本、稳定发现 ID、完整位置和修复方向，并允许自由证据对象。消费端如果只是把 `risks` 改名为 `findings`，会制造一份外形像 v2、实际缺少安全和可复核信息的报告。因此：

- 原始 v1 报告继续按 v1 读取和保存。
- 正式 v2 必须由知道真实分析器、规则集和规则元数据的生产端生成。
- Java 可以为界面做明确标记的 v1 展示适配，但不得把它序列化为通过 v2 Schema 的报告，也不得用于 v0.2 正式验证运行。

## 3. 字段映射

| v1 | v2 | 迁移结论 |
| --- | --- | --- |
| `protocolVersion: "1.0"` | `protocolVersion: "2.0"` | 只在真正生成完整 v2 时改变；数字或其他拼写无效 |
| 无 | `analyzer.name/version` | 新增必填；必须来自实际构建版本，不能由 Java 猜测 |
| 无 | `ruleSet.id/version` | 新增必填；必须来自实际规则目录，不能填 `unknown` |
| `taskId` | `taskId` | 保留一次运行关联语义；仍属于可移除动态字段 |
| `status` | `status` + `reviewability` | 枚举名保留；v2 增加与状态绑定的可复核性和字段条件 |
| `repository.name` | `repository.name` | 保留并执行长度与安全文本检查 |
| `repository.path` | `repository.root: "."` | 不复制 v1 的本机路径；v2 固定从扫描根表示相对路径 |
| 无 | `repository.revision` | 可选；仅可靠获得时写入，不从目录名猜测 |
| `summary` | `summary` | 九个计数沿用，但 v2 限定安全整数和状态条件 |
| `languages` | `languages` | 字段沿用；改为按 `name` 稳定排序且名称唯一 |
| `structure` | `structure` | 七个布尔字段和 `buildFiles` 沿用；构建路径改为严格相对路径并排序 |
| `risks` | `findings` | 破坏性改名；每项必须补齐 v2 新不变量，不能机械复制 |
| `risks[].ruleId` | `findings[].ruleId` | 名称保留；必须属于声明的规则集版本 |
| `risks[].type` | `findings[].type` | v2 只允许 `PROJECT_STRUCTURE`、`TEXT`、`SYNTAX_TREE`；v1 文档示例中的 `CODE_SMELL` 不能直接进入 v2 |
| `risks[].level` | `findings[].severity` | 值 `LOW`、`MEDIUM`、`HIGH`、`CRITICAL` 一一对应，字段改名 |
| `risks[].file`、`line` | `scope`、`location` | 空文件和第 0 行只有在已知仓库级规则中才能解释为 `REPOSITORY + null`；安全相对文件且行至少为 1 时可作为位置输入，但完整终点仍须生产端提供 |
| 无 | `findingId` | 按 v2 固定算法生成；缺少完整位置或受约束证据时不能生成正式 ID |
| `risks[].message` | `findings[].message` | 可保留语义，但须满足长度、安全和确定性规则 |
| `risks[].evidence` | `findings[].evidence` | 只能按规则映射到获批准的严格证据种类；未知键拒绝，不能原样复制自由对象 |
| 无 | `findings[].remediation` | 新增非空字段；来自规则定义，Java 和 AI 不得临时编写 |
| 无 | `limitations` | 新增必填数组；表达跳过、能力边界、资源上限或失败 |
| `generatedAt` | `generatedAt` | 保留带时区时间；仍属于可移除动态字段 |
| `quality` | 无 | 删除占位健康分，不迁移、不改名包装 |
| 空 `dependencies` | 无 | 删除尚未实现的暗示性字段；未来真实能力需要独立协议设计 |

## 4. 现有三类结构发现的迁移边界

当前 C++ v1 实际生成 `MISSING_README`、`MISSING_LICENSE`、`MISSING_CI`。未来 C++ v2 实现可以从同一结构检查事实生成：

- `type: PROJECT_STRUCTURE`
- `scope: REPOSITORY`
- `location: null`
- `evidence.kind: EXPECTED_PATHS_ABSENT`
- 来自规则目录的非空 `remediation`
- 按 v2 算法生成的 `findingId`

这不是让 Java 转换旧文件。特别是 v1 `MISSING_CI` 当前可能输出空证据，Java 无法仅靠旧 JSON 恢复获批准的候选路径，也不能补写规则集版本和修复方向。

v1 示例中的 `LONG_FUNCTION` 与 `CODE_SMELL` 不是当前 C++ Analyzer 的真实生成能力。v2 不能据此声称语法树规则已实现；只有后续规则任务提供真实位置、`SYNTAX_TREE` 类型、受约束证据和测试后才能输出。

## 5. Java 双版本读取顺序

后续 Java 实现任务按以下顺序处理一个报告文件：

1. 先限制文件大小并使用 JSON 解析器读取最小根对象，只取得 `protocolVersion`；缺失、非字符串或重复版本字段直接拒绝。
2. 精确值为 `1.0` 时，交给现有 v1 模型与 v1 校验流程。v1 继续保留当前兼容策略，不能因 v2 严格模式意外破坏旧调用方。
3. 精确值为 `2.0` 时，交给独立 v2 模型；先运行 Draft 2020-12 Schema，再运行路径、位置先后、排序、稳定 ID、规则目录和敏感文本语义检查。
4. 其他值直接报告“不支持的协议版本”，不能回退到最近版本。
5. v1 与 v2 使用独立持久化白名单。保存时继续去除任何本机路径和未批准字段。
6. `SUCCESS` 与 `PARTIAL_SUCCESS` 可以进入可用报告流程；`FAILED` 只作为受限诊断。进程非零退出、超时、报告缺失或报告非法继续由 `AnalyzerProcessRunner` 的真实失败类别决定。

建议的 Java 类型边界是两个独立根类型，例如 `AnalyzerReportV1` 与 `AnalyzerReportV2`，再由一个只负责分派的 Reader 返回显式版本化结果。v2 证据使用四个明确类型，不使用 `Map<String, Object>`。具体类名留给 Java 实现任务决定。

## 6. 分阶段迁移顺序

### 阶段 1：以 `DRAFT` 创建协作 PR

- 提交协议 Markdown、Schema、虚构示例和本迁移方案，协议状态保持 `DRAFT`。
- 主线验收通过后推送当前文档分支并创建协作 PR；创建 PR 不等于允许合并。
- 此阶段不改变 C++ 输出、Java 读取、持久化和 CLI 默认行为。

### 阶段 2：双方评审并接受协议后合并

1. C++ Analyzer 负责人在 PR 中逐项检查必填字段、稳定 ID、排序、证据、状态和限制是否能够由生产端实现，并留下真实评审意见。（已完成，见协议文档第 13 节）
2. Java 负责人复核 C++ 评审引起的全部差异，确认严格读取、状态映射和持久化边界仍然成立。（已完成，无差异需要复核）
3. 双方解决全部分歧，重新验证 Schema、正例、反例、内部链接和最终差异。（已完成，无未解决分歧）
4. 只有两位负责人都明确确认后，才把协议状态从 `DRAFT` 改为 `ACCEPTED`。（已完成）
5. 只有状态已经是 `ACCEPTED` 且 PR 正式 review 通过后，才能合并协议设计；不得先合并 `DRAFT` 再等待 C++ 评审。（本条已满足，本次合并即按此顺序执行）

### 阶段 3：Java 增加严格 v2 消费端

- 创建独立 Java 任务书和分支。
- 增加最小版本分派、v2 明确类型、Schema 和语义校验。
- 保留 v1 读取与测试；增加缺失版本、未知版本、未知字段、非法枚举、数字越界、路径逃逸、位置倒置、错误排序、错误 ID 和敏感文本测试。
- 为 v2 建独立快照白名单，验证 `FAILED` 不被当作可用报告。

### 阶段 4：C++ 增加显式 v2 输出

- 创建独立 C++ 任务书和分支。
- 保留 v1 路径，新增明确选择 v2 的方式；首次实现不改变默认版本。
- 从实际构建和规则目录写入版本；实现路径、证据、限制、稳定 ID 和排序。
- 当前没有实现的规则不输出，不使用占位证据满足 Schema。

### 阶段 5：联合契约测试

- 使用仓库内虚构 fixture 测试 C++ 生成、Schema 验证和 Java 读取同一报告。
- 同一输入连续运行，除 `taskId` 与 `generatedAt` 外规范化结果和 SHA-256 必须一致。
- 测试成功、部分成功、失败与规定反例；不使用真实第三方源码。

### 阶段 6：独立决定默认版本

只有第 7 节条件全部满足，才由独立任务和评审决定 CLI 默认输出何时从 v1 切换到 v2。协议设计和首次 v2 实现本身都不授权切换。

### 阶段 7：独立决定 v1 弃用与移除

先发布弃用说明并核对所有已知调用方。移除 v1 必须有单独决策、版本说明和迁移窗口，不得与 v2 首次实现或默认切换合并。

## 7. 默认输出切换条件

以下条件缺一不可：

1. v2 协议经 Java 与 C++ 负责人真实评审，状态为 `ACCEPTED`。
2. Java v1/v2 双版本读取、C++ 显式 v2 输出和双方联合 fixture 测试已合并。
3. Schema 自校验、两个正例、全部规定反例和实现层语义反例持续通过。
4. 同一 fixture 的规范化报告重复运行稳定，固定 `findingId` 向量在 Java 与 C++ 结果相同。
5. v2 默认输出不会泄露绝对路径、疑似凭据、进程输出或堆栈。
6. 现有 Java 调用、持久化与状态映射在 v1 和 v2 下都有迁移测试。
7. CLI 发布说明、显式选择旧版本的过渡方法和回退方案已经评审。
8. 默认切换由独立任务批准；没有未解决的 Java/C++ 边界分歧。

## 8. v1 弃用和移除条件

`弃用` 表示继续支持但不再增加能力，不等于立即删除。v1 只有在以下条件满足后才能进入移除决策：

- 默认 v2 已至少经过一个公开兼容周期。
- 已知 Java、CLI、持久化和外部调用方都已核对并具备 v2 路径。
- 文档给出明确截止版本和回退方法。
- 没有只能读取 v1 的受支持调用方。
- 另一个范围单一的任务和评审明确批准移除。

在此之前，`docs/02-analyzer-json-protocol.md`、v1 fixture、v1 Java Reader 和 v1 C++ 输出路径都必须保留。

## 9. 迁移验证清单

- [ ] v1 fixture 仍由 v1 Reader 接受。
- [ ] v2 两个正例由 Draft 2020-12 Schema 和 v2 Reader 接受。
- [ ] 缺少版本或未知版本不会回退。
- [ ] v1 的绝对 `repository.path` 不进入 v2 或 v2 快照。
- [ ] v1 自由证据的未知字段不进入 v2。
- [ ] v1 空文件和第 0 行不会机械转换成文件级位置。
- [ ] Java 不补造分析器版本、规则集版本、稳定 ID 或修复方向。
- [ ] v2 `FAILED` 报告不覆盖进程退出码或任务失败状态。
- [ ] CLI 默认输出在独立决策前保持 v1。
- [ ] v1 移除在独立决策前不执行。

本清单当前是迁移设计，不是实现完成记录。
