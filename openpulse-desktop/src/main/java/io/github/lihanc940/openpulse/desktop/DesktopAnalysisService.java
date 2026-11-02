package io.github.lihanc940.openpulse.desktop;

import io.github.lihanc940.openpulse.integration.analyzer.AnalyzerExecutionException;
import io.github.lihanc940.openpulse.integration.analyzer.AnalyzerExecutionFailure;
import io.github.lihanc940.openpulse.integration.analyzer.AnalyzerReportReadException;
import io.github.lihanc940.openpulse.integration.analyzer.model.AnalyzerReportV2;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** Coordinates a single operation. UI notifications are dispatched only after the worker has cleaned up. */
public final class DesktopAnalysisService implements AutoCloseable {
    public enum State { EMPTY, READY, RUNNING, SUCCESS, PARTIAL_SUCCESS, FAILED, CANCELLED }
    public record Snapshot(State state, boolean busy, boolean cancellable, AnalyzerReportV2 report, String message) { }
    @FunctionalInterface
    public interface Analysis { AnalyzerReportV2 run(Path path) throws Exception; }
    @FunctionalInterface
    private interface Work { AnalyzerReportV2 run() throws Exception; }
    private enum Operation { ANALYZE, OPEN, SAVE }

    private final Analysis analysis;
    private final ReportFiles files;
    private final Consumer<Runnable> dispatch;
    private final Consumer<Snapshot> listener;
    private Snapshot snapshot = new Snapshot(State.EMPTY, false, false, null, "选择项目文件夹，或打开已有 JSON 报告。");
    private Thread worker;
    private boolean cancelled;
    private boolean closed;
    private CompletableFuture<Void> idle = CompletableFuture.completedFuture(null);

    public DesktopAnalysisService(Analysis analysis, ReportFiles files,
            Consumer<Runnable> dispatch, Consumer<Snapshot> listener) {
        this.analysis = analysis;
        this.files = files;
        this.dispatch = dispatch;
        this.listener = listener;
    }

    public synchronized Snapshot snapshot() { return snapshot; }
    public synchronized CompletableFuture<Void> whenIdle() { return idle; }

    public synchronized void ready(String path) {
        if (worker != null || closed) return;
        boolean empty = path == null || path.isBlank();
        snapshot = new Snapshot(empty ? State.EMPTY : State.READY, false, false, null,
                empty ? "选择项目文件夹，或打开已有 JSON 报告。" : "已选择项目，可以开始分析。");
        listener.accept(snapshot);
    }

    public synchronized boolean analyze(String text) {
        if (worker != null || closed) return false;
        Path path;
        try {
            if (text == null || text.isBlank()) throw new IllegalArgumentException();
            path = Path.of(text).toAbsolutePath().normalize();
        } catch (RuntimeException exception) {
            snapshot = new Snapshot(State.FAILED, false, false, null, "请选择存在的项目文件夹，然后重新分析。");
            listener.accept(snapshot);
            return false;
        }
        return start(Operation.ANALYZE, () -> {
            if (!Files.isDirectory(path)) {
                throw new AnalyzerExecutionException(
                        AnalyzerExecutionFailure.INVALID_REPOSITORY_PATH,
                        "Repository is not a directory");
            }
            return analysis.run(path);
        });
    }

    public synchronized boolean open(Path path) {
        return start(Operation.OPEN, () -> files.open(path));
    }

    public synchronized boolean save(Path path) {
        AnalyzerReportV2 report = snapshot.report();
        if (report == null) return false;
        return start(Operation.SAVE, () -> { files.save(report, path); return report; });
    }

    private boolean start(Operation operation, Work work) {
        if (worker != null || closed) return false;
        cancelled = false;
        AnalyzerReportV2 retained = operation == Operation.SAVE ? snapshot.report() : null;
        State retainedState = snapshot.state();
        snapshot = new Snapshot(operation == Operation.SAVE ? retainedState : State.RUNNING, true,
                operation == Operation.ANALYZE, retained, switch (operation) {
                    case ANALYZE -> "正在本地分析…（默认超时 30 秒）";
                    case OPEN -> "正在严格校验报告…";
                    case SAVE -> "正在保存 JSON…";
                });
        CompletableFuture<Void> completion = new CompletableFuture<>();
        idle = completion;
        worker = new Thread(() -> {
            AnalyzerReportV2 result = null;
            Exception failure = null;
            try { result = Objects.requireNonNull(work.run()); }
            catch (Exception exception) { failure = exception; }
            AnalyzerReportV2 finishedResult = result;
            Exception finishedFailure = failure;
            // Runner's finally block has completed before we enqueue the terminal state.
            dispatch.accept(() -> finish(operation, finishedResult, finishedFailure, retained, retainedState));
            completion.complete(null);
        }, "openpulse-desktop-worker");
        worker.setDaemon(false);
        listener.accept(snapshot);
        worker.start();
        return true;
    }

    private synchronized void finish(Operation operation, AnalyzerReportV2 report, Exception failure,
            AnalyzerReportV2 retained, State retainedState) {
        worker = null;
        if (closed) return;
        if (cancelled && cleanupFailed(failure)) {
            snapshot = new Snapshot(State.FAILED, false, false, null,
                    "取消后临时文件清理失败：请检查文件占用或访问权限后重试。");
        } else if (cancelled) {
            snapshot = new Snapshot(State.CANCELLED, false, false, null, "已取消，分析进程已结束；可以重新分析。");
        } else if (failure != null) {
            snapshot = new Snapshot(operation == Operation.SAVE ? retainedState : State.FAILED,
                    false, false, retained, errorMessage(failure, operation == Operation.SAVE));
        } else {
            State state = switch (report.status()) {
                case SUCCESS -> State.SUCCESS;
                case PARTIAL_SUCCESS -> State.PARTIAL_SUCCESS;
                case FAILED -> State.FAILED;
            };
            String message = operation == Operation.SAVE ? "JSON 已保存。" : switch (state) {
                case SUCCESS -> "扫描范围已完成；这不代表代码无缺陷或测试已通过。";
                case PARTIAL_SUCCESS -> "部分成功：请阅读下方扫描限制。";
                default -> "报告表明扫描失败，没有形成可用的分析结果。";
            };
            snapshot = new Snapshot(state, false, false, report, message);
        }
        listener.accept(snapshot);
    }

    public synchronized void cancel() {
        if (worker == null || !snapshot.cancellable() || cancelled) return;
        cancelled = true;
        snapshot = new Snapshot(State.RUNNING, true, false, null, "正在取消并清理分析进程，请稍候…");
        listener.accept(snapshot);
        worker.interrupt();
    }

    @Override
    public synchronized void close() {
        closed = true;
        cancelled = true;
        if (worker != null) worker.interrupt();
    }

    private static boolean cleanupFailed(Exception failure) {
        // Runner attaches a cleanup IOException to the original execution failure.
        return failure != null && (failure.getSuppressed().length > 0
                || failure instanceof AnalyzerExecutionException execution
                && execution.failure() == AnalyzerExecutionFailure.CLEANUP_FAILED);
    }

    private static String errorMessage(Exception exception, boolean saving) {
        if (saving) return "保存失败：请检查目标文件夹权限、文件占用或磁盘空间后重试。";
        if (exception instanceof AnalyzerReportReadException read) {
            String reason = switch (read.failure()) {
                case INVALID_FILE -> "文件不存在、不可读取或不是普通文件";
                case FILE_TOO_LARGE -> "报告超过 16 MiB 大小上限";
                case MALFORMED_JSON, INVALID_ROOT -> "JSON 格式不正确或存在重复字段";
                case INVALID_PROTOCOL_VERSION, UNSUPPORTED_PROTOCOL_VERSION -> "需要协议版本 2.0";
                case SCHEMA_VIOLATION, MODEL_MAPPING_FAILED -> "字段、类型或取值不符合协议";
                case SEMANTIC_VIOLATION -> "报告内容之间存在不一致或不安全数据";
            };
            return "报告校验失败：" + reason + "。请选择合法的协议 2.0 JSON 报告。";
        }
        if (exception instanceof AnalyzerExecutionException execution) {
            boolean cleanupFailed = cleanupFailed(execution);
            String message = switch (execution.failure()) {
                case TIMEOUT -> cleanupFailed
                        ? "分析超时：已结束进程。请缩小扫描目录后重试。"
                        : "分析超时：已结束进程并清理临时文件。请缩小扫描目录后重试。";
                case START_FAILED -> "无法启动分析器：请检查应用目录 bin 中的分析器文件。";
                case REPORT_INVALID, REPORT_MISSING -> "分析器未产生可用的严格 v2 报告，请检查分析器版本后重试。";
                case INVALID_REPOSITORY_PATH, REPOSITORY_NOT_FOUND -> "项目路径无效或已不存在，请重新选择文件夹。";
                case CLEANUP_FAILED -> "分析临时文件清理失败：请检查文件占用后重试。";
                default -> "分析失败（" + execution.failure() + "），请检查目录访问权限后重试。";
            };
            if (cleanupFailed && execution.failure() != AnalyzerExecutionFailure.CLEANUP_FAILED) {
                message += " 同时，临时文件清理失败：请检查文件占用或访问权限后重试。";
            }
            return message;
        }
        return "操作失败：请检查文件和目录是否仍可访问，然后重试。";
    }
}
