# Windows 桌面客户端开发与演示

开发入口需要 Windows x64、JDK 21 和 Maven Wrapper 能下载的构建依赖；首次构建需要联网，缓存完整后可离线构建。使用者可以改用第三阶段的本地候选便携包，见 [中文使用说明](windows-x64-desktop.zh-CN.md)，不需要开发环境。候选包尚未公开发布。分析和报告导入导出均在本地执行。

## 先理解三层职责

1. `AnalysisWorkspace` 是界面：按钮、系统目录/文件选择器、表格和普通文本，不实现分析规则。
2. `DesktopAnalysisService` 是应用服务：一次只处理一个操作，把耗时工作交给后台线程；将结果送回界面线程后更新状态。取消时先中断后台工作，等共享客户端清理后才允许再次开始。
3. `openpulse-analyzer-client` 是共享客户端：用参数列表启动 C++、处理超时/退出码/子进程、清理临时目录，并以正式 Schema 和语义校验严格读取报告。

依赖方向是 `Desktop → analyzer-client → C++`。桌面不依赖 Platform、Spring Boot、Web 服务或数据库。桌面显式请求协议 `2.0`，共享库和 Platform 的默认协议未变。

## 构建和启动

确认 `JAVA_HOME` 指向 JDK 21。本机验证使用 Temurin 21.0.11、Maven Wrapper 3.9.9、OpenJFX 21.0.13。不要用旧 Java 8 的 PATH 入口启动。依赖来源与许可证见 [桌面依赖说明](../openpulse-desktop/THIRD_PARTY_NOTICES.md)。

从仓库根目录进入 Wrapper 所在目录，后续命令都在 `openpulse-platform` 中运行，不需要设置 MAVEN_BASEDIR：

```powershell
Set-Location .\openpulse-platform
.\mvnw.cmd -f ..\pom.xml -pl openpulse-desktop -am "-Dopenpulse.analyzer.test.executable=C:\你的分析器目录\openpulse-analyzer.exe" package
..\scripts\run-desktop.ps1 -AnalyzerPath "C:\你的分析器目录\openpulse-analyzer.exe" -SkipBuild -Wait
```

把示例中的 exe 路径替换为已验收的 0.2.2 Windows x64 分析器。开发启动器把它复制到 `openpulse-desktop/target/dev-runtime/bin/openpulse-analyzer.exe`，应用只从这个应用目录定位分析器，不查系统 PATH。复制不表示已经发布便携包。

如果省略 `-SkipBuild`，启动器会先使用同一 Wrapper 聚合构建桌面模块及共享库。`-Wait` 保持启动会话，直到窗口关闭。所有开发依赖、复制的 exe 和日志都在忽略的 `target` 目录。

## 演示顺序

1. 点击“选择文件夹”，选择本地项目；也可在路径框粘贴完整路径。
2. 点击“开始分析”。显示不确定进度，不编造百分比；默认超时为 30 秒。过程中开始、导入和导出入口禁用，但标签页仍可操作。
3. 在“概览”看项目、版本、文件/语言统计及结构事实；在“发现与证据”选择发现，阅读原始证据和修复建议。
4. 部分成功时，顶部提示持续存在；进入“扫描限制”查看原文。COMPLETE、零发现、检测到测试都不代表代码无缺陷或测试通过。
5. 点击“导出 JSON”，选择保存位置；随后通过“打开 JSON”重新读取该文件。导入必须通过严格 v2 Reader；v1、未知版本、未知字段、重复键、不合法语义和超过 16 MiB 的报告会被拒绝。
6. 点击“重新分析”。开始新分析或导入时清除旧报告；失败后不会把上次结果继续显示成本次成功。
7. 运行较慢时点击“取消”；“正在取消”阶段等待进程清理，完成后可以再次开始。关闭窗口也会中断工作并等待清理后退出。

导出会重新序列化已校验的协议事实，并在临时文件上再次严格校验后替换目标；不保证 JSON 空白排版或字节与原文件相同。报告状态、ID、证据、建议等字段保持原值。协议允许省略的空字段保持省略，仓库级发现要求的 `location: null` 保留。

## 遇到问题

- 没有窗口：检查 `openpulse-desktop/target/desktop-stderr.log`，确认 JDK 21 和 `package` 成功；启动器使用可见窗口模式。
- 无法启动分析器：重新用正确的 `-AnalyzerPath` 启动，检查开发应用目录的 `bin` 文件及访问权限。
- 超时：进程会被终止并清理，选择更小的本地项目重试；不是把未完成扫描显示成成功。
- 报告拒绝：使用协议 2.0 的原始报告，按中文错误提示检查版本、格式或内容，不手工删字段绕过校验。
- 保存失败：检查目录权限、文件占用和磁盘空间，再选其他位置重试。当前已校验报告会保留，便于再次导出。
- 临时文件清理失败：检查占用及权限；界面会明确提示，不当作正常取消完成。

## 当前边界

没有分析历史、路径持久化、自动更新、AI 调用、数据库或网站。打开报告不会自动改写当前项目路径；重新分析仍以路径框为准，尤其在导入别的项目报告后请重新选择项目。

开发窗口与包级验收分开记录。第三阶段结果见 [Windows x64 便携分发验收](tasks/16-stage-3-windows-portable.md)。

## 维护者制作 Windows x64 候选包

`app-image` 是含原生启动 exe、应用和私有运行环境的目录；ZIP 只压缩这个目录，不是安装器。`jlink` 裁剪并链接运行环境，`jpackage` 创建启动器。运行环境捆绑让使用者无需另装 Java。候选包只表示本地待复审产物，不等于公开 Release。

官方要求已核对：[JDK 21 打包概览](https://docs.oracle.com/en/java/javase/21/jpackage/packaging-overview.html)、[jpackage 参数](https://docs.oracle.com/en/java/javase/21/docs/specs/man/jpackage.html)、[OpenJFX 构建文档](https://openjfx.io/openjfx-docs/)、[JavaFX 下载](https://gluonhq.com/products/javafx/)。Windows 镜像在 Windows 构建；这里只用 `--type app-image`，无需用于 MSI/EXE 安装器的 WiX。JavaFX 21 的最低 JDK 要求为 17，与本项目 JDK 21 兼容。

准备 Windows x64 PowerShell 7、Eclipse Temurin JDK 21（本次 21.0.11+10）、已验收的 0.2.2 CLI ZIP，以及 [OpenJFX 21.0.13 Windows x64 jmods](https://download2.gluonhq.com/openjfx/21.0.13/openjfx-21.0.13_windows-x64_bin-jmods.zip)。解压 jmods，参数指向含 `javafx.base.jmod`、`javafx.graphics.jmod`、`javafx.controls.jmod` 的目录。工具是构建前提，不是用户启动前提。上游源码和实际许可见 [依赖说明](../openpulse-desktop/THIRD_PARTY_NOTICES.md)。

下面从仓库根目录运行；打包脚本内部进入 `openpulse-platform` 调用 Wrapper，不需要设置 MAVEN_BASEDIR：

```powershell
pwsh -File .\scripts\build-windows-x64-desktop.ps1 `
  -AnalyzerPackage .\openpulse-analyzer\out\windows-x64\openpulse-analyzer-0.2.2-windows-x64.zip `
  -JavaFxJmods "C:\依赖\javafx-jmods-21.0.13" `
  -JavaHome "C:\你的 Temurin JDK 21"

pwsh -File .\scripts\verify-windows-x64-desktop.ps1 `
  -PackagePath .\openpulse-desktop\out\windows-x64\OpenPulse-0.2.2-candidate.1-windows-x64.zip `
  -OutputDirectory "$PWD\.tools\新的 中文验收目录" `
  -JavaHome "C:\你的 Temurin JDK 21"
```

验收目标目录必须不存在。脚本核对 ZIP/包内哈希、依赖清单、许可证、相对启动路径和私有模块，再以仅有 Windows System32 的子进程 PATH 验证真实分析、严格读写和 CLI v2 事实比较。维护者 JDK 只编译不随包的验收探针；运行探针使用包内 Java。随后必须单独做真实窗口验收，脚本成功不代表双击或离线新机已验证。

先在资源管理器实际双击新解压的 `OpenPulse.exe` 并关闭，再从仓库根目录启动环境隔离观察，手工完成选择、分析、取消、导出、重开与关闭。以下命令保持运行直到关闭窗口，证据目录必须是新目录：

```powershell
pwsh -File .\scripts\qa\start-isolated-desktop.ps1 `
  -ImageDirectory "$PWD\.tools\新的 中文验收目录\OpenPulse" `
  -EvidenceDirectory "$PWD\.tools\新的 中文验收目录\qa\窗口隔离"
```

观察器记录启动器及承载 JVM 的子进程，采集包内 DLL/分析器路径和退出后的残留情况。网络端点只作采样观察，不能当作断网证明；不要为此卸载工具或修改系统网络。包级自动结果、窗口截图和未验证环境分别记录。

构建脚本核对 `openpulse-desktop/distribution/portable-inputs.json` 中的 CLI/JMOD 哈希；升级版本、依赖或 JDK 厂商前先复审清单及许可。它运行共享客户端和桌面的完整测试，使用精确清单拒绝意外新增运行依赖。JavaFX 原生库由 JMOD 链入 `runtime/bin/javafx/`，不会依赖用户缓存或 SDK。JAR 固定时间戳、ZIP 固定排序/时间戳；可重复执行不承诺跨 JDK/跨机器字节相同。

产物位于忽略的 `openpulse-desktop/out/windows-x64/`；失败会移除本轮同名 ZIP/校验文件，临时构建目录仅在校验为本脚本创建的路径后删除。包含 `VERSION.json`（主线基点、工作区状态、工具链、模块及分析器哈希）、`FILES.sha256`、中文说明及实际许可。保留所有说明和许可一起交付，不提交产物，不自动 push、创建 PR 或发布。
