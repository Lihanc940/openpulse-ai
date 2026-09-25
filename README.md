# OpenPulse AI

OpenPulse AI 是一个面向开发者和 AI 工具的本地优先仓库分析工具。它先在用户环境中生成透明、可验证的结构化报告，再由人直接阅读，或交给 Skill、MCP 等适配入口使用。

当前目标闭环：

```text
本地仓库 -> C++ 分析核心 -> 透明 JSON 报告 -> 人直接阅读或 AI 按需解释
```

网站没有被删除，但不再是唯一入口。Java Platform 和 Vue Web 被定位为后续团队模式，用于 GitHub 获取、任务历史、报告持久化和多人查看。

## 当前阶段

P1 Java/C++ 本地协作闭环已经完成，GitHub URL 解析、公开仓库元数据查询、受控下载以及任务和报告持久化也已经合并完成。

2026-09-02，项目通过 `v0.2` 产品方向复核：暂停继续堆叠网站、AI 总结和黑盒健康分，下一阶段先验证“本地 CLI + 透明报告 + 一个 Codex Skill”是否真的能给开发者带来稳定、节省上下文的仓库分析能力。完整决定见 `docs/09-product-direction-v0.2.md`。

建议先阅读：

1. `docs/00-glossary.md`：项目常见名词解释。
2. `docs/09-product-direction-v0.2.md`：当前产品定位、范围和验证门槛。
3. `docs/04-architecture.md`：本地核心、AI 适配和团队模式如何拆分。
4. `docs/02-analyzer-json-protocol.md`：Java 和 C++ 当前如何通信。
5. `docs/06-roadmap.md`：各阶段的目标和完成标准。
6. `docs/05-git-workflow.md`：两个人如何提交和评审代码。
7. `docs/07-development-environment.md`：双方需要安装什么，以及首次建仓流程。
8. `docs/01-mvp-v0.1.md`：已经被 v0.2 取代的历史 MVP 方案。
9. `docs/03-week-1-checklist.md`：第一周动手清单。
10. `docs/08-week-2-checklist.md`：第二周动手清单和方向调整位置。

## 推荐仓库结构

当前使用一个总仓库管理三个子项目：

```text
Openpulse AI/
  openpulse-platform/   Java 编排、持久化和后续团队服务
  openpulse-analyzer/   C++20 本地分析核心
  openpulse-web/        后续团队模式的 Vue 3 前端
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
- `docs/tasks/05-java-github-repository-metadata.md`
- `docs/tasks/06-java-github-repository-download.md`
- `docs/tasks/07-java-analysis-persistence.md`
- `docs/tasks/10-java-analyzer-report-v2-reader.md`
- Java 协议 v2 Reader 已通过 PR #24 合并，`main` 对应提交为 `897964c`

已完成的共同任务：

- `docs/tasks/08-v02-validation-baseline.md`
- v0.2 验证基线已经由 Java 与 C++ 负责人共同签字并激活
- `docs/tasks/09-report-protocol-v2-design.md`
- 报告协议 v2 已完成 Java/C++ 双方评审并标记为 `ACCEPTED`
- 协议 v1 在实现迁移和默认版本切换前继续兼容

当前开发任务：

- `docs/tasks/11-cpp-analyzer-report-v2-output.md`
- C++ 负责人正在实现显式选择的 v2 输出；协议 v1 仍是默认输出

下一项共同任务已完成任务书准备，但尚未开始执行：

- `docs/tasks/12-v2-contract-integration.md`
- 任务 11 合并后，Java 与 C++ 负责人将使用同一组虚构 fixture 完成“C++ 生成 -> Schema 验证 -> Java 读取”的联合契约验收
- 当前没有联合 fixture 结果，也没有授权切换默认协议、移除 v1 或填写 v0.2 实验结果
