# OpenPulse Analyzer for Windows x64

This ZIP contains the local CLI. No account, website, Java, CMake or Visual Studio is needed to run it. The executable is built for Windows x64 with the MSVC runtime linked statically. Windows system DLLs are still required.

This guide accompanies the 0.2.2 patch candidate. GitHub Releases is the source of available published versions; existing 0.2.1 executables do not include the monorepo fix.

中文使用指南：发布包内的 `README.zh-CN.md`；仓库中的[中文源文档](https://github.com/Lihanc940/openpulse-ai/blob/main/docs/windows-x64-cli.zh-CN.md)。

## Check and install

Keep the ZIP and its adjacent `.zip.sha256` file together. In PowerShell, run `Get-FileHash .\openpulse-analyzer-0.2.2-windows-x64.zip -Algorithm SHA256` and compare the 64 hexadecimal characters with the first field of `.sha256`. A matching hash checks file integrity; it is not a digital signature or proof of publisher identity.

Extract the ZIP into a parent directory you choose, such as `C:\Tools`. The archive already contains an `openpulse-analyzer-0.2.2` root directory. No installer, administrator rights, registry edit or system PATH change is needed. Keep each version in its own directory.

From the extracted root directory:

```powershell
chcp 65001
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
.\bin\openpulse-analyzer.exe --help
.\bin\openpulse-analyzer.exe --protocol 2.0 --summary --path "C:\path with spaces\repository" --output "C:\reports\repository report.json"
```

The output directory must exist and be writable. The full JSON report is written to the requested file; `--summary` prints a short terminal overview. For the v0.2 validation, always use explicit `--protocol 2.0`. Without `--protocol`, the CLI still emits legacy v1 for existing callers. Explicit `--protocol 1.0` is for compatibility only: v1 includes absolute paths and is unsuitable for formal validation.

Exit codes: `0` success, `1` invalid arguments, `2` missing or invalid repository directory, `3` scan failure, `4` report output failure. The CLI does not impose a process timeout or repository size limit; callers must set those bounds externally until a separate task adds them.

The UTF-8 settings above address garbled Chinese summaries in legacy Windows PowerShell. The JSON report is UTF-8 independently of the terminal's code page.

Since 0.2.2, test indicators and recognized build files are collected across scanned modules. `hasTests` is a name-based heuristic, not a test execution result. `buildFiles` contains sorted, root-relative paths to files, not directories. Generated and dependency directories, including `.tools`, are excluded. README, license and CI checks remain rooted at the selected repository. `COMPLETE` means no scan limitations were recorded, not that the code is defect-free.

## Upgrade, roll back and remove

To upgrade, check the new ZIP hash and extract it into a new versioned directory. Keep old reports where you created them. To roll back, call the executable in the previous directory and select the protocol explicitly. To uninstall, delete only the version directory you extracted and any user-level PATH entry you added yourself. This does not remove repositories, JSON reports or other installed versions.

OpenPulse AI is licensed under the MIT License included as `LICENSE`. `THIRD_PARTY_NOTICES.md` and `licenses/` record the separate licenses of included third-party code. Only download release artifacts from the project's official GitHub Releases page, and verify the adjacent SHA-256 file before running the executable.
