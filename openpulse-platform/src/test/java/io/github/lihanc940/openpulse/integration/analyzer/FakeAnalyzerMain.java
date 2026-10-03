package io.github.lihanc940.openpulse.integration.analyzer;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

public final class FakeAnalyzerMain {

    private FakeAnalyzerMain() {
    }

    public static void main(String[] args) throws Exception {
        String scenario = args[0];
        Path outputPath = argumentPath(args, "--output");
        System.out.println("fake analyzer stdout");
        System.err.println("fake analyzer stderr");

        switch (scenario) {
            case "success", "success-v1" -> writeV1Report(outputPath);
            case "success-v2" -> copyV2Example(outputPath, "analyzer-report-v2.success.sample.json");
            case "partial-v2" -> copyV2Example(outputPath, "analyzer-report-v2.partial-success.sample.json");
            case "failed-v2" -> writeFailedV2Report(outputPath);
            case "invalid-v2-schema" -> writeInvalidV2Report(outputPath);
            case "invalid-v2-semantic" -> writeModifiedV2Report(
                    outputPath,
                    report -> report.replace(
                            "sha256:b2435ebfd9d97636201cc988136190a1c7ade7b21ffb8d5bcb90ba77400c3711",
                            "sha256:" + "0".repeat(64)
                    )
            );
            case "invalid-v2-catalog" -> writeModifiedV2Report(
                    outputPath,
                    report -> report.replace("\"id\": \"openpulse-default\"", "\"id\": \"unknown-catalog\"")
            );
            case "missing-report" -> {
                // Exit successfully without creating the requested report.
            }
            case "invalid-report" -> Files.writeString(
                    outputPath,
                    "{not-valid-json",
                    StandardOpenOption.CREATE_NEW
            );
            case "timeout" -> Thread.sleep(30_000);
            case "timeout-with-descendant" -> {
                Process descendant = startSleepingDescendant(outputPath);
                System.out.println("descendant-pid=" + descendant.pid());
                Thread.sleep(30_000);
            }
            case "timeout-descendant" -> Thread.sleep(30_000);
            case "exit-1" -> System.exit(1);
            case "exit-2" -> System.exit(2);
            case "exit-3" -> System.exit(3);
            case "exit-4" -> System.exit(4);
            case "exit-unknown" -> System.exit(17);
            case "large-output" -> {
                System.out.print("stdout-start-" + "o".repeat(100_000) + "-stdout-end");
                System.err.print("stderr-start-" + "e".repeat(100_000) + "-stderr-end");
                System.exit(3);
            }
            default -> throw new IllegalArgumentException("Unknown fake analyzer scenario: " + scenario);
        }
    }

    private static Path argumentPath(String[] args, String name) {
        for (int index = 1; index < args.length - 1; index++) {
            if (name.equals(args[index])) {
                return Path.of(args[index + 1]);
            }
        }
        throw new IllegalArgumentException("Missing argument: " + name);
    }

    private static void writeV1Report(Path outputPath) throws IOException {
        try (InputStream input = FakeAnalyzerMain.class.getResourceAsStream(
                "/contracts/analyzer-report-v1.sample.json"
        )) {
            if (input == null) {
                throw new IOException("Analyzer report fixture is missing");
            }
            Files.copy(input, outputPath);
        }
    }

    private static void copyV2Example(Path outputPath, String fileName) throws IOException {
        Files.copy(findV2Example(fileName), outputPath);
    }

    private static void writeInvalidV2Report(Path outputPath) throws IOException {
        writeModifiedV2Report(
                outputPath,
                report -> report.replaceFirst("\\{", "{\"unexpected\":true,")
        );
    }

    private static void writeModifiedV2Report(
            Path outputPath,
            java.util.function.UnaryOperator<String> modifier
    ) throws IOException {
        String validReport = Files.readString(findV2Example("analyzer-report-v2.success.sample.json"));
        Files.writeString(outputPath, modifier.apply(validReport), StandardOpenOption.CREATE_NEW);
    }

    private static Path findV2Example(String fileName) throws IOException {
        for (Path candidate : List.of(
                Path.of("..", "docs", "examples", fileName),
                Path.of("docs", "examples", fileName)
        )) {
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        throw new IOException("Analyzer report v2 example is missing");
    }

    private static void writeFailedV2Report(Path outputPath) throws IOException {
        Files.writeString(outputPath, """
                {
                  "protocolVersion":"2.0",
                  "taskId":"task_runner_failed_001",
                  "analyzer":{"name":"openpulse-analyzer","version":"0.2.0"},
                  "ruleSet":{"id":"openpulse-default","version":"0.2.0"},
                  "status":"FAILED",
                  "reviewability":"NOT_USABLE",
                  "repository":{"name":"fictional-workspace","root":"."},
                  "findings":[],
                  "limitations":[{
                    "kind":"SCAN_FAILED",
                    "scope":"REPOSITORY",
                    "reason":"TRAVERSAL_ERROR",
                    "message":"Repository traversal did not produce a reviewable report."
                  }],
                  "generatedAt":"2026-09-17T08:00:00+08:00"
                }
                """, StandardOpenOption.CREATE_NEW);
    }

    private static Process startSleepingDescendant(Path outputPath) throws IOException {
        String executableName = System.getProperty("os.name").toLowerCase().contains("win")
                ? "java.exe"
                : "java";
        String javaExecutable = Path.of(System.getProperty("java.home"), "bin", executableName).toString();
        return new ProcessBuilder(
                javaExecutable,
                "-cp",
                System.getProperty("java.class.path"),
                FakeAnalyzerMain.class.getName(),
                "timeout-descendant",
                "--output",
                outputPath.toString()
        ).start();
    }
}
