# 第二周动手清单

第二周目标：从“本地 Java/C++ 能互相调用”推进到“用户可以提交一个仓库地址，系统能创建可追踪的分析任务”。

第一周已经完成的核心基础：

- Java Spring Boot 服务可构建、可测试。
- C++ analyzer 可以扫描本地目录并输出协议 v1 JSON。
- Java 可以解析协议 v1 报告。
- Java 可以安全启动 analyzer 进程，处理超时、退出码、报告缺失和临时目录清理。

第二周不要急着做 AI、前端美化或复杂评分。先把任务生命周期、GitHub 输入、下载边界和持久化打稳。

## 第 1 天：P1 收尾和本地分析 API

Java 负责人先补一个本地闭环入口：

- 新增 `POST /api/v1/analysis/local` 或等价接口。
- 请求只接收一个已经存在的本地目录路径。
- 调用现有 `AnalyzerProcessRunner`。
- 返回已解析的 `AnalyzerReport` 或稳定错误响应。
- 不接数据库、不下载 GitHub、不做 AI。
- 用 MockMvc 覆盖成功、路径非法、runner 失败。

这一步完成后，P1 本地最小闭环才算从“内部能力”变成“可被 API 调用”。

## 第 2 天：GitHub URL 解析与仓库元数据

只做输入校验和基础信息获取：

- 支持 `https://github.com/{owner}/{repo}`。
- 拒绝空地址、非 GitHub 地址、缺少 owner/repo 的地址。
- 去掉 `.git` 后缀、query、fragment 和尾部斜杠。
- 暂时只支持公开仓库。
- 通过 GitHub API 获取仓库名称、默认分支、描述、stars、forks、语言等基础信息。
- Token 只允许从环境变量读取，不写入配置文件或测试数据。

先不要下载代码，也不要创建数据库表。

## 第 3 天：受控下载公开仓库

下载逻辑必须有边界：

- 每次下载进入 Java 自己创建的临时目录。
- 支持超时。
- 限制压缩包或 checkout 后目录大小。
- 拒绝路径穿越。
- 成功和失败都清理临时目录。
- 自动化测试不能依赖真实 GitHub 网络，用本地 HTTP fixture 或可替换下载器。

可以先选择 GitHub archive zip 下载，不必立刻引入完整 `git clone`。

## 第 4 天：任务和报告持久化设计

开始引入数据库前，先写清楚模型：

- `Project`：GitHub 仓库基础信息。
- `AnalysisTask`：一次分析任务、状态、失败原因、耗时。
- `AnalysisReport`：协议 JSON 和后续评分的来源。

状态至少包含：

```text
PENDING
RUNNING
SUCCESS
FAILED
```

先写实体和 repository 测试，再接入业务流程。

## 第 5 天：创建 GitHub 分析任务接口

形成第二周核心流程：

```text
POST /api/v1/analysis/github
  -> 解析 GitHub URL
  -> 获取仓库元数据
  -> 创建任务
  -> 下载代码到临时目录
  -> 调用 C++ analyzer
  -> 保存报告
  -> 返回任务结果
```

第一版可以同步执行，不需要消息队列或异步任务系统。等流程稳定后再改异步。

## 第 6 天：失败路径和清理复核

重点不是新增功能，而是把错误变清楚：

- GitHub 地址错误。
- 仓库不存在或不是公开仓库。
- 下载超时。
- 下载内容过大。
- analyzer 超时或退出码非零。
- analyzer 报告不合法。
- 数据库保存失败。
- 临时目录清理失败。

每类错误都要能在任务状态或响应中定位原因。

## 第 7 天：复盘和 Week 3 准备

记录：

- 一个真实公开仓库能否从 URL 跑到报告。
- 哪些失败路径已有测试。
- 哪些字段需要入库，哪些只保留在 JSON 报告里。
- P3 健康评分需要哪些数据。
- 前端最小页面需要哪些 API。

Week 3 再集中做规则、评分、报告查询和前端展示。

## 本周禁止提前做

- GitHub OAuth 或私有仓库。
- AI 总结。
- Docker Compose。
- GitHub Actions。
- 前端完整页面。
- Redis、消息队列或复杂异步调度。
- 多用户权限。
- 自动提交 PR 或自动修改用户代码。
- 大规模并发扫描。

## 本周完成标准

- P1 本地分析 API 可以通过 HTTP 调用 runner。
- GitHub URL 解析有稳定测试。
- GitHub 元数据获取边界清楚。
- 仓库下载有超时、大小和清理保护。
- 至少一个公开仓库可以完成一次端到端分析。
- 任务状态和失败原因可以被追踪。
- `.\openpulse-platform\mvnw.cmd clean verify` 通过。
- C++ analyzer 单测继续通过。
- 没有密钥、个人路径、构建产物或可执行文件进入 Git。
