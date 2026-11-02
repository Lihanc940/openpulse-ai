package io.github.lihanc940.openpulse.desktop;

import io.github.lihanc940.openpulse.integration.analyzer.*;
import tools.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/** Test-only real process tree; never used by DesktopLauncher. */
public final class SlowProcessMain {
    static AnalyzerReportReader reader() {
        var mapper = new ObjectMapper();
        return new AnalyzerReportReader(mapper, new AnalyzerReportV2SchemaValidator(), new AnalyzerReportV2SemanticValidator(mapper));
    }

    static AnalyzerProcessRunner runner(Path markers, Duration timeout) {
        var properties = new AnalyzerProcessProperties("test-only", timeout, "2.0");
        AnalyzerCommandFactory command = (repository, report) -> List.of(
                Path.of(System.getProperty("java.home"), "bin/java.exe").toString(), "-cp", System.getProperty("java.class.path"),
                SlowProcessMain.class.getName(), "parent", markers.toString(), report.toString());
        return new AnalyzerProcessRunner(properties, command, reader());
    }

    public static void main(String[] arguments) throws Exception {
        if (arguments[0].equals("parent")) {
            Path marker = Path.of(arguments[1]);
            String java = Path.of(System.getProperty("java.home"), "bin/java.exe").toString();
            Process child = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"),
                    SlowProcessMain.class.getName(), "child").start();
            Files.writeString(marker.resolve("workdir.txt"), Path.of(arguments[2]).getParent().toString());
            Files.writeString(marker.resolve("parent.pid"), Long.toString(ProcessHandle.current().pid()));
            Files.writeString(marker.resolve("child.pid"), Long.toString(child.pid()));
        }
        Thread.sleep(60_000);
    }
}
