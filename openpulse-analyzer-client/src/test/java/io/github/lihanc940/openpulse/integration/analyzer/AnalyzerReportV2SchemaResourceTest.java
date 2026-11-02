package io.github.lihanc940.openpulse.integration.analyzer;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class AnalyzerReportV2SchemaResourceTest {

    @Test
    void packagedSchemaIsByteForByteEqualToTheFormalRepositorySchema() throws IOException {
        byte[] formal = Files.readAllBytes(
                Path.of("..", "docs", "protocol", "analyzer-report-v2.schema.json"));
        byte[] packaged;
        try (InputStream input = AnalyzerReportV2SchemaResourceTest.class.getResourceAsStream(
                AnalyzerReportV2SchemaValidator.SCHEMA_RESOURCE)) {
            assertThat(input).isNotNull();
            packaged = input.readAllBytes();
        }

        assertThat(packaged).containsExactly(formal);
    }
}
