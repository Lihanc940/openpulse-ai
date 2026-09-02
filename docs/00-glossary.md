# 术语解释

这份文档用来把项目计划书里的名词翻译成能动手的概念。

## MVP

MVP 是 Minimum Viable Product，意思是“最小可用版本”。

对 OpenPulse AI v0.2 来说，MVP 不是把所有功能都做完，而是先证明本地分析结果对人和 AI 都有用：

```text
本地仓库 -> C++ 扫描 -> 透明结构化报告 -> 人阅读或 AI 按需解释
```

网站、团队服务和自动化入口只有在核心价值验证后才继续扩展。

## 前端、后端、分析引擎

前端是用户看到和操作的页面，比如提交仓库、查看历史报告和团队共享结果。本项目保留 Vue 3，但前端不属于 v0.2 的第一验证阶段。

后端是团队模式的协调者，负责接收请求、调用 GitHub API、创建分析任务和保存数据库。本项目使用 Java Spring Boot，但本地分析核心不依赖网站或 AI 才能运行。

分析引擎是专门扫描代码的程序。当前能力包括文件和行数统计以及 README/LICENSE/CI 等项目结构检查；识别函数和代码结构需要后续语法树规则支持。本项目用 C++20 来做。

## API

API 是模块之间约定好的调用方式。

例子：前端想让后端分析一个仓库，可以调用：

```http
POST /api/repositories/analyze
```

请求内容可能是：

```json
{
  "url": "https://github.com/owner/repo"
}
```

## JSON

JSON 是一种结构化数据格式，适合不同语言之间交换数据。

本项目里，C++ 分析引擎不需要直接操作 Java 数据库，只要输出一个 JSON 文件。Java 读取这个 JSON，就知道 C++ 扫描到了什么。

## GitHub API

GitHub API 是 GitHub 提供给程序使用的接口。

Java 后端可以通过它获取仓库的 Star、Fork、Issue、PR、最近更新时间、主要语言等数据。

## Spring Boot

Spring Boot 是 Java 后端开发框架。你可以把它理解成“快速搭建 Web 后端项目的工具箱”。

它会帮你处理 HTTP 接口、配置文件、数据库连接、权限认证、定时任务等常见工作。

## MySQL

MySQL 是关系型数据库，用来保存团队模式中的仓库、分析任务和历史报告等长期数据。

第一版可以先设计表结构，不需要一开始追求复杂优化。

## Redis

Redis 是内存缓存。它速度快，适合保存短期数据。

Redis 目前没有进入路线图。只有真实用户量或任务协调问题证明数据库和进程内状态不够用时，才重新评估。

## CLI

CLI 是 Command Line Interface，命令行程序。

当前 C++ 分析引擎已经是 CLI，例如：

```bash
openpulse-analyzer --path ./demo-project --output report.json
```

Java 用 `ProcessBuilder` 启动这个命令，等待它生成 JSON 报告。

v0.2 会把 CLI 提升为用户可以直接安装和运行的产品入口，而不只把它当作 Java 内部组件。

## 确定性报告

“确定性”表示在分析器版本、规则版本和输入仓库不变时，结果应当稳定重复，而不是由 AI 临时猜测。

报告中的每条发现都应包含规则编号、位置、证据和修复方向。没有真实依据的总健康分不属于 v0.2 核心输出。

## Skill

Skill 是一组给 AI 使用的说明和辅助文件，告诉 AI 什么时候运行 OpenPulse、怎样读取报告、怎样向用户解释结果。

Skill 不是分析引擎本身。真正的扫描和规则判断仍由本地分析器完成。

不同 AI 产品对 Skill 的格式和安装方式可能不同。v0.2 先验证一个 Codex Skill，不把它宣传成所有 AI 都能直接安装的通用包。

## MCP

MCP 是 Model Context Protocol，可以简单理解为“AI 调用本地工具的通用接口”。

未来 OpenPulse 可以通过 MCP 暴露 `analyze_repository`、`list_findings` 和 `get_finding_evidence` 等工具。MCP 属于价值验证后的扩展，不在第一阶段同时开发。

## 适配层

适配层是连接核心能力和不同入口的薄代码。Skill、MCP、GitHub Action 和网站都只能调用同一套分析核心，不能各自重新实现规则。

## Tree-sitter

Tree-sitter 是把源代码解析成语法树的工具。语法树能识别函数、类、参数和嵌套结构，比单纯搜索文本更可靠。

C++ 分析器可以逐步接入 Tree-sitter。v0.2 如果增加语法树规则，先限定 Java 和 C++；其他语言只报告已经验证的仓库级事实。

## 静态分析

静态分析是不运行项目代码，只阅读代码文本或语法结构来发现问题。

第一阶段可以保留文件和项目结构规则，代码级规则需要明确区分文本证据和语法树证据：

- 文件是否太长
- 函数是否太长
- 嵌套是否太深
- TODO/FIXME 是否过多
- C/C++ 是否出现危险函数
- 项目是否缺 README、LICENSE、CI

## 复杂度

复杂度表示代码理解和维护的难度。

v0.2 可以先对明确支持的语言使用这些可解释指标：

- 函数行数
- if/for/while/switch 数量
- 最大嵌套深度
- 参数数量

不用一开始就实现完整编译器级分析。

## 规则引擎

规则引擎就是把检查逻辑拆成一条条规则。

例子：

```text
LongFunctionRule: 函数超过 80 行，标记为高风险。
MissingLicenseRule: 仓库没有 LICENSE 文件，文档完整度扣分。
DangerousFunctionRule: C/C++ 代码出现 strcpy，提示潜在风险。
```

这样后续增加规则会更容易。

## AI 适配

AI 不再负责产生基础分析事实，而是根据 OpenPulse 的结构化证据做解释、排序和修改指导。

正确方式：

```text
OpenPulse 本地报告 -> AI 按需查询证据 -> AI 解释和指导
```

AI 适配是可选入口。没有 AI 时，用户仍然能够获得完整的确定性报告。

## Docker Compose

Docker Compose 用来一键启动多个服务。

后续团队模式可能用它启动：

- Java 后端
- MySQL
- 前端

C++ 分析器可以打包进 Java 后端镜像，或者作为独立可执行文件挂载进去。

## CI

CI 是 Continuous Integration，持续集成。

简单理解：每次提交代码后，让 GitHub Actions 自动帮你运行测试、构建项目，检查代码有没有坏。

## JWT

JWT 是一种登录身份令牌。

用户登录成功后，后端返回一个 token。前端之后请求接口时带上这个 token，后端就知道是谁在操作。

v0.2 本地产品不需要登录。JWT 只有团队模式出现明确的多用户权限需求时才评估。
