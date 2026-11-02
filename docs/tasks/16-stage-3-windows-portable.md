# 任务 16 第三阶段：Windows x64 便携分发验收

日期：2026-11-02。分支：`codex/desktop-portable-stage3`。

前置核实：PR #36 已关闭且已合并，合并提交 `f80f917a7ffe32c0cab76e5838139d261e6ec16f`；同步最新 `main` 后从该提交创建独立分支。开工工作区只有原有个人 Word 文档未跟踪，保留且不纳入提交。

状态：第三阶段本地实现与以下验收通过，待主线复审；不 push、不创建 PR、不合并、不发布。`0.2.2-candidate.1` 是本地桌面候选，分析器 0.2.2 仍是候选，不能称作公开发行。任务 16 第一、二阶段已合并，第三阶段尚未合并，尚未达到贡献指南的完整合并定义。

## 本阶段职责和构建依据

`app-image` 是可运行的完整目录：启动 exe、应用 JAR、分析器和私有运行环境。运行环境捆绑让用户解压后双击，无需另装 Java。ZIP 是传递该目录的容器；候选表示待复审，不等于 Release。

界面仍负责选择与显示，应用服务仍负责后台操作，共享客户端仍负责严格 Reader 和进程生命周期。打包脚本只负责组装这些已验收模块，不增加分析规则。没有更改任何 Java/C++ 业务代码、Schema、CLI 默认协议或数据库迁移。

官方要求与参数核对：

- [JDK 21 Packaging Overview](https://docs.oracle.com/en/java/javase/21/jpackage/packaging-overview.html)：在目标平台构建，对用户捆绑运行环境；Windows MSI/EXE 安装器需要 WiX，本次 `--type app-image` 无需 WiX。
- [JDK 21 jpackage](https://docs.oracle.com/en/java/javase/21/docs/specs/man/jpackage.html)：使用 `--runtime-image`、`--input`、`--main-jar`、`--main-class` 和 Java 模块选项。
- [OpenJFX 文档](https://openjfx.io/openjfx-docs/) / [官方 JavaFX 下载](https://gluonhq.com/products/javafx/)：JavaFX 21 最低要求 JDK 17，本项目 Java 21 可用；选择 Windows x64 21.0.13 JMOD，与第二阶段编译版本相同。

实际工具链：Windows x64 build 26200.9711（25H2）、PowerShell 7、Eclipse Temurin 21.0.11+10 x64、jlink/jpackage 21.0.11、Maven Wrapper 3.9.9、OpenJFX 21.0.13。这是实测版本，不声称为最新补丁。

## 交付内容

| 文件 | 作用 |
| --- | --- |
| `scripts/build-windows-x64-desktop.ps1` | 固定输入哈希、完整共享库/桌面测试、依赖清单检查、jdeps → jlink → jpackage、许可证、版本/文件清单和 ZIP/SHA-256 |
| `openpulse-desktop/distribution/portable-inputs.json` | 候选版本、已验收分析器 ZIP 哈希、三个 OpenJFX JMOD 哈希、九个运行 JAR 的精确清单与许可来源 |
| `scripts/verify-windows-x64-desktop.ps1` | 新目录解压、ZIP/文件哈希、布局、依赖/许可/模块检查，包内 Java 执行真实分析、严格导入导出与 CLI 比较 |
| `scripts/qa/PortableReportCheck.java` | 维护者验收探针，仅在忽略的验收目录编译，不随包分发 |
| `scripts/qa/start-isolated-desktop.ps1` | 子进程环境隔离与进程树/DLL/TCP 采样，不更改系统环境或系统工具 |
| `docs/windows-x64-desktop.zh-CN.md` | 实际随包中文使用、校验、故障说明和许可入口 |

详细复现命令见 [桌面开发指南](../desktop-development.zh-CN.md#维护者制作-windows-x64-候选包)。Wrapper 始终从 `openpulse-platform` 调用，使用 `-f ..\pom.xml`，不依赖 MAVEN_BASEDIR。打包本身只构建 desktop/client，不启动 Platform、数据库或 Docker。

运行目录：

```text
OpenPulse/
  OpenPulse.exe
  app/OpenPulse.cfg
  app/*.jar
  app/bin/openpulse-analyzer.exe
  runtime/bin/server/jvm.dll
  runtime/bin/javafx/{glass,prism_d3d,...}.dll
  runtime/legal/...
  licenses/...
  README.zh-CN.md / LICENSE / THIRD_PARTY_NOTICES.md
  VERSION.json / FILES.sha256
```

启动器用 `$APPDIR` 相对配置和包内 runtime；桌面沿用主 JAR 所在目录定位 `app/bin/openpulse-analyzer.exe`。包不含开发目录配置、系统 PATH 分析器、外置 JDK 路径、Spring/Platform/Web/JPA、数据库驱动、编译器、JUnit/AssertJ、JavaFX Web 或 media。

`jdeps` 检测根模块为 java.base、java.desktop、java.sql、javafx.controls，另加入 jdk.crypto.ec；连同传递模块共 13 个。java.sql/transaction.xa 是标准库类型依赖，不是数据库服务或驱动。

许可随包保留 Java/JavaFX 的完整链接模块 legal 树（包含 GPLv2、Classpath Exception 及 jpeg/mesa 原生声明）、Temurin NOTICE、JAR 内原始许可/NOTICE 与可用的解出副本、完整 Apache 2.0、SLF4J MIT、分析器 nlohmann-json/PicoSHA2 许可。实际清单由精确运行依赖生成，开发用依赖表与实际分发清单分开。

## 候选产物与输入可追溯性

产物位于忽略的 `openpulse-desktop/out/windows-x64/`：

- `OpenPulse-0.2.2-candidate.1-windows-x64.zip`：**44,972,567 字节**。
- 同名 `.zip.sha256`：**`3e22ecc037d82218c7e59f0df37046dbfec9585932dc39f341ba0f75acb695ec`**。
- 包内 `FILES.sha256` 覆盖 251 个文件（清单自身除外）；窗口验收后再次核对全部一致。

连续两次相同输入完整构建（本地日志 build4/build5）得到上述同一字节数和 SHA-256。固定了 JAR 输出时间戳、ZIP 条目排序和 1980-01-01 时间戳；不承诺换 JDK/机器后字节完全相同。ZIP、运行环境等分发产物没有进入源码提交；提交保留必要验收截图和机器证据。

分析器输入是任务 15 已验收的原始 `openpulse-analyzer-0.2.2-windows-x64.zip`，SHA-256 `3a10dbc5ca4c3fecef0ad5048eb96ef542a1dd748b83d9860dcaf11bc4bfd32d`。构建时重新固定核对，不重新编译或替换 C++ 代码。

OpenJFX 来自 [官方 21.0.13 Windows x64 JMOD ZIP](https://download2.gluonhq.com/openjfx/21.0.13/openjfx-21.0.13_windows-x64_bin-jmods.zip)。本机下载极慢，因此按 ZIP 中央目录取得所需三个条目，解压时验证各条目 CRC，并固定各 JMOD 的 SHA-256；没有宣称下载/验证了整个上游 ZIP。维护者通常完整下载并解压即可，打包脚本对这三个输入作相同哈希校验，不依赖本机的下载办法。

`VERSION.json` 如实记录打包时主线基点 `f80f917...`、packagingWorktreeDirty=true、打包脚本哈希、JDK release、JMOD/分析器哈希、运行模块和依赖。候选是在第三阶段本地提交前生成的工作区产物，不冒充干净 Release 提交构建。

## 自动化结果

| 检查 | 本轮结果 |
| --- | --- |
| 共享客户端 | 90 通过，0 失败/错误/跳过；包括包中真实 C++ 进程 |
| 桌面模块 | 39 通过，0 失败/错误/跳过；保留取消清理及 TIMEOUT/其他失败附带清理异常的回归 |
| 聚合构建 | BUILD SUCCESS；多次完整打包均重新执行上述测试 |
| 新解压包 | SHA、252 文件总数、251 文件清单哈希、实际依赖与许可、相对启动配置、13 个私有模块均通过 |
| 包内运行环境 | 清除外置 Java/JDK/Maven 配置、PATH 仅 System32、私有 TEMP，真实 Runner → Reader → 导出 → 严格重新打开通过 |
| 严格拒绝 | 带未知字段的 v2 报告被拒绝；错误 ZIP SHA 在解压前被拒绝 |
| CLI 事实比较 | 包内同版本 C++ 显式 `--protocol 2.0` 对比 Runner 导出及真实窗口导出；仅忽略 taskId/generatedAt，其他事实全部相同 |
| CLI 默认协议 | 在既有完整 fixture 上不传 --protocol，输出仍为 1.0 |
| 网络受限工具环境补充 | 全新 `.tools/隔离 05` 中自动包级验证通过；与真实窗口操作分开记录 |
| 脚本与 diff | 三个 PowerShell 脚本语法解析通过，git diff --check 通过 |

本轮不重跑 Platform 全量、MySQL/Testcontainers、C++ CTest 或联合契约脚本；不把历史结果充作本轮执行。最初受限环境使用默认临时目录的真实 C++ 写报告失败，改在正常 Windows 环境完整重跑通过；最终受限工具环境使用验包脚本的私有短 TEMP 同样通过。初稿旧 CLI 验包入口会引导 Platform 上下文，在受限环境因 loopback 被拒而失败，已从桌面打包入口移除，最终没有依赖这次失败测试得出通过结论。

本地日志：`.tools/task16-stage3-build4.log`、`build5.log`（均带完整 task16-stage3 前缀）、`.tools/task16-stage3-verify4.log`、`.tools/task16-stage3-restricted.log`、`.tools/task16-stage3-window2.log`。原始 JSON 留在忽略的 `.tools/第三阶段 全新解压 04/qa/`；必要的路径归一化机器证据见 [machine-evidence.json](../acceptance/task16-stage3/machine-evidence.json)。

## 真实窗口操作

使用 computer-use 技能控制本地构建程序，未用模拟慢进程代替分析器。首次资源管理器控制授权超时，重试成功后才执行双击并记录通过。

从全新 `.tools/第三阶段 全新解压 04/OpenPulse` 资源管理器**双击 OpenPulse.exe**，显示中文窗口并关闭。随后对同一候选以独立子进程环境完整验收：删除 Java/JDK/CLASSPATH/Maven/Gradle 等配置，PATH 仅 `C:\WINDOWS\System32`，TEMP 和 APPDATA/LOCALAPPDATA 指向新建空目录；不改系统安装和用户全局环境。正常双击与环境隔离启动是两个独立步骤，不声称资源管理器本身也经过环境隔离。

| 操作 | 实际结果 / 截图 |
| --- | --- |
| 新解压目录与双击 | 正常显示中文窗口；01、02 |
| 系统目录选择器选中文空格路径 | 选择 `中文 项目`，路径正确；03 |
| 点击真实分析 | SUCCESS / COMPLETE，2 文件、3 发现；04 |
| 发现、原始证据、修复建议 | 三条发现可选，许可证证据与建议显示；05 |
| 扫描限制 | 零限制时仍显示覆盖范围提示；06 |
| 导出 JSON | 原生保存对话框保存 `窗口 导出.json`；提示 JSON 已保存；07 |
| 重新打开 | 原生打开对话框选择导出报告，严格读入成功；08；并通过 CLI 事实比较 |
| 扫描中窗口响应 | 真实 C++ 扫描临时 2,147,481,600 字节短行 Java fixture 时切换页签正常；09、10 |
| 取消 | 等待清理后显示已取消，已观察到的真实分析器 PID 16804 消失，无 openpulse-analyzer-* 临时目录；11 |
| 取消后再分析 | 小项目再次 SUCCESS；12 |
| 扫描中关闭 | 再次真实扫描时关闭；观察到 PID 7852 扫描中存在，关闭后分析器与两个桌面进程均退出，退出码 0；13、14及机器证据 |

截图位于 [task16-stage3](../acceptance/task16-stage3/)。临时大文件只用于真实取消/关闭验收，验收后已删除，不随包也不提交。

第一次观察器仅看启动器父 PID，未采到 DLL/分析器，不能作为路径证明。修正为观察其 JVM 同名子进程后补测：父 PID 31264、JVM PID 36772；`java.dll`、`jvm.dll`、glass/prism DLL 全在候选 `runtime/`，分析器在候选 `app/bin/`，命令明确为 `--protocol 2.0`。关闭后没有观察到的桌面/分析器进程残留，私有 TEMP 仅留下 JVM 的 `hsperfdata_29021` 目录；分析任务目录已清理，不声称整个 TEMP 空无一物。

## 验证边界与剩余事项

- 本机保留了原有 JDK/编译工具/Docker，不卸载、不修改全局 PATH、不停止其他程序。子进程隔离和实际 DLL 路径证明候选使用包内 Java/JavaFX/分析器；没有服务器或数据库驱动，也没有为客户端启动数据库。不是“在完全未安装任何工具的新 Windows 机器”上的证明。
- 环境设置不可达代理，应用进程树 TCP 采样未发现连接；网络受限工具环境的自动分析/读写也通过。但是没有禁用操作系统网卡/防火墙，代理环境变量不是网络防火墙，TCP 采样可能漏掉短暂流量。未在真正断网的全新 VM 上完成全部双击流程；不宣称该场景已实测。
- 实测为本机 Windows x64，未验证其他 Windows 版本、ARM64、非简体系统代码页、所有 Unicode 字符/超长路径、只读或企业受控目录及签名/SmartScreen 分发体验。候选未签名，无安装器或自动更新。
- 已验收 CLI v1 在中文路径仍有既有 invalid UTF-8 失败；默认版本检查采用既有 ASCII 路径 fixture。桌面中文路径走显式 v2，已通过。本阶段不修复/改变 v1 默认协议；不将 v2 的中文验收扩大成 v1 支持声明。
- 不增加 AI、网站、登录、历史数据库、分析规则或公开发布。下一步是主线复审本阶段提交与候选，合并及公开发布另行授权。
