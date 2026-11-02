package io.github.lihanc940.openpulse.integration.analyzer;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

/** Only Platform knows Spring; the shared client can be constructed with ordinary Java. */
@Configuration(proxyBeanMethods = false)
public class AnalyzerClientConfiguration {

    @Bean
    AnalyzerProcessProperties analyzerProcessProperties(AnalyzerPlatformProperties properties) {
        return properties.toClientProperties();
    }

    @Bean
    AnalyzerCommandFactory analyzerCommandFactory(AnalyzerProcessProperties properties) {
        return new ConfiguredAnalyzerCommandFactory(properties);
    }

    @Bean
    AnalyzerReportV2SchemaValidator analyzerReportV2SchemaValidator() {
        return new AnalyzerReportV2SchemaValidator();
    }

    @Bean
    AnalyzerReportV2SemanticValidator analyzerReportV2SemanticValidator(ObjectMapper mapper) {
        return new AnalyzerReportV2SemanticValidator(mapper);
    }

    @Bean
    AnalyzerReportReader analyzerReportReader(ObjectMapper mapper,
            AnalyzerReportV2SchemaValidator schema, AnalyzerReportV2SemanticValidator semantics) {
        return new AnalyzerReportReader(mapper, schema, semantics);
    }

    @Bean
    AnalyzerProcessRunner analyzerProcessRunner(AnalyzerProcessProperties properties,
            AnalyzerCommandFactory commandFactory, AnalyzerReportReader reader) {
        return new AnalyzerProcessRunner(properties, commandFactory, reader);
    }
}
