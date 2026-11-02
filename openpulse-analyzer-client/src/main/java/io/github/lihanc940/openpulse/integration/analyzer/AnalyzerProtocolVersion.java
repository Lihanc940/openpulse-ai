package io.github.lihanc940.openpulse.integration.analyzer;

import java.util.Arrays;

public enum AnalyzerProtocolVersion {

    V1_0("1.0"),
    V2_0("2.0");

    private final String cliValue;

    AnalyzerProtocolVersion(String cliValue) {
        this.cliValue = cliValue;
    }

    public String cliValue() {
        return cliValue;
    }

    public static AnalyzerProtocolVersion fromCliValue(String value) {
        return Arrays.stream(values())
                .filter(candidate -> candidate.cliValue.equals(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Analyzer protocol version must be exactly 1.0 or 2.0"
                ));
    }
}
