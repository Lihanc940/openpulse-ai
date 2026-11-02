package io.github.lihanc940.openpulse.integration.analyzer;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** Spring configuration binding stays in the team service. */
@ConfigurationProperties("openpulse.analyzer")
public record AnalyzerPlatformProperties(String executable, Duration timeout, String protocolVersion) {

    public AnalyzerPlatformProperties {
        // Preserve fail-fast validation during Spring configuration binding.
        new AnalyzerProcessProperties(executable, timeout, protocolVersion);
    }

    public AnalyzerProcessProperties toClientProperties() {
        return new AnalyzerProcessProperties(executable, timeout, protocolVersion);
    }
}
