package io.github.lihanc940.openpulse.integration.analyzer;

import io.github.lihanc940.openpulse.integration.analyzer.model.AnalyzerReport;
import io.github.lihanc940.openpulse.integration.analyzer.model.AnalyzerReportDocument;
import io.github.lihanc940.openpulse.integration.analyzer.model.AnalyzerReportV2;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectReader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;

@Component
public class AnalyzerReportReader {

    public static final long MAX_REPORT_BYTES = 16L * 1024 * 1024;

    private static final String PROTOCOL_V1 = "1.0";
    private static final String PROTOCOL_V2 = "2.0";

    private final ObjectReader treeReader;
    private final ObjectReader v1Reader;
    private final ObjectReader v2Reader;
    private final AnalyzerReportV2SchemaValidator schemaValidator;
    private final AnalyzerReportV2SemanticValidator semanticValidator;

    public AnalyzerReportReader(
            ObjectMapper objectMapper,
            AnalyzerReportV2SchemaValidator schemaValidator,
            AnalyzerReportV2SemanticValidator semanticValidator
    ) {
        treeReader = objectMapper.reader()
                .with(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .with(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        v1Reader = objectMapper.readerFor(AnalyzerReport.class);
        v2Reader = objectMapper.readerFor(AnalyzerReportV2.class)
                .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .with(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES);
        this.schemaValidator = schemaValidator;
        this.semanticValidator = semanticValidator;
    }

    public AnalyzerReportDocument read(Path reportPath) {
        byte[] reportBytes = readBoundedFile(reportPath);
        JsonNode root = readRoot(reportBytes);
        String protocolVersion = readProtocolVersion(root);
        return switch (protocolVersion) {
            case PROTOCOL_V1 -> readV1(root);
            case PROTOCOL_V2 -> readV2(root);
            default -> throw new AnalyzerReportReadException(
                    AnalyzerReportReadFailure.UNSUPPORTED_PROTOCOL_VERSION,
                    "Unsupported analyzer report protocol version"
            );
        };
    }

    public AnalyzerReport readV1(Path reportPath) {
        AnalyzerReportDocument report = read(reportPath);
        if (report instanceof AnalyzerReport v1Report) {
            return v1Report;
        }
        throw new AnalyzerReportReadException(
                AnalyzerReportReadFailure.UNSUPPORTED_PROTOCOL_VERSION,
                "Analyzer report protocol is not supported by the v1 runtime path"
        );
    }

    public AnalyzerReportV2 readV2(Path reportPath) {
        AnalyzerReportDocument report = read(reportPath);
        if (report instanceof AnalyzerReportV2 v2Report) {
            return v2Report;
        }
        throw new AnalyzerReportReadException(
                AnalyzerReportReadFailure.UNSUPPORTED_PROTOCOL_VERSION,
                "Analyzer report protocol is not version 2.0"
        );
    }

    private byte[] readBoundedFile(Path reportPath) {
        if (reportPath == null) {
            throw invalidFile("Analyzer report path must not be null");
        }
        if (!Files.exists(reportPath, LinkOption.NOFOLLOW_LINKS)) {
            throw invalidFile("Analyzer report file does not exist");
        }
        if (!Files.isRegularFile(reportPath, LinkOption.NOFOLLOW_LINKS)) {
            throw invalidFile("Analyzer report path is not a regular file");
        }
        try {
            long size = Files.size(reportPath);
            if (size > MAX_REPORT_BYTES) {
                throw new AnalyzerReportReadException(
                        AnalyzerReportReadFailure.FILE_TOO_LARGE,
                        "Analyzer report exceeds the file size limit"
                );
            }
            byte[] bytes = Files.readAllBytes(reportPath);
            if (bytes.length > MAX_REPORT_BYTES) {
                throw new AnalyzerReportReadException(
                        AnalyzerReportReadFailure.FILE_TOO_LARGE,
                        "Analyzer report exceeds the file size limit"
                );
            }
            return bytes;
        } catch (IOException exception) {
            throw new AnalyzerReportReadException(
                    AnalyzerReportReadFailure.INVALID_FILE,
                    "Analyzer report file could not be read",
                    exception
            );
        }
    }

    private JsonNode readRoot(byte[] reportBytes) {
        JsonNode root;
        try {
            root = treeReader.readTree(reportBytes);
        } catch (JacksonException exception) {
            throw new AnalyzerReportReadException(
                    AnalyzerReportReadFailure.MALFORMED_JSON,
                    "Analyzer report JSON is malformed or contains duplicate keys",
                    exception
            );
        }
        if (root == null || !root.isObject()) {
            throw new AnalyzerReportReadException(
                    AnalyzerReportReadFailure.INVALID_ROOT,
                    "Analyzer report root must be a JSON object"
            );
        }
        return root;
    }

    private String readProtocolVersion(JsonNode root) {
        JsonNode version = root.get("protocolVersion");
        if (version == null || version.isNull() || !version.isString()) {
            throw new AnalyzerReportReadException(
                    AnalyzerReportReadFailure.INVALID_PROTOCOL_VERSION,
                    "Analyzer report protocolVersion must be a non-null string"
            );
        }
        return version.asString();
    }

    private AnalyzerReport readV1(JsonNode root) {
        AnalyzerReport report;
        try {
            report = v1Reader.readValue(root);
        } catch (JacksonException exception) {
            throw new AnalyzerReportReadException(
                    AnalyzerReportReadFailure.MODEL_MAPPING_FAILED,
                    "Analyzer report version 1.0 cannot be mapped",
                    exception
            );
        }
        validateV1(report);
        return report;
    }

    private AnalyzerReportV2 readV2(JsonNode root) {
        schemaValidator.validate(root);
        AnalyzerReportV2 report;
        try {
            report = v2Reader.readValue(root);
        } catch (JacksonException exception) {
            throw new AnalyzerReportReadException(
                    AnalyzerReportReadFailure.MODEL_MAPPING_FAILED,
                    "Analyzer report version 2.0 cannot be mapped to the strict model",
                    exception
            );
        }
        semanticValidator.validate(report);
        return report;
    }

    private void validateV1(AnalyzerReport report) {
        requireV1NonNull(report, "report");
        if (!PROTOCOL_V1.equals(report.protocolVersion())) {
            throw new AnalyzerReportReadException(
                    AnalyzerReportReadFailure.UNSUPPORTED_PROTOCOL_VERSION,
                    "Unsupported analyzer report protocol version"
            );
        }
        if (report.taskId() == null || report.taskId().isBlank()) {
            throw new AnalyzerReportReadException(
                    AnalyzerReportReadFailure.SEMANTIC_VIOLATION,
                    "Analyzer report taskId must not be blank"
            );
        }

        requireV1NonNull(report.status(), "status");
        requireV1NonNull(report.repository(), "repository");
        requireV1NonNull(report.summary(), "summary");
        requireV1NonNull(report.languages(), "languages");
        requireV1NonNull(report.structure(), "structure");
        requireV1NonNull(report.quality(), "quality");
        requireV1NonNull(report.risks(), "risks");
        requireV1NonNull(report.dependencies(), "dependencies");
        requireV1NonNull(report.generatedAt(), "generatedAt");

        requireV1NonNull(report.structure().buildFiles(), "structure.buildFiles");
        requireV1NonNull(report.dependencies().nodes(), "dependencies.nodes");
        requireV1NonNull(report.dependencies().edges(), "dependencies.edges");
        requireV1RiskLists(report.risks());
    }

    private void requireV1RiskLists(List<AnalyzerReport.Risk> risks) {
        for (int index = 0; index < risks.size(); index++) {
            AnalyzerReport.Risk risk = risks.get(index);
            requireV1NonNull(risk, "risks[" + index + "]");
            requireV1NonNull(risk.evidence(), "risks[" + index + "].evidence");
        }
    }

    private void requireV1NonNull(Object value, String fieldName) {
        if (value == null) {
            throw new AnalyzerReportReadException(
                    AnalyzerReportReadFailure.SEMANTIC_VIOLATION,
                    "Analyzer report required version 1.0 field is missing: " + fieldName
            );
        }
    }

    private AnalyzerReportReadException invalidFile(String message) {
        return new AnalyzerReportReadException(AnalyzerReportReadFailure.INVALID_FILE, message);
    }
}
