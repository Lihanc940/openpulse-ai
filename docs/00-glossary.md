# 术语解释

这份文档用来把项目计划书里的名词翻译成能动手的概念。

## MVP

MVP 是 Minimum Viable Product，意思是“最小可用版本”。

对 OpenPulse AI 来说，MVP 不是把所有功能都做完，而是先跑通核心链路：

```text
输入仓库 -> 下载代码 -> C++ 扫描 -> Java 接收结果 -> 健康评分 -> AI 建议 -> 页面展示
```

只要这条链路能稳定运行，项目就已经有展示价值。

## 前端、后端、分析引擎

前端是用户看到和操作的页面，比如输入 GitHub 地址、查看评分图和风险列表。本项目推荐 Vue 3。

后端是系统的大脑和协调者，负责接收前端请求、调用 GitHub API、创建分析任务、保存数据库、调用 AI。本项目推荐 Java Spring Boot。

分析引擎是专门扫描代码的程序，负责统计代码行数、识别复杂函数、检查 README/LICENSE/CI 等项目结构。本项目用 C++20 来做。

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

MySQL 是关系型数据库，用来保存用户、仓库、分析任务、分析报告、AI 报告等长期数据。

第一版可以先设计表结构，不需要一开始追求复杂优化。

## Redis

Redis 是内存缓存。它速度快，适合保存短期数据。

本项目里 Redis 可以用于缓存 GitHub API 结果、限制重复分析、保存任务状态。MVP 阶段可以先不用，等 Java 主流程跑通后再接。

## CLI

CLI 是 Command Line Interface，命令行程序。

第一版建议 C++ 分析引擎做成 CLI，例如：

```bash
openpulse-analyzer --path ./demo-project --output report.json
```

Java 用 `ProcessBuilder` 启动这个命令，等待它生成 JSON 报告。

## 静态分析

静态分析是不运行项目代码，只阅读代码文本或语法结构来发现问题。

第一版可以先做文本规则：

- 文件是否太长
- 函数是否太长
- 嵌套是否太深
- TODO/FIXME 是否过多
- C/C++ 是否出现危险函数
- 项目是否缺 README、LICENSE、CI

## 复杂度

复杂度表示代码理解和维护的难度。

第一版可以先用近似指标判断：

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

## AI 顾问

AI 顾问不是随便聊天，而是根据结构化数据生成解释。

正确方式：

```text
C++ 扫描结果 + GitHub 数据 + 项目结构指标 -> Java 整理摘要 -> AI 生成报告
```

这样 AI 的建议才有依据，也更像真实产品。

## Docker Compose

Docker Compose 用来一键启动多个服务。

后期可以用它启动：

- Java 后端
- MySQL
- Redis
- 前端

C++ 分析器可以打包进 Java 后端镜像，或者作为独立可执行文件挂载进去。

## CI

CI 是 Continuous Integration，持续集成。

简单理解：每次提交代码后，让 GitHub Actions 自动帮你运行测试、构建项目，检查代码有没有坏。

## JWT

JWT 是一种登录身份令牌。

用户登录成功后，后端返回一个 token。前端之后请求接口时带上这个 token，后端就知道是谁在操作。

MVP 可以先做简单账号登录，GitHub OAuth 后面再加。

