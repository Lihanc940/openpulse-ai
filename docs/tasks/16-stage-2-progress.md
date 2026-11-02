# 任务 16 第二阶段暂停时的恢复点

日期：2026-11-02。此文件保存用户要求暂停时的历史快照，后续已按“继续”恢复；以下“待办”和“尚未完成”描述暂停时状态。最新实现、验收结果和边界见 [第二阶段验收记录](16-stage-2-desktop-workflow.md)。

## 当前基线与工作区

- 第一阶段已通过 PR #35 合并，基线 `main` / `origin/main`：`d86a81d`。
- 已 fetch 最新 main，创建独立分支：`codex/desktop-workflow-stage2`。
- 本阶段改动尚未提交；没有 push、没有创建 PR，没有制作第三阶段便携包。
- 用户原有未跟踪的课程 Word 文档保持原样。

## 已实现但仍待最终评审的代码

- 根聚合 POM 加入 `openpulse-desktop`，Java 21，固定 OpenJFX 21.0.13，复用 `openpulse-analyzer-client`，没有引入 Platform、Spring 或数据库。
- `AnalysisWorkspace`：中文窗口、系统目录选择、可粘贴路径、开始/重新分析、取消、打开/导出 JSON、概览、发现列表、原始证据、建议、扫描限制。
- `DesktopAnalysisService`：单次后台任务、重复启动保护、取消后等待清理、晚到结果保护、失败清除旧结果、保存失败保留当前报告以便重试、关闭时中断工作线程。
- `ReportFiles`：复用严格 v2 Reader 导入；导出先写目标目录内临时文件，通过同一 Reader 校验后替换目标文件，保留协议要求的 null 与省略字段区别。
- `ReportPresentation`：普通文本展示，保留规则 ID、原始证据和限制；明确 COMPLETE/零发现/检测到测试不代表代码无缺陷或测试通过。
- `DesktopApplication` / `DesktopLauncher`：显式协议 2.0，默认超时 30 秒，从应用目录 `bin/openpulse-analyzer.exe` 定位，不依赖 PATH；关闭窗口等待后台清理。
- `scripts/run-desktop.ps1`：开发启动器，复制用户指定的已验收分析器到忽略的开发目录，使用 JAVA_HOME 和构建后的 JavaFX 依赖。`-Wait` 可保持工具启动会话。启动日志在 `openpulse-desktop/target/desktop-*.log`。
- 测试辅助 `SlowProcessMain` / `WindowProbe` 仅在 test 源码中，用于后续慢任务窗口验收，产品启动器不使用测试替身。

## 已验证事实

- `.tools/task16-stage2-test2.log`：桌面模块 23 个测试、共享客户端 90 个测试全部通过，无跳过；BUILD SUCCESS。传入真实分析器路径运行。
- 桌面测试覆盖：导入导出 SUCCESS/PARTIAL_SUCCESS/FAILED、无效版本/JSON/重复键/未知字段/语义篡改/超大报告、保存失败与旧文件保护、取消/重复启动/晚到回调/重新开始/关闭、真实进程树终止及临时目录清理、真实 C++ 中文空格多模块目录分析和导出重开，与直接 CLI 输出比较（只忽略 taskId/generatedAt）。
- 最初一项普通文本测试使用了会被已有语义校验器拒绝的绝对路径样式文本，已修正测试输入，没有放宽 Reader；随后上述测试通过。
- 首次窗口启动被启动器的 Hidden 选项隐藏，已改为 Normal，实际可见窗口启动成功。未改系统显示设置。
- Windows 实际点击已完成：系统目录选择器选取中文空格多模块目录；点击分析，显示真实 0.2.2 C++ 报告（2 文件、1 测试文件、子模块 pom.xml、3 条结构发现）；切换发现详情；导出 JSON；严格校验后重新打开；导入部分成功样例并显示两条限制；导入合法 FAILED 报告明确显示失败/无可用统计；导入 `{}` 被拒绝，旧报告清空，导出按钮禁用。
- Windows 界面验收通过 computer-use 技能进行。用户随后按物理 Escape 停止 Computer Use，并要求暂停；已停止，不能把最后一次“失败后重新分析”的点击当作已验证成功。

## 已保留截图与临时数据

截图目录：`docs/acceptance/task16-stage2/`。

1. `01-real-analysis.png`：真实 C++ 分析概览。
2. `02-findings-evidence.png`：发现列表、原始证据和修复建议。
3. `03-reopened-json.png`：导出并重新打开的报告。
4. `04-partial-limitations.png`：部分成功和扫描限制（协议样例，不是当前目录的真实部分成功）。
5. `05-failed-report.png`：FAILED 报告（生产 builder golden 补入合法动态字段的导入样例）。
6. `06-invalid-report.png`：无效报告被拒绝，旧结果清空。

忽略目录 `.tools/task16-stage2-qa/` 中有中文多模块样例、窗口导出 JSON、FAILED 与无效 JSON。当前实际窗口可能仍打开；恢复时先枚举并选择新返回的窗口对象，不复用旧句柄、截图 ID 或元素索引。

## 恢复后待办

1. 先核对用户的新指示、分支和工作区，不覆盖现有改动。
2. 补完真实窗口操作：失败后重新分析、慢任务下切换标签以确认响应、取消后再开始、运行中关闭窗口。可用 test-only WindowProbe + SlowProcessMain 验证进程树清理；必须在报告里标注测试替身，不能当作真实 C++ 扫描结果。
3. 检查常用窗口尺寸、长路径/长证据及显示缩放，保留必要截图；尚未完成这部分验收。
4. 评审应用服务状态与线程关闭、错误提示、导入导出边界；按需修正并重跑受影响测试。当前错误提示仍有 Reader 英文错误类别，可评估中文解释。
5. 补开发启动说明、JavaFX 来源与第三方许可证说明、第二阶段正式验收记录，并同步任务书、README、架构进度。尚未完成这些文档。
6. 运行适当的最终 Java 回归；本阶段尚未重新运行 Platform/MySQL 或完整联合契约，不得引用第一阶段结果冒充本阶段全量验收。
7. 检查 diff、截图和提交范围，不纳入 target、.tools、exe、个人 Word 文档。本阶段全部完成后创建单一范围本地提交，仍不 push、不创建 PR。

## 开发与验证入口

所有 Wrapper 命令从 `openpulse-platform` 目录运行，无需设置 MAVEN_BASEDIR：

```powershell
Set-Location .\openpulse-platform
.\mvnw.cmd -f ..\pom.xml -pl openpulse-desktop -am "-Dopenpulse.analyzer.test.executable=C:\你的分析器目录\openpulse-analyzer.exe" package
# 仍在 openpulse-platform 目录；启动开发窗口，不生成便携包
..\scripts\run-desktop.ps1 -AnalyzerPath "C:\你的分析器目录\openpulse-analyzer.exe" -SkipBuild -Wait
```

本机已实际使用的分析器位于忽略目录 `.tools/task15-build/Release/openpulse-analyzer.exe`。第三阶段 app-image/ZIP 分发尚未开始。
