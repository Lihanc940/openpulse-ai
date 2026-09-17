package io.github.lihanc940.openpulse.integration.analyzer;

import com.networknt.schema.Error;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SchemaRegistryConfig;
import com.networknt.schema.SpecificationVersion;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

@Component
public class AnalyzerReportV2SchemaValidator {

    public static final String SCHEMA_RESOURCE = "/schemas/analyzer-report-v2.schema.json";

    private final Schema schema;

    public AnalyzerReportV2SchemaValidator() {
        SchemaRegistryConfig config = SchemaRegistryConfig.builder()
                .formatAssertionsEnabled(true)
                .build();
        SchemaRegistry registry = SchemaRegistry.withDefaultDialect(
                SpecificationVersion.DRAFT_2020_12,
                builder -> builder.schemaRegistryConfig(config)
        );
        try (InputStream input = AnalyzerReportV2SchemaValidator.class.getResourceAsStream(SCHEMA_RESOURCE)) {
            if (input == null) {
                throw new IllegalStateException("Analyzer report v2 Schema resource is missing");
            }
            schema = registry.getSchema(input, InputFormat.JSON);
        } catch (IOException exception) {
            throw new IllegalStateException("Analyzer report v2 Schema resource could not be loaded", exception);
        }
    }

    public void validate(JsonNode reportNode) {
        List<Error> errors = schema.validate(reportNode);
        if (!errors.isEmpty()) {
            String keyword = errors.getFirst().getKeyword();
            String summary = keyword == null || keyword.isBlank() ? "unknown" : keyword;
            throw new AnalyzerReportReadException(
                    AnalyzerReportReadFailure.SCHEMA_VIOLATION,
                    "Analyzer report violates protocol 2.0 Schema (category=" + summary
                            + ", count=" + errors.size() + ")"
            );
        }
    }
}
