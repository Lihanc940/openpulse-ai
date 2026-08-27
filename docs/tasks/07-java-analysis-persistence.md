# Java 第七步：任务和报告持久化

## 任务定位

- 负责人：Java 负责人
- 建议分支：`feat/platform-analysis-persistence`
- 所属阶段：P2 真实 GitHub 仓库分析
- 对应计划：Week 2 第 4 天
- 前置任务：GitHub URL 与元数据查询、公开仓库受控下载已经合并到 `main`

本任务为后续真实 GitHub 分析流程建立可靠的数据库基础：保存仓库项目、一次分析任务的生命周期，以及经过校验和脱敏后的 analyzer 报告快照。

本步骤只完成模型、数据库迁移、Repository 边界和自动化测试，不新增 HTTP 接口，不把 GitHub 下载与 C++ analyzer 串成完整流程。这样可以先验证“什么数据值得保存、状态怎样变化、数据库约束能否保护数据”，再进入下一步业务编排。

## 为什么要单独做这一步

持久化不只是给 Java 类加几个数据库注解。如果模型和约束没有先设计清楚，后面的 GitHub 分析流程很容易出现：

- 同一个 GitHub 仓库因为大小写或 URL 写法不同被重复创建。
- 任务从失败状态又回到运行状态，生命周期失去可信度。
- Java 自己的任务 ID 与 C++ 报告中的 task ID 被错误地当成同一个概念。
- 临时目录绝对路径、Token、进程输出或异常堆栈被长期写进数据库。
- 测试只在 H2 上通过，部署到 MySQL 后才发现 SQL 或字段类型不兼容。
- Hibernate 自动改表，导致开发环境和生产环境的数据库结构不一致。

因此本任务把领域规则、数据库表、迁移脚本和适配器分开处理，并使用真实 MySQL 容器验证兼容性。

## 开始前阅读

- `README.md`
- `docs/00-glossary.md`
- `docs/01-mvp-v0.1.md`
- `docs/02-analyzer-json-protocol.md`
- `docs/04-architecture.md`
- `docs/06-roadmap.md`
- `docs/08-week-2-checklist.md`
- `docs/tasks/05-java-github-repository-metadata.md`
- `docs/tasks/06-java-github-repository-download.md`
- 现有 `GithubRepositoryMetadata`、`AnalyzerReport` 及其测试
- Spring Data JPA 官方文档：`https://docs.spring.io/spring-data/jpa/reference/`
- Flyway 官方文档：`https://documentation.red-gate.com/flyway`
- Testcontainers for Java 官方文档：`https://java.testcontainers.org/`

## 本步骤需要理解的名词

新对话在使用这些概念前，应先用简单语言解释：

- **持久化（Persistence）**：把内存中的数据写入数据库，使应用重启后仍能读取。
- **关系数据库**：用表、行、列和表之间的关系保存结构化数据；本项目生产目标使用 MySQL。
- **实体（Entity）**：具有唯一身份并会随时间变化的业务对象，例如一次分析任务。
- **领域模型（Domain Model）**：表达业务概念和规则的 Java 对象，不应被某一种数据库实现绑死。
- **JPA**：Java 的持久化规范，用对象映射数据库表。
- **Hibernate**：Spring Boot 常用的 JPA 实现，负责执行对象与表之间的映射。
- **Spring Data JPA**：在 JPA 之上提供 Repository 基础能力的 Spring 项目。
- **Repository**：面向业务代码的数据访问接口，让业务层不需要直接编写 SQL 或依赖 `JpaRepository`。
- **Adapter**：实现业务 Repository 接口，并把领域对象转换成 JPA 数据对象的代码。
- **数据库迁移（Migration）**：按版本保存数据库结构变化的 SQL 文件。
- **Flyway**：按顺序执行并记录数据库迁移的工具。
- **主键（Primary Key）**：数据库中唯一标识一行数据的字段。
- **外键（Foreign Key）**：让一张表的记录引用另一张表，并阻止无效关联。
- **唯一约束（Unique Constraint）**：保证某个值或字段组合不能重复。
- **索引（Index）**：帮助数据库更快查找数据的结构，但会增加写入和存储成本。
- **事务（Transaction）**：让一组数据库操作要么全部成功，要么全部回滚。
- **乐观锁（Optimistic Lock）**：用版本号发现并发修改冲突，避免后写入的人静默覆盖先写入的人。
- **Testcontainers**：在测试期间启动一次性的真实数据库容器，用来验证 MySQL 兼容性。
- **H2**：轻量测试数据库，但 SQL 行为不等同于 MySQL，不能作为本任务唯一的数据库验证依据。
- **JSON 快照**：把已经校验和脱敏的 analyzer 报告按固定 JSON 保存，保留协议原始信息供以后读取。

## 技术基线与依赖

本任务建议使用：

- Spring Data JPA。
- MySQL 作为生产数据库目标。
- Flyway 作为数据库结构的唯一迁移工具。
- Testcontainers MySQL 作为 Repository 和迁移集成测试环境。
- Hibernate 只验证映射，不自动创建或更新生产表。

按实际需要在 `pom.xml` 中增加最小依赖，例如 JPA、Flyway、MySQL Connector/J、Testcontainers JUnit 与 MySQL 模块。Testcontainers 的 MySQL 镜像必须固定明确版本，不使用 `latest`。

可以为了不影响与数据库无关的旧 Spring 上下文测试，在 test scope 使用 H2 或进一步切分测试上下文；但持久化功能的迁移、约束和 Repository 测试必须在 Testcontainers MySQL 上通过，不能只用 H2 证明兼容性。

本任务不新增 Docker Compose。运行 Testcontainers 时，本机需要已经启动 Docker Desktop；Docker 不可用时应如实记录，不能把未运行的 MySQL 集成测试写成已通过。

## 建议代码边界

继续采用模块化单体，并把领域接口与 JPA 实现分开。下面是建议方向，不要求为了完全匹配目录名而制造空类：

```text
openpulse-platform/src/main/java/io/github/lihanc940/openpulse/
  project/
    domain/
      Project.java
      ProjectRepositoryKey.java
    application/
      ProjectRepository.java
    infrastructure/persistence/
      ProjectJpaEntity.java
      SpringDataProjectJpaRepository.java
      JpaProjectRepository.java
  analysis/
    domain/
      AnalysisTask.java
      AnalysisTaskId.java
      AnalysisTaskStatus.java
      AnalysisFailureCode.java
    application/
      AnalysisTaskRepository.java
    infrastructure/persistence/
      AnalysisTaskJpaEntity.java
      SpringDataAnalysisTaskJpaRepository.java
      JpaAnalysisTaskRepository.java
  report/
    domain/
      AnalysisReportRecord.java
    application/
      AnalysisReportRepository.java
      AnalysisReportSnapshotFactory.java
    infrastructure/persistence/
      AnalysisReportJpaEntity.java
      SpringDataAnalysisReportJpaRepository.java
      JpaAnalysisReportRepository.java

openpulse-platform/src/main/resources/db/migration/
  V1__create_analysis_persistence.sql
```

职责建议：

- 领域对象：维护状态转换和不变量，不依赖 JPA、Spring MVC 或 GitHub HTTP DTO。
- application Repository：声明业务真正需要的保存和查询能力。
- JPA entity：只负责数据库字段、关联、索引和版本映射。
- Spring Data repository：提供基础 JPA 查询，仅在 infrastructure 包内使用。
- adapter：在领域对象和 JPA entity 之间转换，实现 application Repository。
- `AnalysisReportSnapshotFactory`：把已校验的 `AnalyzerReport` 转成可安全持久化的快照，删除临时路径等不应入库的数据。
- Flyway migration：创建表、约束和索引，是数据库结构的唯一来源。

不要直接给现有 `GithubRepositoryMetadata`、`AnalyzerReport` 或 GitHub 响应 DTO 添加 JPA 注解。它们分别承担集成契约或协议读取职责，不应变成数据库模型。

## 模型设计

### 1. Project

`Project` 表示一个已经确认存在的公开 GitHub 仓库。建议字段：

```text
id
repositoryKey
owner
name
fullName
canonicalUrl
description
defaultBranch
primaryLanguage
stars
forks
archived
createdAt
updatedAt
```

规则：

- `id` 使用数据库内部主键，不向业务调用方表达 GitHub 身份。
- `repositoryKey` 使用规范化后的 `owner/name` 小写形式，并建立唯一约束。
- `owner`、`name`、`fullName`、`canonicalUrl` 和 `defaultBranch` 不能为空。
- `description` 和 `primaryLanguage` 可以为空。
- `stars` 和 `forks` 不得为负数。
- 同一仓库再次查询时应更新可变化的元数据，而不是创建重复项目。
- `createdAt` 和 `updatedAt` 使用 UTC，并由应用或统一审计机制明确维护。

`repositoryKey` 用于去重，不代表需要把用户输入强制改成小写展示。展示字段可以保留 GitHub 返回的大小写。

### 2. AnalysisTask

`AnalysisTask` 表示一次独立分析。建议字段：

```text
id
taskId
projectId
status
failureCode
failureMessage
createdAt
startedAt
completedAt
durationMs
version
```

规则：

- `id` 是数据库内部主键。
- `taskId` 是平台创建并对外使用的 UUID，必须唯一。
- `projectId` 通过外键关联 `Project`。
- `status` 至少包含 `PENDING`、`RUNNING`、`SUCCESS`、`FAILED`。
- 枚举以可读字符串保存，不使用容易因枚举顺序变化而损坏数据的 ordinal。
- 数据库字段使用 `VARCHAR`，不要使用 MySQL 原生 enum，便于以后增加状态或失败类型。
- 合法转换只有 `PENDING -> RUNNING -> SUCCESS/FAILED`。
- 已经结束的任务不能再次运行，状态也不能倒退。
- 进入 `RUNNING` 时必须记录 `startedAt`。
- `SUCCESS` 不得带失败码；`FAILED` 必须带稳定 `failureCode`。
- `failureMessage` 只保存有长度上限的安全说明，不保存底层异常原文。
- 结束状态必须记录 `completedAt`，`durationMs` 不得为负数。
- 建议使用 `@Version` 乐观锁，防止两个执行者静默覆盖任务状态。

`AnalysisFailureCode` 只需要建立稳定、可扩展的高层分类。不要在本任务中把之前所有 GitHub、下载和 analyzer 失败枚举重新复制一遍，也不要依赖异常消息推断类型；下一步编排时再定义明确映射。

### 3. AnalysisReport

`AnalysisReport` 表示一次成功分析对应的持久化报告。建议字段：

```text
id
analysisTaskId
protocolVersion
analyzerTaskId
reportStatus
reportJson
generatedAt
createdAt
```

规则：

- `analysisTaskId` 通过外键关联 `AnalysisTask`，并建立唯一约束，使一个任务最多保存一份报告。
- `protocolVersion` 单独保存，方便以后识别和迁移不同协议版本。
- `analyzerTaskId` 来自 C++ 报告，与 Java 平台创建的 `AnalysisTask.taskId` 是两个字段。
- 当前 C++ analyzer 会生成自己的 task ID，因此不要强制两个 ID 相等。
- `reportJson` 保存由 `AnalyzerReportReader` 校验后再经过脱敏和稳定序列化的 JSON 快照。
- MySQL 中优先使用 `JSON` 列，并通过集成测试验证读写；不要在 Java 中用字符串拼 JSON。
- 本任务不增加健康分数字段，评分属于后续阶段。

### 4. 报告脱敏边界

现有 analyzer 协议中的 `repository.path` 指向本次扫描使用的目录。GitHub 下载场景中它通常是服务器临时工作区路径，不能原样持久化。

保存报告前必须建立明确快照规则：

- 删除或替换 `repository.path`，数据库中不得出现服务器绝对临时路径。
- 不保存 GitHub Token、带签名 query 的下载地址或请求 Header。
- 不保存 analyzer 的 stdout、stderr、诊断摘要或 Java 异常堆栈。
- 不直接保存未经校验的原始报告文件。
- 保留协议规定、后续展示和评分真正需要的结构化字段。
- 测试必须证明敏感路径和诊断文本不在最终 `reportJson` 中。

不要通过简单字符串替换处理 JSON。应先使用现有结构化解析器得到 `AnalyzerReport`，再由专门的快照工厂构造和序列化安全数据。

## 数据库迁移要求

第一份迁移建议创建：

```text
projects
analysis_tasks
analysis_reports
```

要求：

- 使用 Flyway 版本迁移，例如 `V1__create_analysis_persistence.sql`。
- 表名、列名、约束名和索引名保持稳定、可读。
- 字符集使用 `utf8mb4`。
- 建立主键、外键、唯一约束和必要查询索引。
- 为 `projects.repository_key`、`analysis_tasks.task_id` 建立唯一约束。
- 为 `analysis_reports.analysis_task_id` 建立唯一约束。
- 为常用查询方向建立索引，例如项目任务列表和任务状态查询。
- 能由数据库表达的非负数或非空规则应使用约束，同时保留 Java 领域校验。
- 迁移一旦合并不得回头修改；后续结构变化新增 `V2`、`V3`。
- Hibernate 配置使用 `ddl-auto=validate`，禁止 `create`、`create-drop` 或 `update` 代替迁移。
- 不提交本地数据库文件、数据目录或开发者账号密码。

删除规则必须保守。不要为了测试方便给项目、任务和报告配置意外的级联删除；如果确实需要级联，必须在任务实现说明中解释数据生命周期。

## Repository 契约

application 层至少需要以下能力，具体方法签名可根据现有代码风格调整：

```text
ProjectRepository
  save(Project project)
  findByRepositoryKey(ProjectRepositoryKey key)

AnalysisTaskRepository
  save(AnalysisTask task)
  findByTaskId(AnalysisTaskId taskId)

AnalysisReportRepository
  save(AnalysisReportRecord report)
  findByTaskId(AnalysisTaskId taskId)
```

要求：

- 业务代码只依赖这些接口，不直接依赖 Spring Data 的 `JpaRepository`。
- adapter 负责领域对象与 JPA entity 的双向转换。
- 不把 `EntityManager`、代理对象或数据库内部 ID 泄露给未来 Controller。
- 保存已存在项目时更新元数据，并保持内部项目身份稳定。
- 查询不存在的数据返回明确的空结果，不用 `null` 表示多种含义。
- 事务边界放在 application service 或明确的基础设施方法上，不在 Controller 中管理事务。

本任务只验证独立保存和读取能力，不实现“下载 -> 运行 analyzer -> 保存报告”的完整事务流程。

## 配置要求

生产数据库配置只能从部署环境读取，不在仓库中写入真实凭据。可以使用 Spring 标准配置和环境变量，例如：

```text
SPRING_DATASOURCE_URL
SPRING_DATASOURCE_USERNAME
SPRING_DATASOURCE_PASSWORD
```

要求：

- 仓库中不提供真实主机、账号或密码。
- 日志、异常和测试输出不得打印连接密码。
- JDBC 与 Hibernate 时间处理统一使用 UTC。
- Flyway 与应用使用同一个受控 schema。
- 启动时由 Flyway 执行迁移，再由 Hibernate 验证映射。
- 测试通过 Testcontainers 动态注入连接信息，不依赖开发者本机已有 MySQL 数据库。
- 固定 MySQL 容器镜像版本，并与项目文档记录保持一致。

如果新增专用测试配置，它只能包含测试默认值，不得包含个人路径、固定端口或真实凭据。

## 自动化测试要求

持久化测试必须可重复、彼此隔离，并且不访问 GitHub 或启动 C++ analyzer。

至少覆盖：

### 1. 领域模型规则

- `Project` 必填字段和非负计数校验。
- 合法任务状态转换成功。
- 跳过 `RUNNING`、从结束状态回退或重复结束被拒绝。
- 成功任务不能带失败码，失败任务必须带失败码。
- 时间和耗时规则可使用注入的 `Clock` 稳定测试，不依赖真实等待。

### 2. Flyway 与 MySQL 启动

- 从空的 Testcontainers MySQL schema 执行全部迁移成功。
- Hibernate `validate` 与迁移后的表结构一致。
- 测试不能依赖预先手工建表。
- 容器镜像使用固定版本，不使用 `latest`。

### 3. Project Repository

- 保存后可以完整读取字段。
- 相同规范化 `repositoryKey` 不会产生重复项目。
- owner/repository 大小写不同仍命中同一 key。
- 元数据更新后 `id` 和 `createdAt` 保持稳定，`updatedAt` 更新。
- 违反必填、唯一或非负约束时事务回滚。

### 4. AnalysisTask Repository

- `taskId`、项目关联、状态和时间字段可以往返读取。
- 状态与失败类型以字符串保存，不使用 ordinal。
- 重复 `taskId` 被唯一约束拒绝。
- 不存在的 `projectId` 被外键拒绝。
- 乐观锁能发现并发版本冲突。

### 5. AnalysisReport Repository

- 合法的脱敏 JSON 快照可以保存并读取。
- 每个任务最多一份报告。
- 不存在的任务不能保存报告。
- 平台 `taskId` 与 `analyzerTaskId` 可以不同并分别保留。
- `protocolVersion`、生成时间和报告 JSON 往返正确。
- `reportJson` 中不包含临时绝对路径、Token、stdout、stderr 或异常详情。
- 无效 JSON 不能作为合法报告保存。

### 6. 事务与清理

- 约束失败不会留下半条项目、任务或报告数据。
- 每个测试完成后数据隔离，不依赖执行顺序。
- 测试容器关闭后不在开发者机器留下项目数据库数据目录。

### 7. 原有测试继续通过

在 `openpulse-platform` 目录执行：

```powershell
.\mvnw.cmd clean verify
```

当前已有 172 个 Java 测试必须继续通过。新增的 MySQL 集成测试也必须由同一个 `verify` 命令执行，不能只在 IDE 中单独运行。

## 失败与安全要求

- 数据库异常不能被伪装成任务成功。
- 持久化失败类型应稳定，不依赖数据库驱动异常消息向上层展示。
- `failureMessage` 只能保存对用户安全、长度受限的说明。
- Token、数据库密码、服务器路径、stdout、stderr 和堆栈不得进入实体、JSON 快照或测试断言快照。
- 不在日志中打印整个 entity 或完整报告 JSON。
- 不用生产数据库做自动化测试，不让测试删除开发者已有 schema。
- 不为通过测试而关闭外键、唯一约束或 Flyway 校验。

## 明确不做

本任务禁止提前加入：

- `POST /api/v1/analysis/github` 或其他新的用户 HTTP 接口。
- GitHub 元数据、下载器和 analyzer 的完整业务编排。
- 自动开始、轮询或取消分析任务。
- 异步线程池、消息队列、定时任务或复杂重试。
- AI 总结、提示词、向量数据库或模型调用。
- 前端页面或前端 API 适配。
- 健康评分、复杂规则或排名。
- GitHub OAuth、私有仓库或多用户权限。
- Redis、缓存和分布式锁。
- Docker Compose、GitHub Actions 或生产部署脚本。
- 数据清理接口、保留策略任务或管理员后台。
- 为了演示而提交临时 Controller、命令行入口或硬编码数据库凭据。

## 完成标准

- `Project`、`AnalysisTask` 和持久化报告模型的职责与约束清楚。
- 领域模型不直接依赖 GitHub DTO、analyzer DTO 或 Spring Data Repository。
- Flyway 创建三张表及必要主键、外键、唯一约束和索引。
- Hibernate 使用 `ddl-auto=validate`，不自动修改数据库结构。
- application Repository 与 JPA adapter 分离。
- 平台任务 ID 与 analyzer task ID 分开保存。
- 报告快照经过结构化校验和脱敏，不保存临时路径或敏感诊断。
- Testcontainers MySQL 覆盖迁移、Repository、约束和 JSON 读写。
- 自动化测试不依赖真实 GitHub、C++ analyzer、个人 MySQL 或固定端口。
- 在 `openpulse-platform` 目录执行 `.\mvnw.cmd clean verify` 通过。
- `git diff --check` 通过。
- 没有数据库数据文件、构建产物、密钥或个人路径进入 Git。
- 创建范围单一的本地实现 commit。
- 先不要 push，回主线对话验收。

## 回主线对话时提交的信息

```text
分支：
commit：
新增依赖及版本：
Flyway 迁移：
Project 模型和唯一键：
AnalysisTask 状态转换与约束：
AnalysisReport 快照与脱敏规则：
平台 taskId 与 analyzerTaskId 的处理：
Repository 对外方法：
数据库配置和环境变量：
Testcontainers MySQL 镜像版本：
自动化测试场景：
全部测试数量和结果：
MySQL 迁移与兼容性验证：
敏感信息防泄露验证：
我理解的新名词：
遇到的问题：
```

## 新对话启动提示词

在相同项目工作区创建新对话后发送：

```text
请先阅读 README.md、docs/00-glossary.md、docs/01-mvp-v0.1.md、
docs/02-analyzer-json-protocol.md、docs/04-architecture.md、docs/06-roadmap.md、
docs/08-week-2-checklist.md、docs/tasks/05-java-github-repository-metadata.md、
docs/tasks/06-java-github-repository-download.md、
docs/tasks/07-java-analysis-persistence.md，
以及现有 GithubRepositoryMetadata、AnalyzerReport 和相关测试。

我是 Java 初学者，请严格完成“Java 第七步：任务和报告持久化”。
从最新 main 创建 feat/platform-analysis-persistence 分支。
每使用一个新名词前先用简单语言解释，再执行对应操作并验证。
不要加入任务书明确禁止的功能。
本任务只做 Project、AnalysisTask、AnalysisReport 的领域模型、Flyway 迁移、
Repository 接口与 JPA adapter，以及 Testcontainers MySQL 集成测试。
不要新增 HTTP GitHub 分析接口，不要编排 GitHub 下载和 C++ analyzer，
不要做 AI、前端、异步队列、复杂重试或健康评分。
Flyway 是数据库结构的唯一来源，Hibernate 只能使用 ddl-auto=validate。
持久化兼容性必须由固定版本的 Testcontainers MySQL 验证，不能只用 H2。
平台 taskId 和 C++ 报告中的 analyzerTaskId 必须分开保存。
报告入库前必须结构化校验并脱敏，不能保存临时绝对路径、Token、
stdout、stderr、诊断摘要或异常堆栈。
最后在 openpulse-platform 目录运行 .\mvnw.cmd clean verify，
创建范围单一的本地实现 commit，但不要 push 或创建 PR。
Docker 不可用时如实记录，不能伪造 MySQL 集成测试结果。
最后按任务书格式生成回主线对话的验收报告。
```
