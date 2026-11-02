package io.github.lihanc940.openpulse.desktop;

import io.github.lihanc940.openpulse.integration.analyzer.model.AnalyzerReportV2;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.TextField;
import javafx.stage.Stage;

import java.nio.file.Path;
import java.time.Duration;
import tools.jackson.databind.ObjectMapper;

/** Manual window acceptance harness: real shared Runner with a slow test-only process tree. */
public final class WindowProbe extends Application {
    public static void main(String[] arguments) { Application.launch(WindowProbe.class, arguments); }
    @Override public void start(Stage stage) {
        var options = getParameters().getNamed();
        Path markers = Path.of(options.get("markers"));
        var runner = SlowProcessMain.runner(markers, Duration.ofSeconds(Long.parseLong(options.getOrDefault("timeout", "30"))));
        var workspace = new AnalysisWorkspace(stage);
        var service = new DesktopAnalysisService(path -> (AnalyzerReportV2) runner.analyze(path),
                new ReportFiles(new ObjectMapper(), SlowProcessMain.reader()), Platform::runLater, workspace::render);
        workspace.bind(service);
        ((TextField) workspace.root().lookup("#project-path")).setText(options.getOrDefault("repository", markers.toString()));
        if (options.containsKey("report")) service.open(Path.of(options.get("report")));
        stage.setTitle("OpenPulse AI · 窗口验收（测试替身）");
        stage.setScene(new Scene(workspace.root(), Double.parseDouble(options.getOrDefault("width", "960")),
                Double.parseDouble(options.getOrDefault("height", "680"))));
        Platform.setImplicitExit(false);
        stage.setOnCloseRequest(event -> {
            event.consume(); stage.hide(); service.close();
            service.whenIdle().whenComplete((ignored, failure) -> Platform.runLater(Platform::exit));
        });
        stage.show();
        System.out.println("Probe render scale=" + stage.getRenderScaleX() + ", scene="
                + stage.getScene().getWidth() + "x" + stage.getScene().getHeight());
    }
}
