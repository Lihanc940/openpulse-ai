# C++ 分析器 JSON 协议 v1

这份文档定义 Java 后端和 C++ 分析引擎之间怎么通信。

## 调用方式

MVP 使用命令行进程调用。

```bash
openpulse-analyzer --path ./repositories/demo-project --output ./reports/report.json
```

参数含义：

- `--path`：待分析的仓库本地路径。
- `--output`：分析结果 JSON 输出路径。

Java 后端使用 `ProcessBuilder` 启动命令，等待进程结束后读取 JSON 文件。

## 退出码约定

```text
0  分析成功
1  参数错误
2  路径不存在
3  扫描过程失败
4  JSON 输出失败
```

即使分析成功但发现代码风险，退出码也应该是 `0`。风险属于分析结果，不属于程序执行失败。

## JSON 顶层结构

```json
{
  "protocolVersion": "1.0",
  "taskId": "task_20260727_001",
  "status": "SUCCESS",
  "repository": {
    "path": "./repositories/demo-project",
    "name": "demo-project"
  },
  "summary": {
    "totalFiles": 186,
    "sourceFiles": 94,
    "documentFiles": 3,
    "configFiles": 8,
    "testFiles": 13,
    "totalLines": 23841,
    "codeLines": 17640,
    "commentLines": 3280,
    "blankLines": 2921
  },
  "languages": [
    {
      "name": "Java",
      "files": 52,
      "lines": 12000
    }
  ],
  "structure": {
    "hasReadme": true,
    "hasLicense": false,
    "hasContributing": false,
    "hasChangelog": false,
    "hasCi": true,
    "hasTests": true,
    "hasDockerfile": false,
    "buildFiles": ["pom.xml"]
  },
  "quality": {
    "complexityScore": 72,
    "maintainabilityScore": 81,
    "documentationScore": 65,
    "testScore": 58
  },
  "risks": [
    {
      "ruleId": "LONG_FUNCTION",
      "type": "CODE_SMELL",
      "level": "HIGH",
      "file": "src/UserService.java",
      "line": 84,
      "message": "函数长度为 126 行，建议拆分职责。",
      "evidence": {
        "functionName": "registerUser",
        "functionLines": 126,
        "threshold": 80
      }
    }
  ],
  "dependencies": {
    "nodes": [],
    "edges": []
  },
  "generatedAt": "2026-07-27T22:50:00+08:00"
}
```

## 状态字段

`status` 可选值：

- `SUCCESS`：扫描成功。
- `FAILED`：扫描失败。
- `PARTIAL_SUCCESS`：部分文件无法读取，但报告仍可用。

## 风险等级

`level` 可选值：

- `LOW`
- `MEDIUM`
- `HIGH`
- `CRITICAL`

MVP 阶段一般只需要 `LOW`、`MEDIUM`、`HIGH`。

## 规则 ID 建议

第一版先支持这些规则：

```text
LONG_FILE
LONG_FUNCTION
TOO_MANY_PARAMETERS
DEEP_NESTING
TOO_MANY_TODO
DANGEROUS_FUNCTION
DUPLICATE_CODE
MISSING_README
MISSING_LICENSE
MISSING_CI
MISSING_TESTS
```

## Java 解析原则

Java 不应该依赖 C++ 内部实现，只依赖这份 JSON 协议。

也就是说，只要 C++ 输出字段稳定，C++ 内部用正则、手写解析、Tree-sitter 或 Clang AST 都不影响 Java。

