# OpenPulse AI 第八步：v0.2 产品验证基线与实验设计

## 任务定位

- 负责人：共同任务，Java 负责人主笔，C++ 负责人评审
- 建议分支：`docs/v02-validation-baseline`
- 所属阶段：P3 本地产品与透明报告、P4 Skill 价值验证的共同入口
- 前置任务：任务 7 持久化和产品方向 v0.2 已经合并到 `main`
- 任务性质：只编写验证文档、样本清单和空白记录模板

本任务不开发新功能，也不开始正式实验。它要在报告协议 v2、CLI 分发和 Codex Skill 实现之前，先固定我们准备验证的假设、样本、指标和继续或停止门槛。

完成后，后续任务不能因为结果不好看而临时修改指标。确实需要调整时，必须新建文档提交，说明原因和调整时间，保留旧版本作为记录。

## 为什么要单独做这一步

“做出了功能”和“用户得到价值”不是一回事。如果先写完功能，再决定怎样证明它有用，很容易出现：

- 只挑选对 OpenPulse 有利的仓库。
- 把文件统计或文本搜索包装成深度语义分析。
- 看到结果后再降低成功标准。
- 无法公平比较 AI 直接阅读仓库与 OpenPulse 辅助两种方式。
- Token、耗时和误报没有统一记录，最后只能凭感觉争论。
- 试用者遇到安装问题，但团队把口头帮助后的成功当成独立安装成功。
- 报告包含绝对路径、代码片段或疑似密钥，却被提交进公开仓库。

因此先建立验证基线。这里的“基线”就是实验开始前固定的规则和记录方式，作用类似测试的预期结果。

## 开始前阅读

- `README.md`
- `docs/00-glossary.md`
- `docs/02-analyzer-json-protocol.md`
- `docs/04-architecture.md`
- `docs/06-roadmap.md`
- `docs/08-week-2-checklist.md`
- `docs/09-product-direction-v0.2.md`
- `docs/tasks/02-java-analyzer-report-parser.md`
- `docs/tasks/03-java-analyzer-process-runner.md`
- `docs/tasks/07-java-analysis-persistence.md`
- 当前 C++ analyzer 的 CLI 参数、报告生成代码和测试
- 当前 Java `AnalyzerReportReader`、`AnalyzerProcessRunner` 和持久化快照测试

选择真实公开仓库时，应查看仓库主页、许可证和固定 commit。不要仅凭搜索结果摘要判断仓库性质。

## 本步骤需要理解的名词

新对话在使用这些概念前，应先用简单语言解释：

- **产品假设**：团队暂时相信、但还需要证据证明的判断，例如“结构化报告能减少 AI 阅读仓库的上下文消耗”。
- **验证基线**：实验开始前固定的样本、步骤、指标和判断标准。
- **指标（Metric）**：可以记录和比较的数值或分类，例如运行耗时、有效发现数和误报数。
- **门槛（Threshold）**：提前约定的最低要求，决定继续、返工或停止。
- **样本（Sample）**：被用于验证的真实仓库或试用者。
- **固定提交（Pinned Commit）**：使用完整 Git commit SHA 锁定仓库内容，避免上游变化导致前后结果不可比较。
- **选择偏差（Selection Bias）**：只挑选容易得到好结果的样本，导致结论失真。
- **对照实验**：保持目标和条件尽量一致，只改变一个关键因素，再比较结果。
- **对照组**：AI 直接阅读仓库，不使用 OpenPulse 的实验方式。
- **实验组**：AI 先读取 OpenPulse 摘要，再按需读取证据的实验方式。
- **变量**：实验中可能影响结果的条件，例如模型版本、提示词、仓库 commit 和推理设置。
- **可复现性**：另一人按照文档操作，能够得到相同或可解释的结果。
- **规范化（Normalization）**：比较报告前移除 taskId、生成时间和耗时等每次必然变化的字段，并固定数组顺序。
- **摘要哈希**：对规范化结果计算的短标识；相同输入得到相同标识，表示内容一致。
- **误报（False Positive）**：工具报告了问题，但人工复核后确认它并不存在或规则不适用。
- **有效发现（Confirmed Finding）**：人工能够根据证据确认，并且规则描述与实际情况一致的问题。
- **不确定发现（Uncertain Finding）**：现有证据不足，不能安全判断为有效或误报。
- **准确率（Precision）**：`有效发现数 / (有效发现数 + 误报数)`；不确定项不进入分母，但必须单独报告。
- **可行动（Actionable）**：用户理解问题后，能指出下一步检查或修改动作，而不只是看到模糊评价。
- **中位数（Median）**：把数值排序后位于中间的值，比平均数更不容易被一次极慢运行影响。
- **继续/停止判断（Go/No-Go）**：根据预先门槛决定继续扩展，还是先修正核心问题。

## 本任务交付物

本任务应创建以下验证目录和文件：

```text
docs/validation/
  v0.2-validation-baseline.md
  repository-samples.csv
  repository-runs.csv
  finding-reviews.csv
  ai-comparisons.csv
  user-trial-template.md
```

职责如下：

- `v0.2-validation-baseline.md`：保存假设、实验步骤、门槛、失败处理和版本信息。
- `repository-samples.csv`：保存 10 个仓库的固定样本信息。
- `repository-runs.csv`：以后逐次记录分析器运行数据；本任务只创建表头和字段说明，不填写伪造结果。
- `finding-reviews.csv`：以后逐条保存两位复核者的独立判断和分歧处理；本任务只创建表头和字段说明，不填写伪造结果。
- `ai-comparisons.csv`：以后记录 AI 对照实验；本任务只创建表头和字段说明，不调用 AI。
- `user-trial-template.md`：以后由每位试用者复制填写；不得提前编造试用反馈。

CSV 使用 UTF-8、英文逗号和一行表头。包含逗号、换行或双引号的字段必须按 CSV 规则转义。时间统一使用带时区的 ISO 8601 格式，耗时统一使用毫秒。

## 需要冻结的产品假设

基线文档至少记录以下假设，并明确它们目前都不是已证明的产品事实：

### H1：确定性报告有独立价值

不使用 AI 时，用户仍能从终端摘要和 JSON 中理解仓库事实、发现证据和修复方向。

### H2：相同输入产生稳定结果

分析器版本、规则版本、配置和仓库 commit 相同时，移除明确的运行时字段后，报告内容保持一致。

### H3：OpenPulse-first 能改善 AI 使用过程

与 AI 直接遍历仓库相比，让 AI 先读取 OpenPulse 摘要、再按需读取证据，可能减少输入 Token 或完成时间，同时不降低有效发现质量。

`OpenPulse-first` 表示 AI 优先使用 OpenPulse 报告，但在证据不足时仍允许读取必要源文件。不能为了让 Token 数据好看而禁止 AI 验证问题。

### H4：新用户能够独立安装

不是项目成员的 Windows x64 用户能够仅根据公开文档安装、运行、理解错误并卸载 OpenPulse。

## 仓库样本选择

### 样本数量

- 正式评估样本固定为 10 个公开仓库。
- 另外可以保留少量已知结果的测试 fixture，但 fixture 不计入 10 个正式样本。
- 在第一次正式运行前冻结完整 commit SHA；运行后不得因为结果不理想替换样本。

### 语言分布

- 4 个以 Java 为主的仓库。
- 4 个以 C++ 为主的仓库。
- 2 个以其他语言为主的对照仓库，只验证仓库级事实和不支持能力的诚实表达。

### 成熟度与规模分布

- 至少 3 个结构较完整、CI 和测试清楚的成熟仓库。
- 至少 3 个明确缺少一种项目结构要素的仓库。
- 其余仓库用于覆盖不同目录布局和规模。
- 必须在最终选样前定义小型、中型和接近限制三档规模的数值边界，并写明使用 GitHub 元数据、压缩包大小还是解压大小作为依据。
- 样本不能全部来自同一组织、同一模板或同一个人。
- 样本大小必须处于当前安全限制内；接近限制的样本要单独标记。

### 选择要求

- 必须有清晰的开源许可证。
- 保存仓库 URL、完整 commit SHA、主要语言、许可证、选择理由和选取日期。
- 在运行 OpenPulse 前，只能根据仓库元数据、许可证和预设分层选择，不预先查看工具结果。
- 不选择恶意代码样本、包含真实泄露凭据的仓库、需要登录的仓库或来源不明的压缩包。
- 不把第三方仓库源代码复制进 OpenPulse 仓库。

`repository-samples.csv` 建议字段：

```text
sample_id,repository_url,commit_sha,primary_language,license,selection_group,size_group,selected_at,selection_reason,notes
```

## 分析器运行实验

### 运行条件

- 对 10 个固定 commit 分别运行分析器。
- 每个样本至少连续运行 3 次。
- 使用相同分析器版本、规则版本和配置。
- 每次运行使用新的输出目录，不能复用上次报告。
- 记录操作系统、CPU 架构和安装包版本，不记录用户名或个人绝对路径。
- 单次超时、大小限制和退出码必须按公开配置记录。

### 规范化比较

报告比较前只能移除或统一以下非确定字段：

- `taskId`
- `generatedAt`
- 运行耗时
- 明确声明为无序集合的数组顺序

不得删除规则结果、路径、统计数或证据来制造一致。规范化规则必须写进基线文档，后续改变时提升基线版本。

### 运行记录字段

`repository-runs.csv` 至少包含：

```text
baseline_version,sample_id,run_number,commit_sha,analyzer_version,rule_set_version,started_at,duration_ms,exit_code,report_status,report_bytes,normalized_hash,total_findings,complete_evidence_count,absolute_path_leak,suspected_secret_leak,review_status,notes
```

运行失败也必须保留一行记录。不得只记录成功结果。

## 人工复核规则发现

每条不同的发现至少由一人复核，影响 Java/C++ 共同协议或有争议的发现由两人共同复核。

复核结果只允许：

```text
CONFIRMED
FALSE_POSITIVE
UNCERTAIN
NOT_APPLICABLE
```

每条复核必须记录：

- 基线版本、稳定的发现唯一标识、`sampleId`、完整 commit SHA 和 `ruleId`。
- 相对文件路径、起止行列位置和证据 SHA-256 指纹。
- 规则类型：仓库结构、文本或语法树。
- 两位复核者各自的匿名编号、复核结果、是否可行动、一句简短依据和带时区复核时间。
- 最终复核结果、最终是否可行动，以及存在分歧时的处理说明。

`finding-reviews.csv` 表头：

```text
baseline_version,finding_id,sample_id,commit_sha,rule_id,relative_path,start_line,end_line,start_column,end_column,evidence_fingerprint,rule_type,reviewer_1_id,reviewer_1_verdict,reviewer_1_actionable,reviewer_1_rationale,reviewer_1_reviewed_at,reviewer_2_id,reviewer_2_verdict,reviewer_2_actionable,reviewer_2_rationale,reviewer_2_reviewed_at,final_verdict,final_actionable,disagreement_resolution,notes
```

`relative_path` 必须是仓库内相对路径。`evidence_fingerprint` 使用规范化证据的 SHA-256，不保存源码正文。普通发现只需要一人复核时，第二位复核者字段保持为空；影响 Java/C++ 共同协议、存在争议或第一位复核者选择 `UNCERTAIN` 时，第二位复核者字段必须完整。只有所需复核完成后才能填写 `final_verdict` 和 `final_actionable`；两人判断不一致时必须填写 `disagreement_resolution`。

不要把 `UNCERTAIN` 强行算作有效发现。存在分歧时保留两人的判断和最终决定，不删除原记录。

## AI 对照实验设计

本任务只写实验协议和 CSV 表头，不实际调用 AI。只有报告协议、CLI 和 Codex Skill 达到对应任务验收标准后，才能填入正式结果。

### 固定条件

- 使用同一个明确模型版本和相同推理设置。
- 使用同一个仓库 commit 和同一份审查目标提示词。
- 每次从没有仓库历史的新对话开始。
- 不复用上一轮回答或人工提示。
- 记录 AI 是否能够访问仓库文件、OpenPulse 报告和证据片段。
- 每种方式在至少 3 个代表仓库上各独立运行 2 次。

### 对照组

```text
AI + 仓库访问 + 固定审查提示词
```

不提供 OpenPulse 报告，也不暗示预期问题。

### 实验组

```text
AI + 仓库访问 + 同一审查提示词 + Codex Skill + OpenPulse 报告
```

Skill 要求 AI 先读摘要、按需取证；证据不足时允许读取源文件并记录发生次数。

### 记录字段

`ai-comparisons.csv` 至少包含：

```text
baseline_version,pair_id,sample_id,mode,model,reasoning_setting,prompt_version,run_number,started_at,duration_ms,input_tokens,output_tokens,token_source,confirmed_findings,false_positives,uncertain_findings,unsupported_claims,source_file_reads,completed,notes
```

要求：

- Token 只记录 API 或工具实际提供的数值，不能自行估算。
- 无法获得 Token 时填写 `NOT_AVAILABLE`，并依靠耗时和质量指标，不得写成 `0`。
- AI 输出不直接作为“正确答案”；必须由人工根据仓库证据复核。
- 不把完整 AI 对话、整段源码或含个人信息的内容提交到公开仓库，只保存必要统计和短摘要。

## 用户安装试用

至少邀请 3 名不是项目成员、没有参与安装脚本开发的 Windows x64 用户。

试用流程：

1. 试用者阅读公开安装文档。
2. 从发布包开始计时。
3. 在一个允许使用的本地仓库上运行分析。
4. 找到终端摘要和 JSON 报告。
5. 说明至少一条发现的证据和建议动作。
6. 执行卸载或删除安装内容。

`user-trial-template.md` 至少记录：

- 匿名试用编号，不记录真实姓名、账号或联系方式。
- 操作系统版本和架构。
- 开始时间、首次成功时间和总耗时。
- 是否需要项目成员口头帮助。
- 安装、运行、理解报告和卸载分别是否成功。
- 遇到的原始错误类别和用户自己的简短反馈。
- 是否愿意再次使用，以及具体原因。

项目成员一旦提供了文档以外的命令、远程操作或文件修改，该次试用必须标记为“需要人工帮助”，不能算独立安装成功。

## 初始成功门槛

这些门槛是探索阶段的继续/停止标准，不是市场宣传数据。

### 核心分析门槛

- 10 个正式样本都必须在限制时间内结束，结果可以是明确的成功、部分成功或受控失败，但不能崩溃或无限等待。
- 10 个样本各运行 3 次后，规范化报告必须全部一致。
- 所有报告发现都必须包含完整规则编号、类型、相对位置、证据和修复方向。
- 不允许出现绝对路径、疑似密钥、stdout、stderr 或异常堆栈泄露。
- 人工复核准确率初始门槛为 80%，并同时公开 `UNCERTAIN` 数量。
- 至少 5 个样本产生一条被确认且可行动的发现。

### 安装门槛

- 至少 2/3 试用者在 10 分钟内完成首次分析。
- 至少 2/3 试用者不需要项目成员提供文档以外帮助。
- 所有试用者都能完成卸载或明确知道安装内容位于哪里。

### AI 辅助门槛

- 实验组的有效发现中位数不能低于对照组。
- 实验组的误报和无依据断言不能高于对照组。
- 在至少 2/3 代表仓库上，实验组满足以下至少一项：输入 Token 降低 20%，或完成时间降低 20%。
- 如果 Token 不可获得，不能宣称节省 Token；只能依据可测量的耗时和质量结果判断。

样本较少，因此这些门槛只能支持下一阶段工程决策，不能证明普遍市场效果。

## 继续、返工和停止规则

### 可以继续报告协议和 CLI

基线文档、样本和模板经过两人评审后，可以开始报告协议 v2 与 C++ CLI 分发任务。

### 必须先返工分析核心

出现以下情况时，不开始 Skill 或 MCP：

- 规范化结果不稳定。
- 存在崩溃、无限等待或敏感信息泄露。
- 发现缺少证据，或准确率低于 80%。
- 大部分正式仓库只有低价值结构统计，没有可行动发现。

### 不继续 MCP

AI 对照实验未达到辅助门槛时，保留本地 CLI 产品，但不继续开发 MCP，也不宣传减少 Token。

### 重新评审产品入口

如果试用者最强需求明显是 PR 自动检查、历史对比或团队共享，应记录证据，再从 GitHub Action、MCP 和团队网站中选择一个，不能同时开始三个方向。

## 安全与数据要求

- 本任务只使用有明确许可证的公开仓库，不使用私人或未授权代码。
- 不提交第三方仓库源码、下载压缩包、构建产物或完整分析工作目录。
- CSV 和 Markdown 中只保存相对路径、规则信息和必要短摘要。
- 不提交 Token、Cookie、Authorization Header、个人目录、真实姓名、联系方式或完整 AI 对话。
- 发现疑似密钥时，只记录规则 ID、相对位置和已脱敏类型，不保存密钥内容。
- 正式实验运行前检查 `.gitignore` 是否覆盖报告输出和临时工作区；需要调整时另开范围明确的工程任务。
- 试用者必须知道记录哪些数据，并可以要求删除自己的试用记录。

## 文档与版本要求

`v0.2-validation-baseline.md` 至少包含：

- 基线版本，例如 `baselineVersion: 0.1`。
- 生效日期和两位评审人。
- H1 至 H4 假设。
- 样本选择规则和最终 10 个固定 commit。
- 运行、人工复核、AI 对照和安装试用步骤。
- 指标定义、公式和成功门槛。
- 已知限制、失败处理和变更记录。

所有结果文件都必须保存 `baseline_version`。基线更新时增加版本，不覆盖旧版本含义；已经产生的数据仍按运行时使用的基线解释。

## 验证要求

本任务没有产品代码变化，不需要运行 Java 或 C++ 全量测试。必须执行：

```powershell
git status --short
git diff --check
git diff --name-only
```

并人工确认：

- 只修改任务书、README/路线状态和 `docs/validation/` 文档或空白模板。
- 10 个样本的 URL、许可证和完整 commit SHA 可以从公开来源核对。
- CSV 表头与任务书字段一致，并能被常见表格工具按列打开。
- 内部文档链接都存在。
- 结果模板为空，未把计划值伪装成实验结果。
- 没有个人路径、密钥、第三方源码或大段 AI 输出。

## 明确不做

本任务禁止提前加入：

- Java、C++、Vue 或构建配置修改。
- 报告协议 v2 的正式字段实现。
- Tree-sitter 依赖或任何新分析规则。
- CLI 安装包、发布脚本或安装器实现。
- Codex Skill、MCP Server 或 GitHub Action 实现。
- AI API 调用或正式对照实验。
- 正式用户试用或伪造试用反馈。
- 健康总分、规则权重或排名。
- 网站、用户系统、私有仓库授权或团队功能。
- Redis、消息队列、微服务或复杂异步执行。
- 为达到样本数量而使用来源不明或无许可证仓库。

## 完成标准

以下条件全部满足后，本任务才算完成：

1. `docs/validation/v0.2-validation-baseline.md` 明确记录 H1 至 H4、指标、门槛和停止条件。
2. 10 个正式公开仓库已经按语言和成熟度要求选定，并固定完整 commit SHA。
3. 仓库样本、运行、发现复核、AI 对照和用户试用模板字段完整且没有伪造结果。
4. 规范化规则、基于 `final_verdict` / `final_actionable` 的准确率与可行动率公式，以及 AI 公平对照条件写清楚。
5. 两位负责人都评审样本和门槛，影响 C++/Java 边界的意见已解决。
6. 所有引用可访问，CSV 格式可读取，`git diff --check` 通过。
7. 改动只有文档和空白数据模板，没有产品代码、依赖或实验输出。
8. 创建一个范围单一的 docs commit，不 push、不创建 PR，回主线对话复核。

## 回主线对话时提交的信息

任务完成后，请按以下格式汇报：

```text
分支：
commit：

基线版本：
交付文件：

10 个样本分布：
- Java：
- C++：
- 其他语言对照：
- 成熟仓库：
- 缺少结构要素仓库：

冻结的产品假设：
记录指标：
核心分析门槛：
安装门槛：
AI 辅助门槛：
停止条件：

样本 URL、许可证和 commit 核对结果：
CSV 可读性检查：
git diff --check：
工作区状态：

确认没有执行：
- 产品代码修改
- AI 对照实验
- 正式用户试用
- Tree-sitter、Skill、MCP 或安装器实现

我理解的新名词：
遇到的问题：
需要两人共同决定的事项：
```

## 新对话启动提示词

在相同项目工作区创建新对话后发送：

```text
请先阅读 README.md、docs/00-glossary.md、docs/02-analyzer-json-protocol.md、
docs/04-architecture.md、docs/06-roadmap.md、docs/08-week-2-checklist.md、
docs/09-product-direction-v0.2.md 和
docs/tasks/08-v02-validation-baseline.md，
并检查当前 main、现有 C++ analyzer、Java AnalyzerReportReader、
AnalyzerProcessRunner 和持久化快照测试。

我是 Java 初学者，请严格完成“OpenPulse AI 第八步：v0.2 产品验证基线与实验设计”。
每使用一个新名词前先用简单语言解释，再执行对应操作并验证。
从最新 main 创建 docs/v02-validation-baseline 分支。

本任务只创建验证基线、10 个公开仓库固定样本以及空白记录模板。
选择仓库时核对公开来源、许可证和完整 commit SHA，不能先看 OpenPulse
结果后再挑样本，也不能复制第三方源码到本仓库。
明确记录 H1 至 H4、规范化规则、人工复核方法、AI 公平对照条件、
安装试用流程、成功门槛和停止条件。

不要修改 Java、C++、Vue 或构建配置；不要实现报告协议 v2、Tree-sitter、
CLI 安装器、Codex Skill、MCP 或 GitHub Action；不要调用 AI，
不要开始正式用户试用，也不要伪造任何结果。

完成后检查 CSV 表头、内部链接、敏感信息和 git diff，
创建一个范围单一的本地 docs commit，但不要 push 或创建 PR。
最后按任务书格式生成回主线对话的验收报告。
```
