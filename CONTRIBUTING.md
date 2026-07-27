# OpenPulse AI 贡献指南

这份规则同时适用于两位开发者。目标是让每次改动都容易理解、测试和回退。

## 分支规则

- `main`：始终保持可构建、可演示，不直接在此分支开发。
- `feat/<name>`：新功能，例如 `feat/java-health-api`。
- `fix/<name>`：缺陷修复，例如 `fix/analyzer-path-error`。
- `docs/<name>`：只修改文档，例如 `docs/json-protocol`。
- `chore/<name>`：构建、工具或依赖维护。

每项工作从最新的 `main` 创建短期分支，完成后通过 Pull Request 合并。

## 提交信息

采用简化的 Conventional Commits：

```text
feat(platform): add analysis task endpoint
fix(analyzer): handle unreadable source files
docs(protocol): clarify analyzer exit codes
test(platform): cover report parsing failure
chore(repo): add editor settings
```

常用类型：

- `feat`：新增用户可感知的能力
- `fix`：修复错误
- `docs`：文档修改
- `test`：测试修改
- `refactor`：不改变行为的代码整理
- `chore`：构建和工程配置

一次提交只表达一个完整意图。不要提交密钥、令牌、构建产物和本地配置。

## Pull Request

即使只有两个人，也通过 Pull Request 合并重要改动。提交前确认：

- 改动范围单一，标题能说明目的
- 对应模块可以构建
- 新行为有测试，或说明暂时无法测试的原因
- Java/C++ 协议变更同步更新协议文档和示例 JSON
- 没有提交密码、Token、`.env` 或大型生成文件
- 另一位开发者已阅读影响双方边界的改动

## 完成定义

一项任务只有同时满足以下条件才算完成：

1. 功能满足验收标准。
2. 自动化测试通过。
3. 错误场景有明确处理。
4. 必要文档已更新。
5. 代码已通过 Pull Request 合并到 `main`。
