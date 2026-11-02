package io.github.lihanc940.openpulse.desktop;

import io.github.lihanc940.openpulse.integration.analyzer.*;
import io.github.lihanc940.openpulse.integration.analyzer.model.AnalyzerReportV2;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

class DesktopAnalysisServiceTest {
    @TempDir Path temporary;
    private final ReportFiles files = new ReportFiles(ReportFilesTest.MAPPER, ReportFilesTest.reader());
    private AnalyzerReportV2 success() { return files.open(ReportFilesTest.fixture("success")); }
    private void idle(DesktopAnalysisService service) throws Exception { service.whenIdle().get(8, TimeUnit.SECONDS); }

    @Test
    void missingDirectoryIsRejectedInBackgroundAndBlankPathResetsReadyState() throws Exception {
        try (var service = new DesktopAnalysisService(path -> { throw new AssertionError("Must not run"); },
                files, Runnable::run, ignored -> { })) {
            assertThat(service.analyze(temporary.resolve("missing").toString())).isTrue();
            idle(service);
            assertThat(service.snapshot().state()).isEqualTo(DesktopAnalysisService.State.FAILED);
            assertThat(service.snapshot().message()).contains("路径无效");
            service.ready("");
            assertThat(service.snapshot().state()).isEqualTo(DesktopAnalysisService.State.EMPTY);
        }
    }

    @Test
    void cancellationDoesNotHideCleanupFailure() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        try (var service = new DesktopAnalysisService(path -> {
            started.countDown();
            try { new CountDownLatch(1).await(); }
            catch (InterruptedException interrupted) {
                var failure = new AnalyzerExecutionException(AnalyzerExecutionFailure.INTERRUPTED, "interrupted");
                failure.addSuppressed(new java.io.IOException("cleanup failed"));
                throw failure;
            }
            return success();
        }, files, Runnable::run, ignored -> { })) {
            service.analyze(temporary.toString());
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            service.cancel(); idle(service);
            assertThat(service.snapshot().state()).isEqualTo(DesktopAnalysisService.State.FAILED);
            assertThat(service.snapshot().message()).contains("清理失败");
        }
    }

    @Test
    void cancelsWaitsForCleanupRejectsDuplicateThenAllowsRestart() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch cleaning = new CountDownLatch(1);
        CountDownLatch releaseCleanup = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        try (var service = new DesktopAnalysisService(path -> {
            if (calls.incrementAndGet() == 1) {
                started.countDown();
                try { new CountDownLatch(1).await(); }
                catch (InterruptedException expected) { cleaning.countDown(); releaseCleanup.await(); }
            }
            return success();
        }, files, Runnable::run, snapshot -> { })) {
            assertThat(service.analyze(temporary.toString())).isTrue();
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(service.analyze(temporary.toString())).isFalse();
            service.cancel();
            assertThat(cleaning.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(service.snapshot().busy()).isTrue();
            assertThat(service.analyze(temporary.toString())).isFalse();
            releaseCleanup.countDown();
            idle(service);
            assertThat(service.snapshot().state()).isEqualTo(DesktopAnalysisService.State.CANCELLED);
            assertThat(service.snapshot().report()).isNull();
            assertThat(service.analyze(temporary.toString())).isTrue();
            idle(service);
            assertThat(service.snapshot().state()).isEqualTo(DesktopAnalysisService.State.SUCCESS);
        } finally { releaseCleanup.countDown(); }
    }

    @ParameterizedTest
    @EnumSource(AnalyzerExecutionFailure.class)
    void analysisFailureAlsoReportsSuppressedCleanupError(AnalyzerExecutionFailure reason) throws Exception {
        var failure = new AnalyzerExecutionException(reason, "sensitive primary diagnostic");
        failure.addSuppressed(new java.io.IOException("sensitive cleanup path"));
        AtomicInteger calls = new AtomicInteger();
        try (var service = new DesktopAnalysisService(path -> {
            if (calls.incrementAndGet() == 2) throw failure;
            return success();
        }, files, Runnable::run, ignored -> { })) {
            service.analyze(temporary.toString()); idle(service);
            assertThat(service.snapshot().report()).isNotNull();
            service.analyze(temporary.toString()); idle(service);
            assertThat(service.snapshot().state()).isEqualTo(DesktopAnalysisService.State.FAILED);
            assertThat(service.snapshot().busy()).isFalse();
            assertThat(service.snapshot().report()).isNull();
            assertThat(service.snapshot().message()).contains("清理失败")
                    .doesNotContain("并清理临时文件", "sensitive primary diagnostic", "sensitive cleanup path");
            String primaryReason = switch (reason) {
                case TIMEOUT -> "分析超时";
                case START_FAILED -> "无法启动分析器";
                case REPORT_INVALID, REPORT_MISSING -> "未产生可用的严格 v2 报告";
                case INVALID_REPOSITORY_PATH, REPOSITORY_NOT_FOUND -> "项目路径无效";
                case CLEANUP_FAILED -> "分析临时文件清理失败";
                default -> reason.name();
            };
            assertThat(service.snapshot().message()).contains(primaryReason);
            if (reason == AnalyzerExecutionFailure.TIMEOUT) {
                assertThat(service.snapshot().message()).contains("已结束进程");
            }
            service.analyze(temporary.toString()); idle(service);
            assertThat(service.snapshot().state()).isEqualTo(DesktopAnalysisService.State.SUCCESS);
        }
    }

    @Test
    void timeoutWithSuccessfulCleanupRetainsNormalTimeoutMessage() throws Exception {
        try (var service = new DesktopAnalysisService(path -> {
            throw new AnalyzerExecutionException(AnalyzerExecutionFailure.TIMEOUT, "timeout");
        }, files, Runnable::run, ignored -> { })) {
            service.analyze(temporary.toString()); idle(service);
            assertThat(service.snapshot().message()).contains("分析超时", "已结束进程并清理临时文件")
                    .doesNotContain("清理失败");
        }
    }

    @Test
    void lateUiCallbackCannotPublishSuccessAfterCancellation() throws Exception {
        BlockingQueue<Runnable> callbacks = new LinkedBlockingQueue<>();
        try (var service = new DesktopAnalysisService(path -> success(), files, callbacks::add, ignored -> { })) {
            service.analyze(temporary.toString());
            idle(service);
            assertThat(service.snapshot().busy()).isTrue();
            service.cancel();
            callbacks.remove().run();
            assertThat(service.snapshot().state()).isEqualTo(DesktopAnalysisService.State.CANCELLED);
        }
    }

    @ParameterizedTest
    @EnumSource(value = AnalyzerExecutionFailure.class, names = {"TIMEOUT", "START_FAILED", "REPORT_INVALID", "SCAN_FAILED"})
    void errorsClearOldResultsAndPermitAnotherAnalysis(AnalyzerExecutionFailure failure) throws Exception {
        AtomicInteger calls = new AtomicInteger();
        try (var service = new DesktopAnalysisService(path -> {
            if (calls.incrementAndGet() == 2) throw new AnalyzerExecutionException(failure, "sensitive diagnostic");
            return success();
        }, files, Runnable::run, ignored -> { })) {
            service.analyze(temporary.toString()); idle(service);
            assertThat(service.snapshot().report()).isNotNull();
            service.analyze(temporary.toString()); idle(service);
            assertThat(service.snapshot().state()).isEqualTo(DesktopAnalysisService.State.FAILED);
            assertThat(service.snapshot().report()).isNull();
            assertThat(service.snapshot().message()).doesNotContain("sensitive diagnostic");
            service.analyze(temporary.toString()); idle(service);
            assertThat(service.snapshot().state()).isEqualTo(DesktopAnalysisService.State.SUCCESS);
        }
    }

    @Test
    void invalidPathAndInvalidImportCannotLeavePreviousSuccess() throws Exception {
        try (var service = new DesktopAnalysisService(path -> success(), files, Runnable::run, ignored -> { })) {
            service.analyze(temporary.toString()); idle(service);
            assertThat(service.analyze("\u0000")).isFalse();
            assertThat(service.snapshot().report()).isNull();
            Path invalid = temporary.resolve("invalid.json"); Files.writeString(invalid, "{}");
            service.open(invalid); idle(service);
            assertThat(service.snapshot().state()).isEqualTo(DesktopAnalysisService.State.FAILED);
            assertThat(service.snapshot().message()).contains("报告校验失败");
            service.open(ReportFilesTest.fixture("partial-success")); idle(service);
            assertThat(service.snapshot().state()).isEqualTo(DesktopAnalysisService.State.PARTIAL_SUCCESS);
        }
    }

    @Test
    void saveFailureKeepsCurrentReportForRetryAndSuccessfulSaveCanReopen() throws Exception {
        try (var service = new DesktopAnalysisService(path -> success(), files, Runnable::run, ignored -> { })) {
            service.analyze(temporary.toString()); idle(service);
            service.save(temporary.resolve("missing/child.json")); idle(service);
            assertThat(service.snapshot().message()).contains("保存失败");
            assertThat(service.snapshot().report()).isEqualTo(success());
            Path saved = temporary.resolve("report.json");
            service.save(saved); idle(service);
            assertThat(service.snapshot().message()).contains("已保存");
            service.open(saved); idle(service);
            assertThat(service.snapshot().report()).isEqualTo(success());
        }
    }

    @Test
    void closeInterruptsWorkerAndSuppressesLateNotifications() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        AtomicInteger notifications = new AtomicInteger();
        var service = new DesktopAnalysisService(path -> {
            started.countDown();
            try { new CountDownLatch(1).await(); }
            catch (InterruptedException expected) { interrupted.countDown(); throw expected; }
            return success();
        }, files, Runnable::run, ignored -> notifications.incrementAndGet());
        service.analyze(temporary.toString());
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        service.close(); idle(service);
        assertThat(interrupted.getCount()).isZero();
        assertThat(notifications.get()).isEqualTo(1);
        assertThat(service.analyze(temporary.toString())).isFalse();
    }
}
