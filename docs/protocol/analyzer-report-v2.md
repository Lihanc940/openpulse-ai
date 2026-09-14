# OpenPulse Analyzer 报告协议 v2

## 1. 协议身份

- 协议版本：`2.0`
- 协议状态：`DRAFT`
- JSON Schema 标准：Draft 2020-12
- 正式 Schema：[analyzer-report-v2.schema.json](analyzer-report-v2.schema.json)
- 迁移说明：[analyzer-report-v2-migration.md](analyzer-report-v2-migration.md)
- 虚构示例：[成功](../examples/analyzer-report-v2.success.sample.json)、[部分成功](../examples/analyzer-report-v2.partial-success.sample.json)
- 约束来源：[v0.2 产品验证基线](../validation/v0.2-validation-baseline.md)

`DRAFT` 表示字段和规则已经形成可校验初稿，但尚未得到 C++ Analyzer 负责人的真实评审，也没有成为当前运行时默认协议。协议 v1 仍是唯一已经实现的兼容边界。

### 1.1 版本演进

`protocolVersion` 使用 `主版本.次版本` 字符串，本文件只接受精确值 `2.0`，不使用修订号。消费端只接受自己明确注册的精确版本，不能因为主版本相同就猜测兼容。

- 只改说明文字且不改变 Schema 或字段语义时，不提升协议版本。
- 保留全部旧合法报告及原语义的扩展属于兼容性变化，但严格模式下新增字段、枚举或证据种类仍需发布新的次版本 Schema 和消费端支持；`2.0` Schema 本身保持不变。
- 删除或重命名字段、改变类型或语义、把旧合法值变成非法值，属于破坏性变化，必须提升主版本。
- 分析器或规则实现变化只提升 `analyzer.version` 或 `ruleSet.version`；除非报告契约也变化，否则不提升协议版本。

## 2. 简单术语与双方契约

- **协议**：Java 与 C++ 交换报告时共同遵守的字段和行为规则。
- **生产端**：生成报告的 C++ Analyzer。
- **消费端**：读取、校验或保存报告的 Java，以及后续只读取报告的入口。
- **不变量**：每次输出都必须成立的规则，例如文件路径不能逃出仓库。
- **严格模式**：对象出现 Schema 未声明的字段时直接拒绝。
- **规范化**：比较两次报告前，只删除允许变化的字段，并以固定方式输出 JSON。

C++ 生产端是分析事实的唯一来源。消费端可以拒绝、保存、筛选和解释事实，但不能补造缺失发现、证据、版本或修复方向。Schema 通过只表示结构合法；本文件列出的跨字段、安全和排序不变量也必须通过，报告才符合协议。

## 3. 当前 v1 行为与 v2 目标

| 方面 | v1 当前真实行为 | v2 设计目标 |
| --- | --- | --- |
| 生产 | C++ 固定输出 `protocolVersion: "1.0"` 和 `SUCCESS` | 后续独立任务才实现可明确选择的 `2.0` 输出 |
| 仓库路径 | C++ 将规范化后的本机绝对路径写入 `repository.path` | 只写 `repository.root: "."`，文件位置使用安全相对路径 |
| 版本 | 没有分析器版本和规则集版本 | `analyzer.version` 与 `ruleSet.version` 必填 |
| 发现 | `risks`；当前只生成缺少 README、LICENSE、CI 的仓库结构发现 | `findings`；每条都有稳定 ID、范围、完整位置、受约束证据和修复方向 |
| 仓库级位置 | 空 `file` 与第 0 行 | `scope: "REPOSITORY"` 且 `location: null` |
| 证据 | 任意 JSON 对象；Java 持久化另用固定白名单 | 由 `kind` 区分的四种严格对象，未知字段拒绝 |
| 排序 | 语言按文件数降序，部分并列顺序没有协议保证 | 所有数组都有固定排序键 |
| Java 读取 | Reader 只接受 `1.0`，模型会忽略未知字段 | 后续使用协议版本分派；v2 独立严格读取，不把未知版本当成 v1 |
| Java 运行 | 超时、退出码、临时目录与受限诊断已有边界 | 保留进程边界；报告状态不能覆盖真实退出结果 |
| 持久化 | v1 快照删除仓库路径并对白名单字段二次校验 | v2 使用独立模型和独立白名单，不扩大敏感信息入口 |

以上 v2 内容是设计目标，不是当前产品能力。

## 4. 顶层对象

### 4.1 所有状态都必填

| 字段 | 类型 | 含义 |
| --- | --- | --- |
| `protocolVersion` | 字符串常量 `2.0` | 精确协议版本，不允许数字 `2` 或字符串 `2` |
| `taskId` | 字符串 | 一次运行的动态关联 ID；1 至 128 个受限字符 |
| `analyzer` | 对象 | 分析器名称和语义版本 |
| `ruleSet` | 对象 | 规则集 ID 和语义版本 |
| `status` | 枚举 | `SUCCESS`、`PARTIAL_SUCCESS` 或 `FAILED` |
| `reviewability` | 枚举 | 报告是否足以复核：`COMPLETE`、`PARTIAL` 或 `NOT_USABLE` |
| `repository` | 对象 | 不带本机路径的仓库身份 |
| `findings` | 数组 | 发现集合；`FAILED` 时必须为空 |
| `limitations` | 数组 | 跳过项、能力边界、资源限制或扫描失败 |
| `generatedAt` | 带时区 RFC 3339 字符串 | 报告生成时间；必须包含大写 `T`，并以 `Z` 或明确的 `+HH:MM` / `-HH:MM` 偏移结束 |

### 4.2 可用报告额外必填

`SUCCESS` 与 `PARTIAL_SUCCESS` 还必须包含 `summary`、`languages` 和 `structure`。`FAILED` 必须省略这三个字段，避免用零值冒充未完成的测量。

### 4.3 分析器、规则集与仓库

`analyzer` 必须恰好包含：

```json
{"name":"openpulse-analyzer","version":"0.2.0"}
```

`ruleSet` 必须恰好包含 `id` 和 `version`。`id` 最多 64 个小写字母、数字和分隔符。两个 `version` 都使用 `主版本.次版本.修订版本` 形式，最多 64 个字符，可以带标准预发布或构建后缀；生产端必须写实际版本，不能使用 `unknown`。

`repository` 必须包含：

- `name`：1 至 128 个字符，仅用于人识别，不能包含控制字符。
- `root`：固定为 `.`，表示报告内所有路径都相对本次扫描根目录。
- `revision`：可选，1 至 128 个受限字符；只有生产端可靠知道提交或内容修订号时才写，未知时省略，不能猜测。

不得加入本机绝对路径、下载临时目录、远端认证信息、用户账号或数据库 ID。

## 5. 状态、可复核性与限制

报告状态与进程退出码是两件事。发现风险不等于程序失败。

| `status` | `reviewability` | 数据要求 | 含义 |
| --- | --- | --- | --- |
| `SUCCESS` | `COMPLETE` | 统计、语言、结构和发现存在；`limitations` 为空 | 计划扫描范围已完成 |
| `PARTIAL_SUCCESS` | `PARTIAL` | 同上；至少一条限制；不得包含 `SCAN_FAILED` | 报告仍可复核，但明确存在跳过项或能力边界 |
| `FAILED` | `NOT_USABLE` | `findings` 为空；统计、语言和结构缺失；至少一条 `SCAN_FAILED` | 扫描没有形成可复核事实，只留下最小、脱敏的失败说明 |

`FAILED` 报告是尽力而为的诊断文件，不是成功结果。若进程无法生成合法 JSON，CLI 或 Java 运行器记录真实进程失败，任何消费端都不得伪造报告。若进程非零退出，即使旁边存在合法 `FAILED` 报告，也不能把任务改记为完成。

### 5.1 限制种类

每个限制对象都使用严格字段，并包含不超过 300 个字符的确定性 `message`：

| `kind` | 作用范围与必填细节 | 用途 |
| --- | --- | --- |
| `FILE_SKIPPED` | `scope: FILE`、`relativePath`、`reason` | 文件因权限、大小、类型、编码或读取错误被跳过 |
| `UNSUPPORTED_CAPABILITY` | `scope: REPOSITORY`、`capabilityType`、`capability` | 明确某语言或规则类型没有被分析 |
| `RESOURCE_LIMIT_REACHED` | `scope: REPOSITORY`、限制类型、配置值、至少观测值 | 达到文件数、字节数或时长上限 |
| `SCAN_FAILED` | `scope: REPOSITORY`、失败原因 | 扫描失败且报告不可用于复核 |

精确枚举如下：

- `FILE_SKIPPED.reason`：`FILE_TOO_LARGE`、`PERMISSION_DENIED`、`READ_ERROR`、`UNSUPPORTED_ENCODING`、`UNSUPPORTED_FILE_TYPE`。
- `UNSUPPORTED_CAPABILITY.capabilityType`：`LANGUAGE`、`RULE_TYPE`；`capability` 为 1 至 64 个受限字符。
- `RESOURCE_LIMIT_REACHED.limitType`：`BYTE_COUNT`、`DURATION_MS`、`FILE_COUNT`；`configuredLimit` 与 `observedAtLeast` 是安全整数。
- `SCAN_FAILED.reason`：`INTERNAL_ERROR`、`TRAVERSAL_ERROR`。

限制消息只能解释公开原因，不能包含原始异常、命令输出、堆栈、本机路径或凭据。`PARTIAL_SUCCESS` 的统计只描述已处理范围；限制数组负责说明缺失部分，不能把“不支持”写成“没有问题”。

## 6. 统计、语言与结构事实

`summary` 沿用 v1 的九个计数字段：`totalFiles`、`sourceFiles`、`documentFiles`、`configFiles`、`testFiles`、`totalLines`、`codeLines`、`commentLines`、`blankLines`。所有数字必须是 `0` 至 `9007199254740991` 的 JSON 整数，不能写成小数或字符串。

`languages[]` 的每项只包含 `name`、`files`、`lines`；同一名称最多一项。`structure` 沿用七个布尔事实和 `buildFiles`。`buildFiles` 每项都必须是安全相对路径。

类别可能重叠，例如测试文件也可能是源文件，因此不能要求文件分类之和等于 `totalFiles`。行统计必须满足 `codeLines + commentLines + blankLines <= totalLines`；等号不成立时，限制中必须解释未读取内容。

v2 删除 v1 的占位 `quality` 和空 `dependencies`。在有验证公式或真实依赖能力之前，不用新名字包装同一占位数据。

## 7. 发现模型

每条 `findings[]` 必须恰好包含：

| 字段 | 约束 |
| --- | --- |
| `findingId` | `sha256:` 加 64 位小写十六进制稳定指纹 |
| `ruleId` | 1 至 64 个大写字母、数字和下划线；属于报告声明的规则集版本 |
| `type` | `PROJECT_STRUCTURE`、`TEXT`、`SYNTAX_TREE` |
| `severity` | `LOW`、`MEDIUM`、`HIGH`、`CRITICAL` |
| `scope` | `REPOSITORY` 或 `FILE` |
| `location` | 仓库级为 `null`；文件级为完整位置对象 |
| `message` | 1 至 300 个字符的简短确定性说明，不含换行或控制字符 |
| `evidence` | 一种受约束证据对象，不能为空 |
| `remediation` | 1 至 500 个字符的修复方向，不含换行或控制字符 |

消息说明“发现了什么”，修复方向说明“下一步检查或修改什么”。两者都不能依赖 AI 补全，也不能包含整段源码。

### 7.1 路径和位置不变量

- JSON 中只使用 `/` 分隔路径，即使分析器运行在 Windows。
- 相对路径为 1 至 1024 个字符，不能以 `/`、`\\`、盘符或 URI scheme 开头，不能包含反斜杠、冒号、控制字符、空段、`.` 段或 `..` 段。
- `repository.root` 固定为 `.`；其他相对路径不使用前导 `./`。
- 行与列统一从 1 开始。文件级位置包含 `relativePath`、`startLine`、`startColumn`、`endLine`、`endColumn`。
- 结束位置不能早于开始位置：`endLine > startLine`，或同一行时 `endColumn >= startColumn`。该跨字段比较由生产端和消费端补充检查，因为通用 JSON Schema 正则不能可靠表达它。
- 仓库级发现必须使用 `scope: REPOSITORY` 和 `location: null`，不能再用空文件名或第 0 行表示“不适用”。
- `location: null` 是 v2 唯一允许的 `null`，只表示仓库级位置不适用；其他字段不能用 `null` 代替缺失。

### 7.2 受约束证据

`evidence.kind` 决定其唯一允许字段：

| `kind` | 其余必填字段 | 上限与用途 |
| --- | --- | --- |
| `EXPECTED_PATHS_ABSENT` | `expectedPaths` | 1 至 16 个安全相对路径；用于缺少仓库文件或目录 |
| `FILE_METRIC` | `metric`、`observed`、`threshold` | 文件字节数或行数事实 |
| `TEXT_MATCH` | `matchKind`、`occurrences`、`threshold` | 只保存匹配类别与计数，不保存匹配源码 |
| `SYNTAX_METRIC` | `symbolKind`、`symbolName`、`metric`、`observed`、`threshold` | 符号名最多 128 字符；保存语法度量，不保存函数正文 |

精确枚举如下：

- `FILE_METRIC.metric`：`BYTE_COUNT`、`LINE_COUNT`。
- `TEXT_MATCH.matchKind`：`DANGEROUS_FUNCTION`、`FIXME_MARKER`、`TODO_MARKER`。
- `SYNTAX_METRIC.symbolKind`：`FUNCTION`、`METHOD`。
- `SYNTAX_METRIC.metric`：`FUNCTION_LINES`、`MAX_NESTING_DEPTH`、`PARAMETER_COUNT`。

数值遵守安全整数范围。证据对象未知字段一律拒绝。计划中的规则任务必须逐条声明允许的证据种类；本设计不表示这些规则已经实现。当前 v1 的三类结构发现将来可映射到 `EXPECTED_PATHS_ABSENT`。`LONG_FILE` 可使用 `FILE_METRIC`；文本规则可使用 `TEXT_MATCH`；函数长度、参数数和嵌套深度可使用 `SYNTAX_METRIC`。没有合适证据种类的规则不能先输出再补协议。

证据、消息、修复方向和限制文本都禁止保存：源码正文、绝对路径、用户名、环境变量、stdout、stderr、异常堆栈、Token、私钥、Authorization Header 或其他疑似凭据。Schema 负责类型、长度、枚举和常见路径形状；生产端与消费端还必须运行相同的敏感文本检查，不能声称 Schema 能识别所有密钥格式。

## 8. 稳定 `findingId`

`findingId` 的输入由五段组成，并用单个 Unicode `U+001F` 分隔。协议字段禁止控制字符，因此分隔符不会与字段内容混淆。

```text
ruleId U+001F scope U+001F pathPart U+001F locationPart U+001F canonicalEvidence
```

- 仓库级发现的 `pathPart` 和 `locationPart` 都是单个 `-`。
- 文件级发现的 `pathPart` 是 `location.relativePath`。
- 文件级 `locationPart` 是 `startLine:startColumn:endLine:endColumn`，十进制整数无前导零。
- `canonicalEvidence` 使用第 10 节的规范 JSON。
- 对以上 UTF-8 字节计算 SHA-256，输出 `sha256:` 加 64 位小写十六进制。
- `taskId`、`generatedAt`、绝对路径、随机数、消息、严重程度和修复文案都不参与 ID。

固定例子：

```text
MISSING_LICENSE\u001fREPOSITORY\u001f-\u001f-\u001f{"expectedPaths":["COPYING","LICENSE"],"kind":"EXPECTED_PATHS_ABSENT"}
```

上面显示的 `\u001f` 表示实际的单个分隔字符，不是六个可见字符。UTF-8 SHA-256 结果必须为：

```text
sha256:b9dcda3c6f619d65163780c30df7792cc3baf402ba42e2adeab4d8031ac161a8
```

同一规则、位置和证据保持不变时，文案调整不会制造新发现；阈值或观测证据变化时 ID 会变化。

## 9. 数组语义和排序

v2 中所有数组在业务上都是无序集合，但生产端必须按下列稳定键升序输出。字符串比较使用 Unicode 码点顺序；相同稳定键禁止重复。

| 数组 | 最大条数 | 稳定排序键 |
| --- | ---: | --- |
| `languages` | 256 | `name` |
| `structure.buildFiles` | 1024 | 相对路径本身 |
| `findings` | 100000 | `findingId` |
| `limitations` | 10000 | `FILE_SKIPPED`: `kind U+001F relativePath U+001F reason`；`RESOURCE_LIMIT_REACHED`: `kind U+001F limitType`；`SCAN_FAILED`: `kind U+001F reason`；`UNSUPPORTED_CAPABILITY`: `kind U+001F capabilityType U+001F capability` |
| `evidence.expectedPaths` | 16 | 相对路径本身 |

连接多个排序字段时使用 `U+001F`，各字段本身禁止该控制字符。Schema 的 `uniqueItems` 只能拒绝完全重复的对象；生产端和消费端还必须检查稳定键唯一、数组已排序以及语言名唯一。

## 10. 规范 JSON 与报告哈希

文本编码统一为 UTF-8，不写字节顺序标记。规范 JSON 按以下规则生成：

1. 先按第 9 节排序并完成全部语义校验。
2. JSON 对象键按 Unicode 码点升序。
3. 数组保持生产端已经确定的协议顺序，规范化工具不得再次猜测排序。
4. 不输出多余空白。整数用十进制、无正号、无前导零；布尔值与 `null` 使用 JSON 小写字面量。
5. 字符串按 JSON 转义双引号、反斜杠和控制字符；其他 Unicode 字符直接以 UTF-8 输出。协议文本本身不允许控制字符。
6. 为验证基线计算整份报告的 `normalized_hash` 时，只移除顶层 `taskId` 和 `generatedAt`，再对规范 JSON 的 UTF-8 字节计算 SHA-256。

不得移除或改写状态、可复核性、协议/分析器/规则集版本、仓库身份、统计、路径、发现、证据、限制或修复方向。v2 没有运行耗时字段；未来若增加，必须先由新协议和新基线明确是否属于可移除动态字段。

## 11. Schema 之外仍须检查的不变量

生产端与 Java v2 消费端都必须在 Schema 后检查：

1. 文件位置结束点不早于开始点。
2. 行统计之和不大于总行数；差额与限制信息一致。
3. 所有数组符合第 9 节排序及稳定键唯一规则。
4. `findingId` 能按报告内容重新计算并完全相等。
5. `ruleId` 属于 `ruleSet.id + ruleSet.version` 指向的规则目录，且规则类型与证据种类获该规则批准。
6. `PARTIAL_SUCCESS` 的每个未处理范围都有对应限制；`SUCCESS` 不隐藏跳过项。
7. 所有文本通过绝对路径、凭据、进程输出和堆栈检查。
8. 可选 `repository.revision` 由可靠输入获得，没有值时省略而不是填占位词。
9. 使用能够验证 RFC 3339 的日期解析器完整解析 `generatedAt`，确认年月日和时间真实存在且带 `Z` 或明确时区偏移；不能只依赖 JSON Schema 的 `format` 注解或正则形状检查，因为部分校验器可能关闭 `format`，而正则也不能识别不存在的日期。

## 12. Java 消费端设计自查

本次只检查可实现性，没有修改 Java 代码。

| 检查项 | 结论 | 后续实现要求 |
| --- | --- | --- |
| 明确 Java 类型 | 通过 | 为 v2 新建独立根模型、枚举、位置类型和四种证据类型；不复用 `Map<String, Object>` |
| 严格未知字段 | 通过 | v1 保留当前兼容行为；v2 ObjectMapper/模型必须拒绝未知字段并执行 Schema 校验 |
| 协议分派 | 通过 | 先最小读取 `protocolVersion`，精确分派 `1.0` 或 `2.0`；缺失和未知版本拒绝 |
| 数字、枚举与位置 | 通过 | Schema 后检查安全整数、位置先后关系、稳定 ID 和排序 |
| 路径安全 | 通过 | 在 Java 中规范化 `/` 路径并再次拒绝绝对路径、URI、反斜杠、`.` 与 `..` 段 |
| 状态兼容 | 通过 | `SUCCESS`/`PARTIAL_SUCCESS` 才是可用结果；非零退出仍按真实失败处理；`FAILED` 报告只作受限诊断 |
| 持久化边界 | 通过 | 为 v2 建独立白名单；不得把 v1 的绝对 `repository.path` 或未批准证据带入 v2 快照 |
| 双版本共存 | 通过 | v1 模型、Reader 与快照保持不变；v2 在独立实现任务中增加 |

Java 本地设计自查状态：`COMPLETE`。这表示协议可映射且安全检查点明确，不表示 v2 Java 读取器已经实现或通过产品测试。最终协作差异仍需 Java 负责人再次复核。

## 13. C++ 负责人逐项评审清单

以下项目必须由 C++ Analyzer 负责人根据真实生产端实现约束逐项填写；当前全部保持 `PENDING`，本文不代签。

- [ ] 能稳定提供 `analyzer.version`、`ruleSet.id` 和 `ruleSet.version`，且不使用占位值。
- [ ] 能保证 `repository.root` 固定为 `.`，所有报告路径为 `/` 分隔的安全相对路径。
- [ ] 能按第 8 节使用 UTF-8、`U+001F` 和 SHA-256 重现固定 `findingId` 例子。
- [ ] 能为仓库级发现输出 `location: null`，不再依赖空路径和第 0 行。
- [ ] 能为文件级发现提供 1-based 完整起止行列，并检查结束点不早于开始点。
- [ ] 当前结构规则能产生 `EXPECTED_PATHS_ABSENT`，不会为未实现规则伪造证据。
- [ ] 四种证据结构和字符串/数组/整数上限可实现，未知字段不会输出。
- [ ] 能严格区分 `SUCCESS`、`PARTIAL_SUCCESS`、`FAILED`，并按状态输出对应字段和限制。
- [ ] 能用限制对象区分文件跳过、能力不支持、资源上限和扫描失败，消息不含原始诊断。
- [ ] 能按第 9 节排序所有数组，并拒绝相同稳定键的重复项。
- [ ] 能按第 10 节生成规范 JSON；只有 `taskId` 与 `generatedAt` 可在基线哈希前移除。
- [ ] `quality` 与空 `dependencies` 在 v2 中不输出。
- [ ] v2 可作为明确选择的输出路径实现，不要求本任务或首次实现立即改变 CLI 默认版本。
- [ ] Schema 没有迫使 C++ 声称当前不存在的语法树、文本或依赖分析能力。

评审人：`PENDING`

评审时间：`PENDING`
意见或未解决分歧：`PENDING`

## 14. 评审状态

| 角色 | 状态 | 说明 |
| --- | --- | --- |
| Java / Consumer 本地设计自查 | `COMPLETE` | 已检查现有模型、Reader、ProcessRunner、SnapshotFactory 及测试；尚未实现 v2 |
| C++ Analyzer 生产端评审 | `PENDING` | 等待负责人真实逐项意见，不代签 |
| 共同接受 | `PENDING` | 只有分歧解决且双方真实确认后才能改为 `ACCEPTED` |

当前未解决事项只有协作评审本身：C++ 负责人尚未确认生产端可实现性。因此协议保持 `DRAFT`。

## 15. 本任务不实现

本协议草案不修改 Java、C++、Vue 或构建配置；不实现 CLI 切换、Tree-sitter、新规则、安装器、Skill、MCP、AI 或实验；不删除协议 v1；不包含真实第三方源码、真实报告或实验结果。
