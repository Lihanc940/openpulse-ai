import io.github.lihanc940.openpulse.desktop.ReportFiles;
import io.github.lihanc940.openpulse.integration.analyzer.*;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/** Maintainer-only probe, compiled outside the image; never shipped in the candidate. */
public final class PortableReportCheck {
    public static void main(String[] args) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        AnalyzerReportReader reader = new AnalyzerReportReader(mapper, new AnalyzerReportV2SchemaValidator(),
                new AnalyzerReportV2SemanticValidator(mapper));
        ReportFiles reports = new ReportFiles(mapper, reader);
        if (args[0].equals("compare")) {
            reports.open(Path.of(args[1]));
            reports.open(Path.of(args[2]));
            ObjectNode left = (ObjectNode) mapper.readTree(Files.readAllBytes(Path.of(args[1])));
            ObjectNode right = (ObjectNode) mapper.readTree(Files.readAllBytes(Path.of(args[2])));
            for (String field : new String[]{"taskId", "generatedAt"}) {
                left.remove(field);
                right.remove(field);
            }
            if (!left.equals(right)) throw new AssertionError("Report facts differ");
            System.out.println("PASS: strict v2 Reader; facts equal except taskId/generatedAt");
        } else if (args[0].equals("run")) {
            Path image = Path.of(args[1]).toRealPath();
            if (!Path.of(System.getProperty("java.home")).toRealPath().equals(image.resolve("runtime"))) {
                throw new AssertionError("External Java runtime used");
            }
            var properties = new AnalyzerProcessProperties(image.resolve("app/bin/openpulse-analyzer.exe").toString(),
                    Duration.ofSeconds(30), "2.0");
            var runner = new AnalyzerProcessRunner(properties, new ConfiguredAnalyzerCommandFactory(properties), reader);
            var result = (io.github.lihanc940.openpulse.integration.analyzer.model.AnalyzerReportV2) runner.analyze(Path.of(args[2]));
            Path exported = Path.of(args[3]);
            reports.save(result, exported);
            reports.open(exported);
            Path invalid = exported.resolveSibling("invalid-unknown-field.json");
            ObjectNode changed = (ObjectNode) mapper.readTree(Files.readAllBytes(exported));
            changed.put("unexpected", true);
            Files.write(invalid, mapper.writeValueAsBytes(changed));
            try {
                reports.open(invalid);
                throw new AssertionError("Unknown field accepted");
            } catch (AnalyzerReportReadException expected) {
                System.out.println("PASS: invalid report rejected");
            }
            System.out.println("PASS: bundled runtime + real analyzer + shared runner + strict import/export");
        } else throw new IllegalArgumentException("run or compare required");
    }
}
