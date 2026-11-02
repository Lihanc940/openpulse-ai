package io.github.lihanc940.openpulse.desktop;

import io.github.lihanc940.openpulse.integration.analyzer.AnalyzerReportReader;
import io.github.lihanc940.openpulse.integration.analyzer.model.AnalyzerReportV2;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** All imports and exports use the same strict Reader as Platform. */
public final class ReportFiles {
    private final ObjectMapper mapper;
    private final AnalyzerReportReader reader;

    public ReportFiles(ObjectMapper mapper, AnalyzerReportReader reader) {
        this.mapper = mapper;
        this.reader = reader;
    }

    public AnalyzerReportV2 open(Path path) {
        return reader.readV2(path);
    }

    public void save(AnalyzerReportV2 report, Path destination) throws IOException {
        ObjectNode json = (ObjectNode) mapper.valueToTree(report);
        // Optional protocol fields must be absent, not null. Repository findings still require location:null.
        for (String field : new String[]{"summary", "languages", "structure"}) {
            if (json.path(field).isNull()) json.remove(field);
        }
        ObjectNode repository = (ObjectNode) json.path("repository");
        if (repository.path("revision").isNull()) repository.remove("revision");
        byte[] bytes = mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(json);
        if (bytes.length > AnalyzerReportReader.MAX_REPORT_BYTES) {
            throw new IOException("报告超过 16 MiB，无法保存。");
        }
        Path target = destination.toAbsolutePath().normalize();
        Path temporary = Files.createTempFile(target.getParent(), ".openpulse-export-", ".json");
        try {
            Files.write(temporary, bytes);
            reader.readV2(temporary);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
