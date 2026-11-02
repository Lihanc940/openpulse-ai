package io.github.lihanc940.openpulse.integration.analyzer;

import tools.jackson.databind.ObjectMapper;

final class ClientTestSupport {

    private ClientTestSupport() {
    }

    static AnalyzerReportReader reader() {
        ObjectMapper mapper = new ObjectMapper();
        return new AnalyzerReportReader(mapper, new AnalyzerReportV2SchemaValidator(),
                new AnalyzerReportV2SemanticValidator(mapper));
    }
}
