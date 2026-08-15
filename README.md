# OpenPulse AI

OpenPulse AI 是一个面向 GitHub 开源仓库的智能分析平台。第一版目标是跑通一个清晰闭环：

输入 GitHub 公共仓库地址 -> 获取仓库信息 -> 下载代码 -> 调用 C++ 分析引擎 -> 生成 JSON 报告 -> Java 保存并计算健康分 -> AI 生成解释和建议 -> 前端展示报告。

## 当前阶段

P1 本地最小闭环已经完成，现在进入 P2 真实 GitHub 仓库分析阶段。当前先建立 GitHub URL、仓库元数据和外部 API 失败边界，再进入受控下载、持久化和分析任务编排。

建议先阅读：

1. `docs/00-glossary.md`：项目常见名词解释。
2. `docs/01-mvp-v0.1.md`：第一版到底做什么、不做什么。
3. `docs/04-architecture.md`：系统为什么这样拆分。
4. `docs/02-analyzer-json-protocol.md`：Java 和 C++ 怎么通信。
5. `docs/05-git-workflow.md`：两个人如何提交和评审代码。
6. `docs/06-roadmap.md`：各阶段的目标和完成标准。
7. `docs/07-development-environment.md`：双方需要安装什么，以及首次建仓流程。
8. `docs/03-week-1-checklist.md`：第一周动手清单。
9. `docs/08-week-2-checklist.md`：第二周动手清单。

## 推荐仓库结构

第一版可以先用一个总仓库管理三个子项目：

```text
Openpulse AI/
  openpulse-platform/   Java Spring Boot 后端
  openpulse-analyzer/   C++20 静态分析引擎
  openpulse-web/        Vue 3 前端
  docs/                 开发文档、接口协议、学习笔记
  document/             原始项目计划书
```

当前采用单仓库和模块化 Java 后端。只有出现明确的独立发布或扩容需求时，才考虑拆分服务或仓库。

## 工程约定

开发规则见 `CONTRIBUTING.md`。任何密钥和本地配置都不能提交；功能通过短期分支和 Pull Request 合并到 `main`。

## 当前开发任务

Java 负责人已完成：

- `docs/tasks/01-java-platform-bootstrap.md`
- `docs/tasks/02-java-analyzer-report-parser.md`
- `docs/tasks/03-java-analyzer-process-runner.md`
- `docs/tasks/04-java-local-analysis-api.md`

Java 负责人当前执行：

- `docs/tasks/05-java-github-repository-metadata.md`
- 建议分支：`feat/platform-github-metadata`

任务对话完成本地实现和测试后，回到项目主线对话复核，再 push 并创建 Pull Request。
