package io.github.lihanc940.openpulse.integration.analyzer;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties("openpulse.analyzer")
public record AnalyzerProcessProperties(String executable, Duration timeout) {

    public AnalyzerProcessProperties {
        if (executable == null || executable.isBlank()) {
            throw new IllegalArgumentException("Analyzer executable must not be blank");
        }
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("Analyzer timeout must be positive");
        }
    }
}
