package io.github.lihanc940.openpulse.integration.analyzer;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public final class FakeAnalyzerMain {

    private FakeAnalyzerMain() {
    }

    public static void main(String[] args) throws Exception {
        String scenario = args[0];
        Path outputPath = argumentPath(args, "--output");
        System.out.println("fake analyzer stdout");
        System.err.println("fake analyzer stderr");

        switch (scenario) {
            case "success" -> writeReport(outputPath);
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

    private static void writeReport(Path outputPath) throws IOException {
        try (InputStream input = FakeAnalyzerMain.class.getResourceAsStream(
                "/contracts/analyzer-report-v1.sample.json"
        )) {
            if (input == null) {
                throw new IOException("Analyzer report fixture is missing");
            }
            Files.copy(input, outputPath);
        }
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
