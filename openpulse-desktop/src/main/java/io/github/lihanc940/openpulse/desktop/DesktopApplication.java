package io.github.lihanc940.openpulse.desktop;

import io.github.lihanc940.openpulse.integration.analyzer.*;
import io.github.lihanc940.openpulse.integration.analyzer.model.AnalyzerReportV2;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.stage.Stage;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.time.Duration;

public final class DesktopApplication extends Application {
    private DesktopAnalysisService service;

    @Override
    public void start(Stage stage) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        AnalyzerReportReader reader = new AnalyzerReportReader(mapper, new AnalyzerReportV2SchemaValidator(),
                new AnalyzerReportV2SemanticValidator(mapper));
        Path appDirectory = System.getProperty("openpulse.desktop.app-dir") == null
                ? Path.of(DesktopApplication.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getParent()
                : Path.of(System.getProperty("openpulse.desktop.app-dir")).toAbsolutePath().normalize();
        AnalyzerProcessProperties properties = new AnalyzerProcessProperties(
                appDirectory.resolve("bin/openpulse-analyzer.exe").toString(), Duration.ofSeconds(30), "2.0");
        AnalyzerProcessRunner runner = new AnalyzerProcessRunner(properties,
                new ConfiguredAnalyzerCommandFactory(properties), reader);
        AnalysisWorkspace workspace = new AnalysisWorkspace(stage);
        service = new DesktopAnalysisService(path -> (AnalyzerReportV2) runner.analyze(path),
                new ReportFiles(mapper, reader), Platform::runLater, workspace::render);
        workspace.bind(service);
        stage.setTitle("OpenPulse AI · 本地分析工作区");
        stage.setScene(new Scene(workspace.root(), 1120, 780));
        stage.setMinWidth(900);
        stage.setMinHeight(640);
        Platform.setImplicitExit(false);
        stage.setOnCloseRequest(event -> {
            event.consume();
            stage.hide();
            service.close();
            service.whenIdle().whenComplete((ignored, failure) -> Platform.runLater(Platform::exit));
        });
        stage.show();
    }

    @Override
    public void stop() { if (service != null) service.close(); }
}
