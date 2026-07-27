# 开发环境与首次建仓指南

本文用于让两位开发者在 Windows 上建立一致的开发环境，并记录 OpenPulse AI 第一次发布到 GitHub 的完整过程。

## 先理解三件事

- **开发环境**：编译、运行和测试项目所需的工具集合。
- **版本要求**：两台电脑尽量使用相同的大版本，减少“我的电脑能跑、你的不能跑”。
- **按阶段安装**：当前用不到的工具暂不安装。真实工程需要可重复，不需要一次堆满软件。

## 双方现在都需要

### Git

用途：保存代码版本、创建分支、合并协作内容。

官方下载：https://git-scm.com/install/windows

验证：

```powershell
git --version
git config --global user.name
git config --global user.email
```

首次使用时设置身份：

```powershell
git config --global user.name "你的 GitHub 用户名"
git config --global user.email "你的 GitHub 邮箱"
```

### GitHub 账号

用途：托管远程仓库、Pull Request、Issue 和自动化测试。

账号地址：https://github.com/

建议：

- 开启双重验证
- 不共享密码或 Token
- 每人使用自己的账号和提交身份

### GitHub CLI

用途：从命令行登录 GitHub、创建仓库、推送代码和管理协作者。

官方说明：https://cli.github.com/

验证：

```powershell
gh --version
gh auth status
```

GitHub CLI 是协作辅助工具，不参与 OpenPulse AI 运行，也不能提交到项目仓库。

## Java 负责人现在需要

### JDK 21 LTS

用途：编译和运行 Java 后端。JDK 是 Java 开发工具包，LTS 表示长期支持版本。

推荐 Eclipse Temurin 21：https://adoptium.net/temurin/releases/?version=21

验证：

```powershell
java -version
javac -version
```

### Maven 3.9+

用途：下载 Java 依赖、编译、测试和打包 Spring Boot 项目。

官方下载：https://maven.apache.org/download.cgi

验证：

```powershell
mvn -version
```

### IntelliJ IDEA

用途：编写、运行和调试 Java。个人学习和开源开发可使用 Community 版本。

官方下载：https://www.jetbrains.com/idea/download/

它是编辑器，不决定项目架构。项目必须在不依赖 IDE 的情况下通过 Maven 命令构建。

## C++ 负责人现在需要

### Visual Studio Community 或 Build Tools

用途：提供 Windows C++ 编译器、标准库、调试器和 Windows SDK。

官方下载：https://visualstudio.microsoft.com/downloads/

安装时选择：

```text
Desktop development with C++
```

至少包含：

- MSVC C++ x64/x86 build tools
- Windows SDK
- C++ CMake tools for Windows

验证需要在 Developer PowerShell 中执行：

```powershell
cl
```

### CMake 稳定版

用途：用统一配置生成和构建 C++ 项目，避免把工程绑定到某一个 IDE。

官方下载：https://cmake.org/download/

安装时选择将 CMake 加入 `PATH`，然后验证：

```powershell
cmake --version
```

## 前端开始时再统一

### Node.js LTS

用途：运行 Vue 构建工具和前端依赖。

官方下载：https://nodejs.org/en/download

开始前端开发时，两人统一到同一个 LTS 大版本，并通过项目锁文件固定依赖。

验证：

```powershell
node --version
npm --version
```

## 本地闭环完成后再安装

### Docker Desktop

用途：统一运行 MySQL、后端、前端等服务，降低不同电脑的环境差异。

官方安装说明：https://docs.docker.com/desktop/setup/install/windows-install/

Windows 推荐 WSL 2 后端。安装后验证：

```powershell
docker --version
docker compose version
```

当前不要单独安装 MySQL、Redis 或 Kubernetes。进入数据库阶段后，优先通过 Docker Compose 固定版本。Redis 只有出现明确的缓存或任务协调需求时才加入。

## 当前两台电脑的目标环境

| 工具 | Java 负责人 | C++ 负责人 | 当前阶段 |
|---|---:|---:|---|
| Git | 必须 | 必须 | 现在 |
| GitHub CLI | 推荐 | 推荐 | 现在 |
| JDK 21 | 必须 | 不要求 | 现在 |
| Maven 3.9+ | 必须 | 不要求 | 现在 |
| Visual Studio C++ 工具 | 不要求 | 必须 | 现在 |
| CMake | 不要求 | 必须 | 现在 |
| Node.js LTS | 后续 | 后续 | 前端阶段 |
| Docker Desktop | 后续 | 后续 | 本地闭环后 |

## 第一次创建 GitHub 仓库

下面是本项目正在执行的流程。

### 1. 初始化本地仓库

```powershell
git init -b main
git status
```

`git init` 只建立本地版本库，不会上传文件。

### 2. 检查提交范围

```powershell
git status
git check-ignore .env
```

先确认 `.gitignore` 正常，并确保没有密码、Token、构建产物和个人配置。

### 3. 创建第一次本地提交

```powershell
git add .editorconfig .gitattributes .gitignore CONTRIBUTING.md README.md docs document openpulse-platform openpulse-analyzer openpulse-web
git diff --cached
git commit -m "chore(repo): establish project baseline"
```

`git add` 是选择本次提交内容；`git commit` 是创建本地历史节点，两者都不是上传。

### 4. 创建远程仓库并推送

```powershell
gh repo create openpulse-ai --public --source . --remote origin --push
```

这条命令依次完成：

1. 在当前 GitHub 账号创建公开仓库
2. 把远程地址保存为 `origin`
3. 把本地 `main` 推送到 GitHub

### 5. 邀请协作者

需要搭档准确的 GitHub 用户名。邀请成功后，对方会在 GitHub 收到通知。

### 6. 设置 `main` 分支保护

建议启用：

- 只通过 Pull Request 合并
- 禁止强制推送
- 合并前必须解决评审讨论
- 有 CI 后要求自动检查通过

初期只有两位开发者时，不强制“至少一位批准”，否则两个人各自提交的 PR 可能无法自助合并；涉及 Java/C++ 协议的变更仍要求另一人实际评审。

## 搭档第一次加入项目

接受 GitHub 邀请后：

```powershell
git clone https://github.com/<owner>/openpulse-ai.git
cd openpulse-ai
git status
```

第一次练习使用文档分支：

```powershell
git switch -c docs/setup-check
```

修改一处自己的环境记录后：

```powershell
git status
git diff
git add <修改的文件>
git commit -m "docs(setup): confirm cpp environment"
git push -u origin docs/setup-check
```

然后在 GitHub 创建 Pull Request，请另一位开发者阅读并合并。

## GitHub 网络排查

先检查主站端口：

```powershell
Test-NetConnection github.com -Port 443
```

`TcpTestSucceeded` 应为 `True`。如果浏览器能访问、命令行不能访问，通常是浏览器使用了代理，而终端没有。

仅对当前 PowerShell 临时设置代理的形式如下：

```powershell
$env:HTTPS_PROXY = "http://127.0.0.1:<你的代理端口>"
$env:HTTP_PROXY = "http://127.0.0.1:<你的代理端口>"
```

端口必须来自本人正在使用的网络工具，不要照抄别人的端口。不要把代理地址、账号或 Token 写入项目文件。
