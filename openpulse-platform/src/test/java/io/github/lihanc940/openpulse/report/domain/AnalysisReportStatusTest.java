package io.github.lihanc940.openpulse.report.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AnalysisReportStatusTest {

    @Test
    void onlySuccessAndPartialSuccessAreUsableResults() {
        assertThat(AnalysisReportStatus.SUCCESS.isUsableResult()).isTrue();
        assertThat(AnalysisReportStatus.PARTIAL_SUCCESS.isUsableResult()).isTrue();
        assertThat(AnalysisReportStatus.FAILED.isUsableResult()).isFalse();
    }
}
