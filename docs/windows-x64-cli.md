# OpenPulse Analyzer for Windows x64

This ZIP contains the local CLI. No account, website, Java, CMake or Visual Studio is needed to run it. The executable is built for Windows x64 with the MSVC runtime linked statically. Windows system DLLs are still required.

## Check and install

Keep the ZIP and its adjacent `.zip.sha256` file together. In PowerShell, run `Get-FileHash .\openpulse-analyzer-0.2.0-windows-x64.zip -Algorithm SHA256` and compare the 64 hexadecimal characters with the first field of `.sha256`. A matching hash checks file integrity; it is not a digital signature or proof of publisher identity.

Extract the ZIP into a versioned directory you choose, such as `C:\Tools\openpulse-analyzer-0.2.0`. The archive already contains an `openpulse-analyzer-0.2.0` root directory. No installer, administrator rights, registry edit or system PATH change is needed. Keep each version in its own directory.

From the extracted root directory:

```powershell
.\bin\openpulse-analyzer.exe --help
.\bin\openpulse-analyzer.exe --protocol 2.0 --summary --path "C:\path with spaces\repository" --output "C:\reports\repository report.json"
```

The output directory must exist and be writable. The full JSON report is written to the requested file; `--summary` prints a short terminal overview. For the v0.2 validation, always use explicit `--protocol 2.0`. Without `--protocol`, the CLI still emits legacy v1 for existing callers. Explicit `--protocol 1.0` is for compatibility only: v1 includes absolute paths and is unsuitable for formal validation.

Exit codes: `0` success, `1` invalid arguments, `2` missing or invalid repository directory, `3` scan failure, `4` report output failure. The CLI does not impose a process timeout or repository size limit; callers must set those bounds externally until a separate task adds them.

## Upgrade, roll back and remove

To upgrade, check the new ZIP hash and extract it into a new versioned directory. Keep old reports where you created them. To roll back, call the executable in the previous directory and select the protocol explicitly. To uninstall, delete only the version directory you extracted and any user-level PATH entry you added yourself. This does not remove repositories, JSON reports or other installed versions.

OpenPulse AI is licensed under the MIT License included as `LICENSE`. `THIRD_PARTY_NOTICES.md` and `licenses/` record the separate licenses of included third-party code. Only download release artifacts from the project's official GitHub Releases page, and verify the adjacent SHA-256 file before running the executable.
