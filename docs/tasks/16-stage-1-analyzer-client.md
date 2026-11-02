# 任务 16 第一阶段：共享分析客户端

日期：2026-11-02。分支：`codex/desktop-local-analysis-ui`。基于 `main` 的 `e6da03e`。第一阶段后来已通过 PR #35 合并，合并提交为 `d86a81d`；下文保留当时的本地实施与复审记录，最新阶段进度见 [任务 16](16-java-desktop-analysis-ui.md)。

## 开工核对

已阅读 README、CONTRIBUTING、架构、路线图、任务 15 和任务 16，并核对协议、Schema、Reader、进程实现与分发脚本。GitHub PR #34 已合并，合并提交正是本次基线；任务 15 原文中的“未合并”描述其当时的本地验收状态。合并不等于 0.2.2 已公开发布。本次没有发布操作。

工作区原有未跟踪的课程 Word 文件未纳入本任务。

## 模块与名词

| 模块 | 职责 | 依赖方向 |
| --- | --- | --- |
| `openpulse-analyzer` | C++ 扫描事实和规则 | 不依赖 Java |
| `openpulse-analyzer-client` | 参数、进程执行、严格报告读取和校验 | Java 标准库、Jackson、networknt Schema validator |
| `openpulse-platform` | Spring Boot、GitHub、数据库及配置适配 | 依赖共享客户端 |
| `openpulse-desktop`（后续阶段） | JavaFX 窗口、后台应用服务、报告导入导出 | 依赖共享客户端 |

- DTO：承载协议数据的 Java 对象，例如 `AnalyzerReportV2`，不负责显示窗口。
- Reader：把 JSON 读取成 DTO 的入口；不可信输入必须先通过它。
- Schema：检查 JSON 的字段、类型、枚举和格式。
- 语义校验：检查跨字段关系、路径、排序、规则目录及 findingId 等约束。
- 配置适配：Platform 从 Spring 配置得到参数，再构造共享库所需的普通 Java 对象。
- Bean：由 Spring 管理的对象。共享类本身不带 Spring 注解，Platform 用 `@Bean` 注册它。
- Maven 聚合构建：一个入口按依赖顺序构建多个模块。`-pl` 选择模块，`-am` 同时构建其依赖。

## 本次实现

迁移唯一一份 Reader、v1/v2 DTO、Schema/语义校验器、进程 Runner、命令工厂、参数与错误类型，保留 Java 包名。正式 Schema 仍只维护在 `docs/protocol`，由共享库打包成类路径资源；Platform 不再重复打包。

Spring 注解移到 Platform 配置适配层。`AnalyzerPlatformProperties` 保持配置前缀和严格参数校验，`AnalyzerClientConfiguration` 装配共享库。命令工厂公开构造器，桌面端可以直接用 `new` 创建。

Reader 和 Runner 的原断言迁入共享库，使用普通构造器运行；原 Platform 配置绑定、API、快照和持久化测试保留。配置测试明确加载真实 `OpenPulseApplication`，避免内部测试配置代替应用配置。

进程超时、线程中断、子进程终止、诊断尾部、临时目录清理和错误分类沿用原实现。v1 默认值、严格 v2、16 MiB 上限、协议字段、C++ 规则均未改变。

## 学习顺序

先看 `AnalyzerProcessProperties`：它只是普通 Java 参数对象。然后看 `ConfiguredAnalyzerCommandFactory.create()`：返回参数列表，每个路径单独作为一个参数传递给程序。接着看 `AnalyzerProcessRunner.analyze()`：校验目录、启动程序、等待、读取报告，最后清理。再看 `AnalyzerReportReader.read()`：按精确协议版本分派，v2 经过 Schema 和语义校验。最后看 Platform 的 `AnalyzerClientConfiguration`：解释为什么同一套代码还能被 Spring 使用。

可对照 `AnalyzerClientRealProcessTest`：它不用 Spring，创建中文空格多模块目录，通过 Runner 获得真实 C++ 报告，再与直接 CLI 输出对比；只忽略 taskId/generatedAt。这个测试证明客户端可以独立运行，不表示桌面窗口已经完成。

## 可复现命令

使用 Java 21，先从仓库根目录进入 `openpulse-platform`，下面所有验证命令均在该目录执行。Wrapper 从当前目录找到 `.mvn`，无需设置 `MAVEN_BASEDIR`；`-f` 指向上一层的聚合 POM 或共享库 POM。Maven Wrapper 固定为 3.9.9；本机 Java 为 Temurin 21.0.11。直接执行 `java` 前先确认 PATH，避免旧 Java 8 入口；Maven 使用 `JAVA_HOME`。

```powershell
# 从仓库根目录进入 Wrapper 所在目录（只执行一次）
Set-Location .\openpulse-platform
# 聚合单元回归
.\mvnw.cmd -f ..\pom.xml clean test
# 原平台等价完整验证（需 Docker/MySQL Testcontainers）
.\mvnw.cmd -f ..\pom.xml -pl openpulse-platform -am clean verify
# 共享库独立单元验证（不提供 exe 时，真实进程用例按设计跳过）
.\mvnw.cmd -f ..\openpulse-analyzer-client\pom.xml clean test
# 共享库独立验证，加上已验收分析器的绝对路径才能执行真实进程用例
.\mvnw.cmd -f ..\openpulse-analyzer-client\pom.xml test "-Dopenpulse.analyzer.test.executable=C:\你的分析器目录\openpulse-analyzer.exe"
# 运行依赖检查
.\mvnw.cmd -f ..\openpulse-analyzer-client\pom.xml dependency:tree -Dscope=runtime
# 原有 C++ / Java 联合契约与包级入口已适配聚合构建
..\scripts\verify-analyzer-report-v2-contract.ps1
..\scripts\verify-windows-x64-package.ps1 -PackagePath ..\openpulse-analyzer\out\windows-x64\openpulse-analyzer-0.2.2-windows-x64.zip -JavaHome $env:JAVA_HOME
```

共享库固定 networknt 3.0.6、Jackson 3.1.4、JUnit 6.0.3、AssertJ 3.27.7，编译插件 3.15.0、测试插件 3.5.6；与迁移前 Platform 使用版本对齐。JavaFX 在本阶段尚未引入，第二阶段再核对 JavaFX 21 系列维护版本并固定。

## 首次实施验收结果

- 迁移前：286 项，0 失败、0 错误，18 项契约测试因未提供真实报告跳过。
- 迁移后原回归：共享库 89 项 + Platform 197 项，合计仍为 286 项，0 失败、0 错误；Platform 的 18 项跳过随后由联合契约入口单独执行。
- 独立共享库加真实进程用例：90 项，0 失败、0 错误、0 跳过；中文空格路径、多模块构建文件和测试识别、与直接 CLI 的内容比较通过。
- 完整 CTest 20/20；C01-C09 为 9/9，N01-N10 为 10/10 按预期拒绝；四份 golden 确定性哈希通过。SUCCESS 是真实 CLI 覆盖；PARTIAL_SUCCESS/FAILED 是生产 builder 场景覆盖，不伪称真实文件系统触发。
- 运行依赖树只有 JSON/Schema 相关库和日志 API，没有 Spring、Web、JPA、数据库驱动。
- 包级验收通过：既有 0.2.2 候选 ZIP 经解压运行、平台共享 Reader/快照检查，SHA-256 仍为 `3a10dbc5ca4c3fecef0ad5048eb96ef542a1dd748b83d9860dcaf11bc4bfd32d`。本次没有重新发布候选包。
- 最终带真实分析器路径的 `clean verify` 成功：共享库 90 项，Platform 197 项（18 项契约用例在普通入口跳过，另由联合脚本执行），0 失败、0 错误；MySQL/Testcontainers 8/8，0 跳过；两份 Java JAR 均成功构建。
- Docker 最初因遗留的 `sailor-ingest.sock`、`engine.sock` 无法移除而启动失败，导致第一次 `verify` 的 MySQL 环境错误。确认目录只含零字节重解析点、关闭 Docker 进程后，将两个临时目录改名为带时间戳的备份，恢复后服务端返回 29.7.2，完整重跑通过。未恢复出厂设置、未删除镜像或容器数据；备份保留在原目录旁。
- `git diff --check` 和暂存区检查通过，提交仅含共享库抽取、Platform 配置适配、必要构建/验收入口及相关文档。

## 文档入口修正与复审（2026-11-02）

原文错误地要求从仓库根目录调用子目录 Wrapper。该 Wrapper 从当前目录向父目录查找 `.mvn`，仓库根目录没有该配置，不能直接照原文运行。本次只修改 README 和本阶段记录：先进入 `openpulse-platform`，使用 `-f ..\pom.xml` 或 `-f ..\openpulse-analyzer-client\pom.xml`；脚本及包路径统一从该目录经 `..\` 定位。业务代码、构建配置与脚本实现未改动。

主线复审反馈：共享客户端 90 个通过；Platform 179 个通过、18 个契约用例跳过；MySQL 8 个通过；BUILD SUCCESS。主线本轮未重跑联合契约脚本。

本地按修正后的命令逐条验证，工作目录均为 `openpulse-platform`，启动前移除进程环境中的 `MAVEN_BASEDIR`，未为 Wrapper 设置额外基目录：

- 聚合 `clean test` 和 `-pl openpulse-platform -am clean verify` 均 BUILD SUCCESS。共享库默认 89 个通过、1 个真实进程用例跳过；Platform 179 个通过、18 个契约用例跳过；MySQL 8 个通过、0 跳过。
- 独立共享库 `clean test`：89 个通过、1 个跳过；另以本机已验收 exe 的实际绝对路径替换示例占位路径，独立 `test` 为 90 个通过、0 跳过。
- 独立共享库 `dependency:tree -Dscope=runtime` 成功，未引入 Spring、Web、JPA 或数据库驱动。
- 为实际核对脚本相对路径，本地另行重跑 `..\scripts\verify-analyzer-report-v2-contract.ps1`：完整 CTest 20/20，C01-C09 为 9/9，N01-N10 为 10/10 按预期拒绝，四份确定性哈希通过。这是本地文档修正复验，区别于上面的主线反馈。
- 从同一目录执行修正后的包级命令通过，候选 ZIP 路径正确解析，SHA-256 仍为 `3a10dbc5ca4c3fecef0ad5048eb96ef542a1dd748b83d9860dcaf11bc4bfd32d`。
- 复审差异仅 README 和本阶段记录；检查 diff 后 amend 到第一阶段原提交，不新增阶段提交，不开始桌面界面。

本地详细日志位于忽略目录 `.tools/task16-*.log`，本次入口复验使用 `.tools/task16-docfix-*.log`，不随提交分发。本记录结束时完成的是任务 16 第一阶段，当时第二、三阶段尚未实施，也未 push、创建 PR、合并或发布；后续 PR #35 合并及新阶段进度以上方链接为准。
