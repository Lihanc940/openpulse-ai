# OpenPulse Analyzer Windows x64 中文使用指南

OpenPulse Analyzer 是一个在本机运行的仓库结构分析命令行工具。当前版本会检查 README、许可证和 CI 等项目基础结构，并生成透明的 JSON 报告。运行时不需要账号、网站、Java、CMake 或 Visual Studio，也不会上传你的仓库。

本文随 0.2.2 补丁候选包更新；正式可下载版本以 GitHub Releases 为准。旧版 0.2.1 不包含多模块识别修复，更新源码不会自动更新已经下载的 exe。

英文使用指南：发布包内的 `README.md`；仓库中的[英文源文档](https://github.com/Lihanc940/openpulse-ai/blob/main/docs/windows-x64-cli.md)。

## 1. 下载并校验文件

从项目的官方 GitHub Releases 页面下载以下两个相邻文件：

- `openpulse-analyzer-0.2.2-windows-x64.zip`
- `openpulse-analyzer-0.2.2-windows-x64.zip.sha256`

把两个文件放在同一目录，在该目录打开 PowerShell，执行：

```powershell
Get-FileHash .\openpulse-analyzer-0.2.2-windows-x64.zip -Algorithm SHA256
Get-Content .\openpulse-analyzer-0.2.2-windows-x64.zip.sha256
```

比较两条命令显示的 64 位十六进制哈希值。完全一致表示下载后的 ZIP 内容没有发生变化。SHA-256 只能校验文件完整性，不等同于数字签名，也不能单独证明发布者身份。

## 2. 解压和安装

把 ZIP 解压到你选择的目录，例如 `C:\Tools`。压缩包内已经有版本根目录，解压后应能看到：

```text
openpulse-analyzer-0.2.2/
  bin/openpulse-analyzer.exe
  README.md
  README.zh-CN.md
  LICENSE
  THIRD_PARTY_NOTICES.md
  licenses/
```

这就是全部安装过程。无需管理员权限、安装程序、注册表修改或配置系统 PATH。建议不同版本放在不同目录，方便升级和回退。

## 3. 第一次运行

在文件资源管理器中打开解压后的版本目录，在地址栏输入 `powershell` 并回车。程序通过终端使用；双击 exe 后窗口一闪而过不能表示分析成功。

旧版 Windows PowerShell 先设置当前窗口使用 UTF-8，避免中文摘要乱码：

```powershell
chcp 65001
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
```

这些设置只用于本次终端会话，不要求修改系统区域设置。然后创建报告输出目录：

```powershell
New-Item -ItemType Directory -Force -Path "$env:USERPROFILE\OpenPulseReports"
```

进入解压后的 `openpulse-analyzer-0.2.2` 目录，先查看帮助：

```powershell
.\bin\openpulse-analyzer.exe --help
```

然后分析一个本地仓库。路径中有空格或中文时也要保留双引号：

```powershell
.\bin\openpulse-analyzer.exe --protocol 2.0 --summary --path "C:\我的项目\示例仓库" --output "$env:USERPROFILE\OpenPulseReports\示例仓库-report.json"
```

参数含义：

- `--path`：要分析的本地仓库目录。
- `--output`：完整 JSON 报告的保存位置；它的父目录必须已经存在并且可写。
- `--summary`：在终端显示简短摘要，不会代替 JSON 报告。
- `--protocol 2.0`：明确使用当前推荐的透明报告格式。

当前为了兼容已有调用方，不写 `--protocol` 时仍会输出旧版 v1。正式使用请明确写 `--protocol 2.0`；v1 会包含绝对路径，不适合正式验证或分享。

## 4. 如何理解结果

命令成功后会同时得到两种结果：

1. 终端摘要：方便人快速查看状态和发现项数量。
2. JSON 报告：保存在 `--output` 指定的位置，可供人检查，也可以交给支持该协议的 AI Skill 或其他工具继续解释。

报告中的每个发现项都应有明确的规则标识和证据。当前工具只检查项目结构，不会读取代码语义后给出模糊的“健康分”。

`SUCCESS` 表示本次扫描完成；`COMPLETE` 表示没有记录扫描限制，不代表代码没有缺陷。`Findings: 0` 也只表示没有命中当前已有的规则。

从 0.2.2 起，`structure.hasTests` 识别扫描范围内各子模块的测试目录（`test/tests/spec/specs`）或测试文件；这是名称启发式判断，不证明测试能通过或覆盖充分。`structure.buildFiles` 列出实际扫描到的已识别构建文件，路径相对于所选项目目录，按路径排序；同名目录不算构建文件。

构建输出、依赖和开发缓存目录（包括 `.git`、`node_modules`、`target`、`build`、`out`、`.tools` 等）不计入扫描。README、许可证和 CI 仍按仓库根目录判断，子模块有这些文件不能代替整个仓库的说明和配置。`MISSING_CI` 表示未找到受支持的配置路径，不代表运行过 CI。

协议 v2 最多接受 1024 个构建文件；超过上限时分析失败并返回非零退出码，不会静默截断列表或伪装成完整扫描。

退出码可用于判断命令是否成功：

| 退出码 | 含义 |
| --- | --- |
| `0` | 分析成功 |
| `1` | 命令参数无效 |
| `2` | 仓库目录不存在或不是有效目录 |
| `3` | 扫描失败 |
| `4` | 报告写入失败 |

## 5. 常见问题

### 中文摘要乱码，但 JSON 正常

按照“第一次运行”设置 UTF-8 后重新执行分析。用 `Get-Content -Raw -Encoding UTF8` 读取报告可以核对原文。新开旧版 PowerShell 窗口后可能需要再次设置编码。

### 提示仓库路径无效

确认 `--path` 指向一个已经存在的目录，而不是文件。路径中有空格或中文时，用双引号包住完整路径。

### 提示报告输出失败

工具不会自动创建 `--output` 的父目录。请先用 `New-Item -ItemType Directory` 创建目录，并确认当前用户有写入权限。

### 为什么没有输出 v2 报告

请检查命令中是否明确写了 `--protocol 2.0`。当前默认值仍是用于兼容旧调用方的 v1。

### Windows 显示未知发布者或 SmartScreen 提示

当前可执行文件尚未进行商业代码签名。只从项目官方 Releases 页面下载，并在运行前核对相邻的 SHA-256 文件。不要为了运行本工具而关闭 Windows 安全功能；如果来源或哈希不可信，请不要运行。

## 6. 升级、回退和卸载

- 升级：校验新版本 ZIP 后，解压到新的版本目录，不要直接覆盖旧目录。
- 回退：重新使用旧版本目录中的可执行文件，并明确指定协议版本。
- 卸载：删除你解压出的对应版本目录即可。如果你自行添加过用户级 PATH，再单独删除该 PATH 项。

删除程序目录不会删除被分析的仓库、已经生成的 JSON 报告或其他版本。

## 7. 当前能力边界

- 仅提供 Windows x64 发布包。
- 当前规则主要检查项目基础结构，不做 AST 级代码语义分析。
- 当前不包含 AI 总结、黑盒健康评分、网页操作流程或 GitHub 私有仓库授权下载；已经存在于本机的私有代码目录仍可由 CLI 本地分析。
- CLI 自身暂未限制仓库大小和执行时间；自动化调用方应在外部设置超时和资源边界。
- 报告会留在你指定的本地位置；是否把报告交给 AI 或其他服务，由你决定。

OpenPulse AI 使用压缩包中的 MIT `LICENSE`。`THIRD_PARTY_NOTICES.md` 和 `licenses/` 记录随包提供的第三方代码及其独立许可证。
