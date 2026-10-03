package io.github.lihanc940.openpulse.integration.analyzer;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties("openpulse.analyzer")
public final class AnalyzerProcessProperties {

    private final String executable;
    private final Duration timeout;
    private final AnalyzerProtocolVersion protocolVersion;

    public AnalyzerProcessProperties(String executable, Duration timeout, String protocolVersion) {
        if (executable == null || executable.isBlank()) {
            throw new IllegalArgumentException("Analyzer executable must not be blank");
        }
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("Analyzer timeout must be positive");
        }
        this.executable = executable;
        this.timeout = timeout;
        this.protocolVersion = AnalyzerProtocolVersion.fromCliValue(protocolVersion);
    }

    public String executable() {
        return executable;
    }

    public Duration timeout() {
        return timeout;
    }

    public AnalyzerProtocolVersion protocolVersion() {
        return protocolVersion;
    }
}
