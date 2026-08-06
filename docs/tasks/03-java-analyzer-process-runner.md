# Java 第三步：安全启动 C++ 分析器

## 任务定位

- 负责人：Java 负责人
- 所在分支：`feat/platform-analyzer-runner`
- 所属阶段：P1 本地最小闭环
- 前置任务：C++ CLI 假分析器和 Java 报告 Reader 已合并到 `main`

本任务只建立 Java 调用本地 C++ 命令行程序的可靠边界。Java 接收一个已经存在的本地仓库目录，启动分析器，处理超时和退出码，读取生成的 JSON 报告，并清理自己创建的临时文件。

暂时不下载 GitHub 仓库、不接数据库，也不新增 HTTP 接口。

## 为什么现在做进程调用

前两步已经分别证明：

1. Java Platform 可以启动和测试。
2. Java 可以按协议读取 C++ JSON 报告。
3. C++ CLI 可以接收 `--path` 和 `--output` 并生成协议 v1 报告。

现在需要把两端真正接起来。进程调用不是简单地执行一条命令，还必须考虑：

- 可执行程序不存在或无法启动
- 仓库路径包含空格
- C++ 返回非零退出码
- 进程运行超时
- 标准输出或错误输出塞满缓冲区后造成死锁
- 进程成功退出但没有生成报告
- 线程被中断
- 临时报告和日志没有清理

这些边界稳定以后，下一步才能安全地提供“创建本地分析任务”的应用用例或 HTTP 接口。

## 开始前阅读

- `README.md`
- `docs/00-glossary.md`
- `docs/02-analyzer-json-protocol.md`
- `docs/04-architecture.md` 中的 Java/C++ 边界
- `docs/06-roadmap.md` 中的 P1 完成标准
- `docs/tasks/02-java-analyzer-report-parser.md`
- `openpulse-analyzer/src/main.cpp`
- `openpulse-platform/src/main/java/io/github/lihanc940/openpulse/integration/analyzer/AnalyzerReportReader.java`

## 本步骤需要理解的名词

新对话在使用这些概念前，应先用简单语言解释：

- **进程（Process）**：操作系统中正在运行的程序实例。Java 后端和 C++ 分析器是两个独立进程。
- **子进程**：由当前 Java 进程启动的另一个进程，本任务中就是 C++ 分析器。
- **`ProcessBuilder`**：Java 标准库中用于安全组装参数并启动外部程序的工具。
- **Shell**：PowerShell、`cmd` 或 Bash 等命令解释器。本任务不经过 Shell 拼接命令，避免转义和注入问题。
- **标准输出（stdout）**：程序正常运行信息的输出通道。
- **标准错误（stderr）**：程序错误和诊断信息的输出通道。
- **管道缓冲区**：父子进程传输输出时使用的有限缓冲区；不及时消费可能让子进程永久阻塞。
- **退出码（Exit Code）**：进程结束时返回的整数，用于说明成功或失败原因。
- **超时（Timeout）**：允许一次分析执行的最长时间，超过后必须终止进程。
- **中断（Interrupt）**：上层要求当前线程停止等待的信号；捕获后必须恢复中断标记。
- **强制终止**：普通终止无效时，由操作系统立即结束进程的最后手段。
- **临时工作目录**：每次执行单独创建、结束后清理的目录，用于存放报告和受控诊断文件。
- **配置属性（Configuration Properties）**：把可执行程序路径和超时放在外部配置中，而不是写死在 Java 代码中。
- **命令注入**：把外部输入拼进 Shell 命令后，攻击者可能让系统执行额外命令的风险。
- **测试替身（Test Double）**：测试中代替真实 C++ 分析器、可控制成功失败行为的小程序。

## 职责边界

建议由以下对象协作：

```text
integration/analyzer/
  AnalyzerProcessRunner.java
  AnalyzerExecutionException.java
  AnalyzerExecutionFailure.java
  AnalyzerProcessProperties.java
  AnalyzerCommandFactory.java
```

测试目录建议新增：

```text
src/test/java/io/github/lihanc940/openpulse/integration/analyzer/
  AnalyzerProcessRunnerTest.java
  FakeAnalyzerMain.java
```

名称和文件数量可以小幅调整，但职责不能混在一个通用 `util` 类中：

- `AnalyzerProcessRunner`：编排一次执行、等待、失败处理、报告读取和清理。
- `AnalyzerCommandFactory`：只根据配置和路径生成参数列表，不启动进程。
- `AnalyzerProcessProperties`：保存可执行程序路径、超时等外部配置。
- `AnalyzerExecutionException`：向上层提供稳定的进程执行错误。
- `AnalyzerExecutionFailure`：区分启动失败、超时、退出码错误、报告缺失和中断等原因。
- `AnalyzerReportReader`：继续只负责读取和校验报告，不负责启动 C++。

不要让 Controller、未来的业务 Service 或测试代码到处直接使用 `new ProcessBuilder(...)`。

## 对外方法

Runner 对外提供一个含义清楚的方法，例如：

```java
AnalyzerReport analyze(Path repositoryPath)
```

调用方只提供待分析目录，不负责生成报告路径或清理临时文件。

Runner 的正常返回值是已经通过 `AnalyzerReportReader` 校验的 `AnalyzerReport`。失败时抛出 `AnalyzerExecutionException`。

## 配置要求

至少支持两项配置：

```yaml
openpulse:
  analyzer:
    executable: ${OPENPULSE_ANALYZER_EXECUTABLE:openpulse-analyzer}
    timeout: ${OPENPULSE_ANALYZER_TIMEOUT:30s}
```

要求：

1. 使用 Spring Boot `@ConfigurationProperties` 集中绑定配置。
2. 可执行程序不能为空。
3. 超时必须为正数，并使用 `Duration`，不要用含义不清楚的裸 `long`。
4. 不在仓库中提交个人电脑的绝对路径。
5. 本地需要绝对路径时，通过环境变量或被忽略的 `application-local.yml` 设置。
6. 不自动下载分析器，也不把 `.exe` 提交进 Git。

Windows 本地配置示例只在当前终端使用：

```powershell
$env:OPENPULSE_ANALYZER_EXECUTABLE = "C:\path\to\openpulse-analyzer.exe"
$env:OPENPULSE_ANALYZER_TIMEOUT = "30s"
```

不要把示例路径原样写进项目配置。

## 命令构造要求

最终启动的参数应等价于：

```text
openpulse-analyzer --path <repositoryPath> --output <reportPath>
```

必须遵守：

1. 使用参数列表构造 `ProcessBuilder`。
2. 不拼接一个完整命令字符串。
3. 不使用 `cmd /c`、PowerShell 或 Bash 包裹真实分析命令。
4. 路径包含空格时也必须正确工作。
5. `repositoryPath` 在启动前必须存在且是目录。
6. 输出路径必须位于 Runner 自己创建的临时工作目录中。

## 临时工作目录

每次分析创建独立目录，例如：

```text
<system-temp>/openpulse-analyzer-随机值/
  report.json
  stdout.log
  stderr.log
```

Runner 只允许删除自己创建的这个临时目录，绝不能递归删除：

- 用户传入的仓库目录
- 仓库目录的父目录
- 系统临时目录根目录
- 根据未经验证的字符串计算出的任意路径

清理逻辑放在 `finally` 或等价的资源生命周期结构中，成功和失败都执行。

## 输出处理要求

stdout 和 stderr 必须被消费或重定向，否则子进程可能因为缓冲区写满而卡死。

本任务推荐：

1. 使用 `ProcessBuilder.redirectOutput(...)` 和 `redirectError(...)` 写入本次临时工作目录。
2. 进程结束后只读取有限长度的诊断内容，例如最后 8 KiB。
3. 不把完整输出直接拼进异常、HTTP 响应或日志。
4. 不使用 `inheritIO()`，避免服务端输出依赖启动终端。

若选择异步消费管道，也必须证明 stdout 和 stderr 同时被消费、任务结束后线程能够退出，并有自动化测试覆盖；初学阶段优先采用临时文件重定向方案。

## 超时与中断

等待进程时使用带超时的 API，例如：

```java
process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)
```

超时后：

1. 先尝试普通终止。
2. 给进程一个很短的退出宽限时间。
3. 仍未退出时强制终止。
4. 必要时终止由分析器创建的后代进程，避免残留后台进程。
5. 抛出明确的超时异常。
6. 在异常路径中仍然清理临时文件。

捕获 `InterruptedException` 后必须：

```java
Thread.currentThread().interrupt();
```

然后终止子进程并把错误包装为 `AnalyzerExecutionException`。不要吞掉中断后继续假装成功。

## 退出码映射

按协议映射：

```text
0  分析成功
1  参数错误
2  路径不存在
3  扫描过程失败
4  JSON 输出失败
```

要求：

- 退出码 `0` 才进入报告读取。
- `1` 到 `4` 提供不同的稳定失败原因或清楚消息。
- 未知退出码也必须有明确的兜底错误。
- Java 传入的参数由自己生成，因此退出码 `1` 通常表示 Java/C++ 集成契约错误。
- 错误信息可以包含退出码和有限诊断摘要，但不能包含 Token、完整源码或无限输出。

## 报告读取与清理

退出码为 `0` 后仍然必须检查：

1. 报告文件存在。
2. 报告路径是普通文件。
3. 调用现有 `AnalyzerReportReader` 解析和校验。

不要在 Runner 中复制 Jackson 解析逻辑。即使报告解析失败，也必须在异常传播前清理临时工作目录。

## 自动化测试策略

Maven 测试不能依赖：

- 开发者已经编译 C++
- Visual Studio 或 CMake 已安装
- 某个固定盘符或个人路径
- Windows 专属 `.cmd` 脚本
- GitHub 网络

推荐在测试源码中创建一个很小的 `FakeAnalyzerMain`。测试使用当前运行 Maven 的 JDK 启动这个 Java 测试程序，让它模拟 C++ CLI：

- 成功时把合法协议样例写到 `--output`
- 指定场景时返回退出码 `1` 到 `4`
- 超时场景中睡眠超过配置时间
- 报告缺失场景返回 `0` 但不写文件
- 输出场景向 stdout 和 stderr 写诊断信息

通过可替换的 `AnalyzerCommandFactory` 给测试提供 Java 假进程命令；生产实现仍然只生成真实 C++ 命令。这样测试可以跨 Windows 和 Linux 运行，又不会在业务代码中加入“测试模式”。

至少完成以下测试：

### 1. 成功执行并解析报告

- 仓库路径中包含空格
- 子进程退出码为 `0`
- 返回的 `AnalyzerReport` 字段正确
- 临时目录最终被清理

### 2. 已知非零退出码

- 至少验证退出码 `3`
- 异常包含稳定失败类型和退出码
- 不尝试把缺失报告当作成功读取

### 3. 未知退出码

- 假进程返回协议未定义的退出码
- Java 明确报告未知退出码

### 4. 超时

- 假进程睡眠超过很短的测试超时
- Runner 能终止进程并及时返回
- 抛出超时失败类型

### 5. 成功退出但报告缺失

- 假进程返回 `0` 但不生成 JSON
- Java 抛出报告缺失错误

### 6. 启动失败

- 配置不存在的可执行程序
- Java 抛出启动失败并保留原始 `IOException` 作为 cause

### 7. 非目录输入

- 传入不存在路径或普通文件
- 启动进程前即拒绝

如果实现了 stdout/stderr 诊断摘要，还要验证超长输出会被截断，不会无限进入异常消息。

## 真实 C++ 冒烟验证

自动化测试完成后，需要使用真实 `openpulse-analyzer` 做一次手工冒烟验证。

当前工作区没有已编译的 `openpulse-analyzer.exe`，并且 `.gitignore` 明确禁止提交可执行文件。可由 C++ 负责人提供本机编译路径，或在安装 C++ 工具链的电脑上执行。

验证至少记录：

```text
实际可执行程序路径：
实际测试仓库路径：
Java 调用是否成功：
退出码：
报告 protocolVersion：
报告 taskId：
临时目录是否清理：
```

真实二进制不可用时，不要伪造结果；在回主线验收报告中明确写“自动化测试通过，真实 C++ 冒烟验证待 C++ 负责人配合”。

## 推荐实施顺序

### 1. 确认状态

```powershell
git status
git branch --show-current
.\openpulse-platform\mvnw.cmd -version
```

当前分支必须是：

```text
feat/platform-analyzer-runner
```

### 2. 阅读协议与现有实现

先解释进程、退出码、stdout、stderr、超时和中断，再画出：

```text
调用方 -> Runner -> C++ 进程 -> report.json -> Reader -> AnalyzerReport
```

### 3. 建立配置和命令工厂

先让配置绑定测试或小范围测试通过，确认命令是参数列表而不是拼接字符串。

### 4. 建立异常模型

定义稳定失败类型，先决定每种失败如何向上层表达，再实现 Runner。

### 5. 实现成功路径

创建临时目录、启动假进程、等待退出、读取报告、清理目录。

### 6. 逐个补充失败路径

按非零退出码、报告缺失、启动失败、超时、中断的顺序推进。一次只增加一种行为和对应测试。

### 7. 完整验证

在仓库根目录执行：

```powershell
.\openpulse-platform\mvnw.cmd clean verify
```

任务 1 和任务 2 的测试必须继续通过。

### 8. 检查并提交

```powershell
git status
git diff
```

只暂存本任务实现文件：

```powershell
git add openpulse-platform
git diff --cached
git commit -m "feat(platform): run analyzer process safely"
```

先不 push，不创建 PR，回主线对话验收。

## 明确不做

本任务禁止提前加入：

- GitHub URL 解析或仓库下载
- 数据库、实体表和迁移脚本
- 新的 HTTP 接口
- 异步任务队列或线程池调度系统
- 健康评分
- AI 调用
- Docker 和 GitHub Actions
- 用户、JWT 和权限
- 自动安装或自动更新 C++ 分析器
- 把 C++ 可执行文件提交进 Git
- 通过 Shell 拼接命令
- 把进程调用做成全项目通用工具类
- 为测试在生产代码中增加特殊分支

## 完成标准

以下条件必须全部满足：

- 可执行程序路径和超时由集中配置管理
- 命令使用参数列表构造，路径含空格时正确
- 本地目录校验在启动进程前完成
- stdout 和 stderr 不会造成进程死锁
- 退出码 `0` 到 `4` 和未知退出码有明确处理
- 超时和中断会终止子进程
- 成功后复用 `AnalyzerReportReader`
- 成功和失败路径都会清理 Runner 创建的临时目录
- 自动化测试不依赖 C++ 工具链、个人路径或网络
- `.\openpulse-platform\mvnw.cmd clean verify` 通过
- 原有 6 个测试继续通过
- 没有新增无关依赖
- 没有构建产物、可执行文件、密钥或个人路径进入 Git
- 已创建范围单一的本地实现 commit
- 尚未 push
- 真实 C++ 冒烟验证已完成，或明确记录待 C++ 负责人配合

## 回主线对话时提交的信息

```text
分支：
commit：
Runner 对外方法：
配置项：
失败类型：
自动化测试场景：
全部测试数量和结果：
真实 C++ 冒烟验证：
临时目录清理验证：
我理解的新名词：
遇到的问题：
```

## 新对话启动提示词

在相同项目工作区创建新对话后发送：

```text
请先阅读 README.md、docs/00-glossary.md、docs/02-analyzer-json-protocol.md、
docs/04-architecture.md、docs/06-roadmap.md、
docs/tasks/02-java-analyzer-report-parser.md、
docs/tasks/03-java-analyzer-process-runner.md、
openpulse-analyzer/src/main.cpp 和现有 AnalyzerReportReader。

我是 Java 初学者，请严格完成“Java 第三步：安全启动 C++ 分析器”。
使用当前 feat/platform-analyzer-runner 分支。
每使用一个新名词前先用简单语言解释，再执行对应操作并验证。
一次只推进一个可验证的小步骤，等我理解后再继续。
不要加入任务单明确禁止的功能。
自动化测试不得依赖 C++ 工具链、个人路径、网络或 Windows 专属脚本。
完成配置、命令构造、Runner、失败处理、超时、清理和测试，
创建范围单一的本地实现 commit，但不要 push 或创建 PR。
真实 C++ 二进制不可用时如实记录，不要伪造冒烟验证结果。
最后按任务单格式生成回主线对话的验收报告。
```
