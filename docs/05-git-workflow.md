# Git 与 GitHub 协作流程

## 四个容易混淆的词

- **Git**：本机的版本管理工具，记录文件每次可靠的变化。
- **commit（提交）**：给一组相关改动创建一个带说明的历史节点。
- **GitHub**：托管远程 Git 仓库并提供协作、评审和自动检查的平台。
- **push（推送）**：把本地 commit 上传到 GitHub。

所以“提交 Git”通常包含两个动作：先在本地 `commit`，再 `push` 到 GitHub。没有 commit 的改动不能成为可靠版本。

## 两人协作的标准循环

```text
同步 main
  -> 创建功能分支
  -> 小步开发和测试
  -> 本地 commit
  -> push 到 GitHub
  -> 创建 Pull Request
  -> 另一人检查
  -> 自动测试通过
  -> 合并到 main
```

Pull Request（简称 PR）是“请求把这个分支的改动合并进主分支”。它会集中展示代码差异、讨论和测试结果。

## 常用命令

首次克隆后创建功能：

```powershell
git switch main
git pull --ff-only
git switch -c feat/java-health-api
```

检查并提交：

```powershell
git status
git diff
git add <明确的文件>
git commit -m "feat(platform): add health endpoint"
git push -u origin feat/java-health-api
```

`git add` 不是上传，它只是选择“下一个 commit 要包含哪些变化”。提交前必须先看 `git status` 和 `git diff`。

## 仓库保护建议

远程仓库建立后，为 `main` 设置：

- 禁止强制推送
- 通过 Pull Request 合并
- 至少一人评审涉及 Java/C++ 协议的修改
- CI 检查通过后才能合并
- 合并后删除功能分支

两个人也值得做代码评审，因为评审的意义不只是找错，还包括共享系统知识。

## 第一次发布到 GitHub

1. 本地初始化 Git 并完成工程基线 commit。
2. 在 GitHub 创建空仓库，不自动生成 README 或 `.gitignore`。
3. 添加远程地址 `origin`。
4. 推送 `main`。
5. 邀请另一位开发者并设置分支保护。

GitHub 仓库建立和第一次推送需要由仓库所有者确认，后续再执行。
