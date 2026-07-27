# 第一周动手清单

第一周目标：先把项目骨架和协作边界搭起来，不追求完整功能。

## 第 1 天：确认范围

- 阅读 `docs/00-glossary.md`
- 阅读 `docs/01-mvp-v0.1.md`
- 阅读 `docs/04-architecture.md`
- 阅读 `docs/05-git-workflow.md`
- 和朋友确认谁负责 Java、谁负责 C++
- 确认第一版只支持 GitHub 公共仓库
- 确认第一版 C++ 先做 CLI，不做独立服务

## 第 2 天：建立仓库与协作规则

- 在 GitHub 创建仓库并邀请协作者
- 推送工程基线 commit
- 为 `main` 设置分支保护
- 双方各自克隆并完成一次文档 PR 练习
- 确认 Java 21、Maven、C++ 编译器和 CMake 环境

## 第 3 天：确定 JSON 协议

- 阅读 `docs/02-analyzer-json-protocol.md`
- 你和朋友确认字段是否够用
- C++ 先写一个能输出示例 JSON 的假分析器
- Java 先写一个能读取示例 JSON 的解析逻辑

这一步很关键：先让两个模块“说同一种话”。

## 第 4-5 天：C++ 最小扫描器

C++ 负责人先实现：

- 接收 `--path`
- 接收 `--output`
- 遍历目录
- 跳过 `.git`、`node_modules`、`target`、`build`、`dist`
- 统计文件总数
- 统计源码文件数
- 输出 JSON

先不用做复杂度，先让命令跑起来。

## 第 4-5 天：Java 最小后端

Java 负责人先实现：

- Spring Boot 项目初始化
- 一个测试接口：`GET /api/health`
- 一个分析接口草稿：`POST /api/repositories/analyze`
- 能读取本地 `report.json`
- 能把 JSON 结果返回给前端或 Postman

先不用连 MySQL、Redis、AI。

## 第 6 天：第一次联调

目标不是完美，而是让流程动起来：

```text
Java 接收请求 -> 调用 C++ 命令 -> C++ 输出 JSON -> Java 读取 JSON -> 返回结果
```

这一步跑通之后，你们项目的骨架就立住了。

## 第 7 天：复盘

记录：

- 哪些命令能跑通
- JSON 字段是否需要改
- 哪些错误最常见
- 下一周要补哪些功能

第二周再做 GitHub API、仓库下载和更真实的扫描规则。
