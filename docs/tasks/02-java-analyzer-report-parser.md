# Java 第二步：读取并校验 C++ JSON 报告

## 任务定位

- 负责人：Java 负责人
- 所在分支：`feat/platform-report-parser`
- 所属阶段：P1 本地最小闭环
- 前置任务：Java Platform 最小骨架已合并到 `main`

本任务只建立 Java 与 C++ 报告协议的读取边界。暂时不启动 C++ 进程、不接数据库，也不新增 HTTP 接口。

## 为什么先做解析器

Java 调用 C++ 后拿到的是一个 JSON 文件。继续开发之前，Java 必须能够：

1. 把 JSON 转换为有类型的 Java 对象。
2. 判断报告是否符合协议版本。
3. 对缺失文件、坏 JSON 和不支持的版本给出明确错误。
4. 用固定样例测试，防止双方修改字段时悄悄破坏兼容性。

这一层稳定以后，下一步才能安全地使用 `ProcessBuilder` 启动 C++。

## 开始前阅读

- `docs/02-analyzer-json-protocol.md`
- `docs/examples/analyzer-report-v1.sample.json`
- `docs/04-architecture.md` 中的 Java/C++ 边界

## 本步骤需要理解的名词

新对话在使用这些概念前，应先用简单语言解释：

- **协议（Protocol）**：两个独立程序共同遵守的数据格式和行为约定。
- **契约（Contract）**：协议中不能由一方随意改变的字段、类型和含义。
- **序列化**：把 Java 对象转换为 JSON。
- **反序列化**：把 JSON 转换为 Java 对象。
- **Jackson**：Spring Boot 默认使用的 JSON 处理工具。
- **DTO**：用于承接外部数据格式的对象。
- **领域对象**：表达系统内部业务含义的对象；本任务暂不把协议 DTO 当作最终领域模型。
- **测试夹具（Fixture）**：供自动化测试重复使用的固定输入文件。
- **边界校验**：外部数据进入系统时进行的最低合法性检查。
- **异常包装**：保留原始错误原因，同时向上层提供稳定、可理解的错误类型。

## 包结构

建议新增：

```text
openpulse-platform/
  src/main/java/io/github/lihanc940/openpulse/
    integration/
      analyzer/
        AnalyzerReportReader.java
        AnalyzerReportReadException.java
        model/
          AnalyzerReport.java
          AnalyzerStatus.java
          RiskLevel.java
  src/test/
    java/io/github/lihanc940/openpulse/
      integration/analyzer/
        AnalyzerReportReaderTest.java
    resources/contracts/
      analyzer-report-v1.sample.json
      analyzer-report-malformed.json
      analyzer-report-unsupported-version.json
```

具体文件数量可以在不模糊边界的前提下微调，但不要建立通用 `util` 包。

## 模型要求

`AnalyzerReport` 应覆盖协议 v1 的顶层字段：

```text
protocolVersion
taskId
status
repository
summary
languages
structure
quality
risks
dependencies
generatedAt
```

建议使用 Java `record` 表达只承载数据的结构，并用嵌套 record 表达：

- `Repository`
- `Summary`
- `Language`
- `Structure`
- `Quality`
- `Risk`
- `Dependencies`

类型要求：

- `generatedAt` 使用 `OffsetDateTime`，保留报告中的时区偏移。
- `status` 使用 `AnalyzerStatus` 枚举。
- 风险 `level` 使用 `RiskLevel` 枚举。
- `languages`、`risks`、`buildFiles` 使用有元素类型的 `List`。
- 风险 `evidence` 暂用 `Map<String, Object>`，因为不同规则的证据结构不同。
- `dependencies.nodes` 和 `dependencies.edges` 的子结构尚未正式定义，暂用 `List<Map<String, Object>>`。

不要为了省事把整份报告解析成一个 `Map` 或到处传递 `JsonNode`。

## Reader 要求

`AnalyzerReportReader` 对外提供一个含义清楚的方法，例如：

```java
AnalyzerReport read(Path reportPath)
```

它负责：

1. 检查路径存在且是普通文件。
2. 使用项目统一的 Jackson mapper 读取 JSON。
3. 转换为 `AnalyzerReport`。
4. 校验 `protocolVersion` 必须为 `1.0`。
5. 校验 `taskId` 不能为空。
6. 校验必要的对象和列表不为 `null`。
7. 失败时抛出 `AnalyzerReportReadException`。

不要在业务代码中直接 `new ObjectMapper()`。优先使用 Spring Boot 管理和配置的 mapper，使 Java 时间类型与未来的全局 JSON 配置保持一致。

异常信息应该说明：

- 哪个文件读取失败
- 是文件不存在、JSON 格式错误，还是协议版本不支持

异常信息不能包含 Token、完整源码或其他敏感内容。

## 测试夹具

把：

```text
docs/examples/analyzer-report-v1.sample.json
```

复制到测试资源：

```text
src/test/resources/contracts/analyzer-report-v1.sample.json
```

复制的原因是：模块测试必须能独立运行，不能依赖仓库外层的相对目录。

测试夹具与协议示例出现字段变更时必须同步更新，后续 CI 会负责发现不同步造成的解析失败。

## 自动化测试

至少完成四类测试。

### 1. 正确报告

验证：

- `protocolVersion` 是 `1.0`
- `taskId` 是 `task_demo_001`
- `status` 是 `SUCCESS`
- 总文件数是 `12`
- 语言列表有 `2` 项
- 风险列表有 `2` 项
- 第一条风险规则是 `MISSING_LICENSE`
- `generatedAt` 能正确解析时区
- `evidence` 中的字段能够读取

### 2. 文件不存在

传入不存在的路径，应抛出 `AnalyzerReportReadException`，错误信息能说明文件不存在。

### 3. JSON 格式错误

使用故意损坏的测试 JSON，应抛出 `AnalyzerReportReadException`，并保留原始解析异常作为 `cause`。

### 4. 协议版本不支持

使用 `protocolVersion: "2.0"` 的测试 JSON，应明确报告“不支持的协议版本”，不能静默接受。

## 推荐实施顺序

### 1. 确认状态

```powershell
git status
git branch --show-current
.\openpulse-platform\mvnw.cmd -version
```

当前分支必须是：

```text
feat/platform-report-parser
```

### 2. 阅读协议和样例

逐个理解字段，先讨论 Java 类型映射，再创建 record。不要看到 JSON 就立即写代码。

### 3. 建立模型

先建立 `AnalyzerReport` 及枚举，编译确认类型正确。

### 4. 建立 Reader 和异常

实现文件读取、Jackson 反序列化和最小协议校验。

### 5. 建立测试夹具和测试

每增加一种失败场景，都先说明期望，再运行测试。

### 6. 完整验证

在仓库根目录执行：

```powershell
.\openpulse-platform\mvnw.cmd clean verify
```

原有健康检查测试也必须继续通过。

### 7. 检查并提交

```powershell
git status
git diff
```

只暂存本任务文件：

```powershell
git add openpulse-platform
git diff --cached
git commit -m "feat(platform): parse analyzer report contract"
```

先不 push，不创建 PR，回主线对话验收。

## 明确不做

本任务禁止提前加入：

- `ProcessBuilder` 和 C++ 进程调用
- GitHub 仓库下载
- 数据库和实体表
- 健康评分
- AI 调用
- 新的 HTTP 接口
- Docker
- JWT、用户和权限
- 把协议 DTO 直接设计成数据库实体
- 通用 JSON 工具类

## 完成标准

以下条件必须全部满足：

- 协议 v1 字段有明确 Java 类型
- 正确报告可以完整解析
- 文件不存在、坏 JSON、协议版本不支持都有测试
- `.\openpulse-platform\mvnw.cmd clean verify` 通过
- 原有健康检查测试继续通过
- 没有新增无关依赖
- 没有构建产物、密钥或个人路径进入 Git
- 已创建范围单一的本地 commit
- 尚未 push

## 回主线对话时提交的信息

```text
分支：
commit：
新增模型：
Reader 对外方法：
成功场景测试：
失败场景测试：
全部测试数量和结果：
我理解的新名词：
遇到的问题：
```

## 新对话启动提示词

在相同项目工作区创建新对话后发送：

```text
请先阅读 README.md、docs/02-analyzer-json-protocol.md、
docs/04-architecture.md、docs/examples/analyzer-report-v1.sample.json
和 docs/tasks/02-java-analyzer-report-parser.md。

我是 Java 初学者，请严格完成“Java 第二步：读取并校验 C++ JSON 报告”。
使用当前 feat/platform-report-parser 分支。
每使用一个新名词前先用简单语言解释，再执行对应操作并验证。
一次只推进一个可验证的小步骤，等我理解后再继续。
不要加入任务单明确禁止的功能。
完成模型、Reader、失败处理、测试和本地 commit，但不要 push 或创建 PR。
最后按任务单格式生成回主线对话的验收报告。
```
