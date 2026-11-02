# OpenPulse AI 第十五步：真实试用后的多模块识别修复

## 任务定位

- 日期：2026-11-02。
- 分支：`codex/analyzer-monorepo-fix`。
- 起点：已发布的 0.2.1，`main` 提交 `c9e2005`。
- 补丁候选版本：0.2.2。
- 状态：本地实现与验收通过，尚未合并或公开发布。
- 下一项：独立规划 Windows 桌面分析界面，降低命令行操作门槛。

## 真实问题与根因

用户从公开下载包运行本项目后，报告同时给出 `summary.testFiles: 51` 和 `structure.hasTests: false`，`buildFiles` 也为空。根因是递归统计与根级结构检查的范围不一致：Java 的 `src/test`、子模块的 `pom.xml` 和 `CMakeLists.txt` 不在仓库根目录。

旧版 PowerShell 同时出现中文摘要乱码，切换当前终端 UTF-8 后显示正常，JSON 本身有效。

## 本次修复边界

1. 复用单次递归遍历，收集各层实际构建文件并输出排序后的 UTF-8 相对路径。
2. 扫描范围内存在 `test/tests/spec/specs` 目录或命名符合现有测试启发式的文件时，`hasTests` 为真；空测试目录也可识别。
3. 测试文件判断仅使用相对仓库根目录的路径，不能受仓库外层 `tests` 目录影响。
4. `.tools` 与既有构建/依赖目录一起排除，避免本机开发缓存污染结果。
5. 用文件系统路径打开文件，覆盖中文和空格模块路径。
6. v2 构建文件超过 Schema 已有的 1024 项上限时拒绝构造，返回失败，不能静默截断。
7. 更新中文使用指南中的终端打开、UTF-8、报告含义和结构识别范围。

README、许可证、CI 与 Dockerfile 的既有根级检查不扩展到子模块。三个规则编号、候选证据路径、规则版本和 findingId 不变。协议字段与默认协议不变，版本更新只表示扫描实现补丁。

中文终端的交付方式是明确记录 UTF-8 设置，不宣称 exe 能自动修复所有终端编码。下一步桌面客户端读取 v2 JSON 展示中文，不解析终端摘要。

## 为什么不同时做桌面界面

先保证同一份扫描事实正确并可复现，再由桌面端展示。界面单独按任务 16 验收；两项通过独立提交记录，便于评审和回退。

## 变更文件边界

- C++ `Analyzer` 的事实收集、结构检查及必要的 v2 构造边界。
- 原有自包含测试中的回归场景，以及发布包验收脚本。
- 版本注入、版本断言和联合验收 golden 的分析器版本与 SHA-256。
- 中英文 CLI 使用说明、维护者构建示例及本任务记录。

不修改 Java 业务实现、Schema、规则目录、正式价值实验数据。生成 exe、ZIP 和临时报告只放在忽略目录。用户已有未跟踪文档保持原样。

## 已有验收证据

- 修复前新增回归用例真实产生 10 个失败断言；旧实现未被当作通过。
- 修复后覆盖多模块、命名误判、生成目录排除、同名目录、空测试目录、中文路径、v1/v2 结构一致性。
- 真实重新扫描本项目后，识别出 `openpulse-platform/pom.xml` 和 `openpulse-analyzer/CMakeLists.txt`，`hasTests` 为真，测试文件仍为 51。
- 扫描范围包含文档中的虚构 fixture，因此构建文件列表也会包含 fixture 的构建文件；这不等于已运行这些构建。
- 固定 golden 内容只更新 `analyzer.version`；真实 CLI/生产 builder 联合验收必须确认这些快照与当前版本一致。

## 完成标准

1. 原问题和路径误判具有修复前失败、修复后通过的回归证据。
2. C++ 全部 CTest 通过，v1 默认行为和显式 v2 继续兼容。
3. Java/C++ 联合验收 C01-C09、N01-N10 和确定性哈希通过。
4. 构建 0.2.2 候选 ZIP，并从包内 exe 验证多模块路径、排除边界和 Java Reader。
5. 对当前项目进行实际扫描，验证测试/构建识别与报告原文。
6. 文档链接、编码和 `git diff --check` 通过，提交中没有生成产物或用户个人文档。
7. 本地提交、PR、合并、发布状态分别记录；候选包不自动覆盖公开 0.2.1。

## 验证入口

```powershell
# 从仓库根目录，在已有 Windows C++ 工具环境运行
cmake -S openpulse-analyzer -B .tools/task15-build -G "Visual Studio 17 2022" -A x64 -DBUILD_TESTS=ON
cmake --build .tools/task15-build --config Release --parallel
ctest --test-dir .tools/task15-build -C Release --output-on-failure
.\scripts\verify-analyzer-report-v2-contract.ps1
.\scripts\build-windows-x64-package.ps1
.\scripts\verify-windows-x64-package.ps1 -PackagePath .\openpulse-analyzer\out\windows-x64\openpulse-analyzer-0.2.2-windows-x64.zip -JavaHome "你的 JDK 21 目录"
git diff --check
```

本机若已有干净的 nlohmann/json v3.11.3，可以按分发文档显式传入其目录，由打包脚本核对 tag 和工作区；不要关闭校验或修改全局安全配置。

## 本地验收结果

- C++：完整 CTest `20/20`，失败 `0`。
- Java/C++ 联合契约：C01-C09 `9/9`；N01-N10 `10/10` 按预期拒绝，四份确定性 golden 与真实生产输出一致。
- 发布包：构建成功；解压运行、默认 v1、显式 v2、空格路径、多模块测试/构建识别及 `.tools` 排除、错误退出码、运行时依赖和 Java Reader 验收通过。
- Windows 运行时依赖：仅 `KERNEL32.dll`。
- 候选文件：`openpulse-analyzer-0.2.2-windows-x64.zip`，294125 字节。
- SHA-256：`3a10dbc5ca4c3fecef0ad5048eb96ef542a1dd748b83d9860dcaf11bc4bfd32d`。
- 原问题实测：`hasTests: true`，`testFiles: 51`，构建列表包含 Java 与 C++ 子模块。
- 文档链接及 `git diff --check` 通过。
- 本轮未运行全量 Java/MySQL 集成测试；Java 只执行本次相关联合契约及包级 Reader 用例，不宣称全量平台验收。
- 未 push、未创建 PR、未合并、未公开发布；0.2.1 下载包保持原状。
