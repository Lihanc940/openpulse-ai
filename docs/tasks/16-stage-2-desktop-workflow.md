# 任务 16 第二阶段：桌面操作闭环验收

日期：2026-11-02。基于 `main` 的 `d86a81d`（第一阶段 PR #35 已合并）。分支：`codex/desktop-workflow-stage2`。

状态：第二阶段本地实现与验收通过，尚未 push、创建 PR、合并或发布。第三阶段 app-image/ZIP 便携分发尚未实施，不能把本阶段开发窗口称为免装 JDK 的桌面发行包。

## 实现范围与学习入口

新增 `openpulse-desktop`，Java 21 / OpenJFX 21.0.13。Maven Wrapper 3.9.9；本机 JDK 为 Temurin 21.0.11，Windows x64。JavaFX 维护版本和许可证依据记录在 [第三方依赖说明](../../openpulse-desktop/THIRD_PARTY_NOTICES.md)。

| 层 | 代码 | 职责 |
| --- | --- | --- |
| 界面 | `AnalysisWorkspace` | 中文窗口、原生目录/文件选择、发现表格与普通文本展示 |
| 应用服务 | `DesktopAnalysisService` | 单次后台操作、状态、取消、失败恢复、派发界面更新 |
| 文件适配 | `ReportFiles` | 严格 v2 导入、校验后的 JSON 导出、临时文件替换与清理 |
| 展示模型 | `ReportPresentation` | 将已校验 DTO 映射为中文说明，保留原始规则、证据和建议 |
| 共享客户端 | 既有 `openpulse-analyzer-client` | 唯一 Reader、Schema/语义校验、进程参数、超时、中断与清理 |

初学者建议从“按钮调用应用服务 → 后台线程调用 Runner → Reader 校验 → 界面线程展示 Snapshot”这条链读起。后台线程执行耗时工作；界面线程只负责交互和展示。取消操作中断后台线程，等 Runner 清理结束后才发布终态。导入、导出同样不在界面线程读写文件。

桌面运行依赖没有 Platform、Spring、Web、JPA 或数据库驱动。C++、共享 Reader/Runner、正式 Schema、规则、CLI 默认协议、Platform 业务和数据库迁移均未修改。桌面显式请求协议 2.0、默认 30 秒超时；分析器从开发应用目录 `bin` 定位，不查 PATH。

本阶段提供目录选择、真实后台分析、取消、超时与失败恢复、概览/发现/证据/建议/限制、JSON 导出与严格导入。导出重新序列化协议事实，处理可省略字段并保留要求的 null，再经过同一 Reader 校验；不保证原 JSON 的排版或字节不变。

## 首次完整验收的自动化与回归结果

以下保留第二阶段首次完整验收结果；清理错误提示复审修正后的最新局部回归见下一节。

| 验证入口 | 实际结果 |
| --- | --- |
| 全模块 `clean verify`，传入真实 exe | BUILD SUCCESS |
| 共享客户端 | 90 通过，0 失败、0 错误、0 跳过 |
| 桌面模块 | 25 通过，0 失败、0 错误、0 跳过 |
| Platform 单元/API/快照回归 | 179 通过，18 个外部报告契约用例按普通入口跳过，0 失败、0 错误 |
| MySQL/Testcontainers | 8/8，0 跳过 |
| 桌面补充测试与运行依赖树 | 25 个桌面测试通过，包含覆盖已有导出文件；依赖树无服务端框架或数据库 |
| 联合契约脚本 | 完整 CTest 20/20，C01-C09 为 9/9，N01-N10 为 10/10 按预期拒绝，四份确定性哈希一致 |

桌面测试覆盖：

- SUCCESS / PARTIAL_SUCCESS / FAILED 报告导入导出；无效 JSON、重复键、错误版本、未知字段、语义篡改、超过 16 MiB 的报告被拒绝。
- 保存失败保留当前报告以便重试；无效导出不能替换旧文件；合法导出可以覆盖已有文件；临时导出文件清理。
- 单次操作、取消中禁止重启、清理完成后重启、晚到回调不覆盖取消、失败清除旧结果、关闭中断、清理失败不伪装成正常取消。
- 以真实 Java 测试父子进程验证取消/超时/关闭后的进程树终止和临时目录删除；该测试进程不是 C++ 规则分析。
- 以真实 C++ 扫描中文空格多模块目录，经桌面应用服务导出、重新打开；与直接 CLI 输出比较，仅忽略 taskId/generatedAt，其他事实一致。
- 普通文本展示保留不可信文本，不使用 WebView、HTML、自动外部链接或命令执行入口。

联合脚本的 SUCCESS 覆盖真实 C++ CLI；PARTIAL_SUCCESS/FAILED 覆盖生产 builder 场景，不将它们描述成真实文件系统扫描触发。普通 `verify` 中的 18 个跳过不是执行成功；它们由联合入口单独验证。未重新构建或发布 0.2.2 ZIP，本阶段也不宣称第三阶段包级验收通过。

## 主线复审修正：分析失败附带清理错误（2026-11-02）

主线发现 TIMEOUT 附带临时目录清理异常时，界面仍声称“已清理临时文件”。检查共享 Runner 确认：清理失败会作为 suppressed 异常附加到原执行异常，原错误分类保持不变。除取消和独立 CLEANUP_FAILED 外，原桌面提示还遗漏了其他执行错误附带的清理异常。

修正后，超时仍显示“分析超时”和进程结束信息，但在附带清理失败时不再声称清理成功，同时明确提示“临时文件清理失败”，并建议检查占用与访问权限。其他分析失败保留原始失败原因，追加同样的清理失败提示；独立 CLEANUP_FAILED 不重复追加。取消分支复用相同判定，原有取消清理回归测试保持不变。界面不显示原始异常详情或本机清理路径。

- 新增覆盖全部 13 种执行错误分类的附带清理异常测试，检查主因、清理提示、旧报告清除和再次分析恢复；这些是应用服务异常注入测试，不声称真实触发了所有操作系统清理失败。
- 新增清理正常的 TIMEOUT 对照测试，保留其原有提示；现有 `cancellationDoesNotHideCleanupFailure` 测试未修改并继续通过。
- 修复前，新增回归真实产生 12 个失败；独立 CLEANUP_FAILED 本就能报告清理失败。修复后，应用服务 25 项、进程 4 项、报告 10 项，共 39 个桌面测试全部通过，无失败、无错误、无跳过。
- 同一轮共享客户端 90 个测试通过，包含真实 C++ 分析器用例；BUILD SUCCESS。验证从 `openpulse-platform` 调用 Wrapper，未设置 MAVEN_BASEDIR。
- 本次只修改应用服务提示、相关测试和本验收记录。未修改共享 Runner、C++、协议、窗口布局或分发逻辑；未重复执行本节以上首次验收的 Platform/MySQL、联合契约和窗口操作，不把历史结果当作本轮重跑。
- 本地日志为 `.tools/task16-stage2-cleanup-before.log`、`.tools/task16-stage2-cleanup-after.log`；检查 diff 后 amend 到第二阶段原提交。不 push、不创建 PR，第三阶段未开始。

## Windows 实际窗口操作

通过 computer-use 技能在本机 Windows 桌面操作并保存截图。

| 操作 | 验证结果 |
| --- | --- |
| 系统目录选择器选择中文空格多模块项目 | 成功，路径框可显示并允许粘贴路径 |
| 点击分析 | 显示真实 C++ 0.2.2 v2 报告：2 文件、1 测试文件、子模块 pom.xml、3 条结构发现 |
| 查看发现与详情 | 严重程度、规则 ID、原始证据、修复建议可读，内容按普通文本展示 |
| 导出并重新打开 | 原生保存/打开对话框完成，严格 Reader 接受导出的 JSON |
| 部分成功/FAILED/无效报告 | 限制持续提示；FAILED 无可用统计；无效报告拒绝并清除旧结果，禁用导出 |
| 无效导入后重新分析 | 最终构建恢复到真实 SUCCESS，无旧结果误用 |
| 慢任务中切换标签 | 窗口保持响应，开始/导入/导出禁用，取消仍可用 |
| 取消后重新开始 | 先显示取消清理中，完成后允许重新开始；检查测试父子 PID 均退出，临时目录删除 |
| 第二次运行中关闭窗口 | 窗口与 JavaFX 进程退出；测试父子进程退出，临时目录删除 |
| 默认 30 秒超时 | 中文超时提示、可以再次开始；测试父子进程退出、临时目录删除；最终发现占位为“无法判断发现项” |
| 窗口布局 | 1120×780 默认工作区；960×680 / 125% 渲染、900×640 / 150% 渲染检查通过 |
| 长路径和长内容 | 路径框横向容纳长路径；问题、证据和建议自动换行/滚动，顶部按钮不被覆盖 |

慢任务、取消、运行中关闭和超时的窗口验收使用 test 源码中的 `WindowProbe` / `SlowProcessMain`，窗口标题明确写“测试替身”，仍调用真实共享 Runner。它只制造可控慢进程树，不提供虚构成功报告，不进入产品启动器。缩放通过 JavaFX `glass.win.uiScale` 设置，日志确认 render scale 为 1.25 / 1.5；没有更改 Windows 系统显示设置，不宣称已覆盖所有显示器/DPI 环境。长文本布局使用通过严格 Reader 的人工扩展报告样例，不冒充新规则的实际发现。

## 截图索引

路径均相对本文件；截图包含实施过程及最终复验。`06`、`07`、`13`、`14` 为最终构建复验，`14` 是最终真实分析概览。

- [01 真实分析](../acceptance/task16-stage2/01-real-analysis.png)、[02 发现与证据](../acceptance/task16-stage2/02-findings-evidence.png)、[03 导出重开](../acceptance/task16-stage2/03-reopened-json.png)。
- [04 部分成功与限制](../acceptance/task16-stage2/04-partial-limitations.png)、[05 FAILED 报告](../acceptance/task16-stage2/05-failed-report.png)、[06 拒绝无效报告](../acceptance/task16-stage2/06-invalid-report.png)、[07 失败后恢复](../acceptance/task16-stage2/07-recovered-analysis.png)。
- [08 运行中响应 / 125%](../acceptance/task16-stage2/08-responsive-slow-task-125.png)、[09 取消完成 / 125%](../acceptance/task16-stage2/09-cancelled-125.png)。
- [10 长问题 / 150%](../acceptance/task16-stage2/10-long-text-150.png)、[11 证据滚动](../acceptance/task16-stage2/11-long-evidence-scroll-150.png)、[12 长建议](../acceptance/task16-stage2/12-long-remediation-150.png)、[13 超时与无可用报告](../acceptance/task16-stage2/13-timeout-150.png)。
- [14 最终真实分析窗口](../acceptance/task16-stage2/14-final-real-analysis.png)。

## 复现入口与日志

用户操作、启动和排错见 [中文开发指南](../desktop-development.zh-CN.md)。所有 Wrapper 命令从 `openpulse-platform` 运行，未设置 MAVEN_BASEDIR：

```powershell
Set-Location .\openpulse-platform
# 完整 Java 回归（需要可用 Docker；将 exe 占位路径替换为已验收分析器）
.\mvnw.cmd -f ..\pom.xml "-Dopenpulse.analyzer.test.executable=C:\你的分析器目录\openpulse-analyzer.exe" clean verify
# 开发窗口；不是便携包
..\scripts\run-desktop.ps1 -AnalyzerPath "C:\你的分析器目录\openpulse-analyzer.exe" -SkipBuild -Wait
# 独立桌面与共享库测试，并检查运行依赖
.\mvnw.cmd -f ..\pom.xml -pl openpulse-desktop -am "-Dopenpulse.analyzer.test.executable=C:\你的分析器目录\openpulse-analyzer.exe" test dependency:tree -Dscope=runtime
# C++ / Java 契约入口
..\scripts\verify-analyzer-report-v2-contract.ps1
# 慢进程窗口：必须先 package/verify，使 target/runtime 与 test-classes 可用
..\openpulse-desktop\src\test\scripts\run-window-probe.ps1 -Markers ..\.tools\task16-window-probe -Scale '125%'
```

Probe 支持 `-Scale '150%' -Width 900 -Height 640`，可通过 `-ReportPath` 打开合法的测试报告、通过 `-RepositoryPath` 设置长路径进行布局检查。它默认 30 秒超时；需要测试更短超时可显式传入 `-TimeoutSeconds`，此时不应把用时记为默认超时。关闭或取消后，Markers 中的 parent.pid、child.pid 和 workdir.txt 可用于只读检查退出与清理。

本地日志在忽略目录：`.tools/task16-stage2-final-verify.log`、`.tools/task16-stage2-final-desktop.log`、`.tools/task16-stage2-contract.log`；窗口日志和测试标记在 `.tools/task16-stage2-qa/`。日志、exe、target 产物和个人 Word 文档不随提交分发。阶段实现和本记录通过单一本地 `feat(desktop)` 提交保存。

## 仍保留的边界

- 第三阶段未实施：没有捆绑 JRE、app-image、安装器或便携 ZIP，未做无 JDK/无开发工具 PATH 的解压双击验收。
- 新依赖来源与许可证已记录；完整运行环境、OpenJFX 原生第三方声明和分析器许可证的随包整理属于第三阶段。
- 初版只支持本地目录和单次操作，不持久化路径/历史，没有登录、数据库、网站、AI、Skill、MCP 或新检测规则。
- 导入报告不自动改变项目路径；重新分析以路径框为准。保存失败保留当前已校验报告供重试。
- PARTIAL_SUCCESS/FAILED 窗口截图来自严格协议样例/生产 builder golden 导入；不声称在此次真实项目扫描中自然触发这些状态。
- 本地实现验收通过仍不等于 PR 已合并或软件已公开发布。
