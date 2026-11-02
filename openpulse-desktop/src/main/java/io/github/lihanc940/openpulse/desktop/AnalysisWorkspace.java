package io.github.lihanc940.openpulse.desktop;

import io.github.lihanc940.openpulse.integration.analyzer.model.AnalyzerReportV2;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.geometry.Insets;
import javafx.scene.Parent;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import java.io.File;
import java.nio.file.Path;

public final class AnalysisWorkspace {
    private final Stage stage;
    private final BorderPane root = new BorderPane();
    private final TextField project = new TextField();
    private final Button choose = new Button("选择文件夹");
    private final Button analyze = new Button("开始分析");
    private final Button cancel = new Button("取消");
    private final Button open = new Button("打开 JSON");
    private final Button save = new Button("导出 JSON");
    private final Label status = new Label();
    private final Label message = new Label();
    private final ProgressIndicator progress = new ProgressIndicator();
    private final TextArea overview = textArea("分析结果将在这里显示。");
    private final TextArea details = textArea("选择一条发现，查看原始证据和修复建议。");
    private final TextArea limitations = textArea("分析后显示扫描限制。");
    private final TableView<AnalyzerReportV2.Finding> findings = new TableView<>();
    private final Tab limitsTab = new Tab("扫描限制", limitations);
    private final ReportPresentation presentation = new ReportPresentation();
    private DesktopAnalysisService service;
    private AnalyzerReportV2 displayed;

    public AnalysisWorkspace(Stage stage) {
        this.stage = stage;
        Label title = new Label("OpenPulse AI");
        title.setStyle("-fx-font-size: 25px; -fx-font-weight: bold; -fx-text-fill: #102c52;");
        Label subtitle = new Label("本地仓库分析  /  报告协议 2.0");
        subtitle.setStyle("-fx-text-fill: #52657c;");
        project.setPromptText("选择项目文件夹，或粘贴完整路径");
        project.setAccessibleText("项目文件夹路径");
        project.setId("project-path");
        HBox pathRow = new HBox(10, project, choose);
        HBox.setHgrow(project, Priority.ALWAYS);
        analyze.setStyle("-fx-background-color: #245fc2; -fx-text-fill: white; -fx-font-weight: bold;");
        analyze.setDefaultButton(true);
        FlowPane actions = new FlowPane(10, 8, analyze, cancel, open, save);
        progress.setPrefSize(22, 22);
        progress.setMaxSize(22, 22);
        status.setStyle("-fx-font-weight: bold; -fx-text-fill: #16457a;");
        HBox stateRow = new HBox(10, progress, status);
        message.setWrapText(true);
        VBox header = new VBox(10, title, subtitle, pathRow, actions, stateRow, message);
        header.setPadding(new Insets(20, 24, 16, 24));
        header.setStyle("-fx-background-color: #f0f5fc;");
        root.setTop(header);

        TableColumn<AnalyzerReportV2.Finding, String> severity = new TableColumn<>("程度");
        severity.setCellValueFactory(cell -> new ReadOnlyStringWrapper(ReportPresentation.severity(cell.getValue().severity())));
        severity.setPrefWidth(65);
        TableColumn<AnalyzerReportV2.Finding, String> rule = new TableColumn<>("规则 ID");
        rule.setCellValueFactory(cell -> new ReadOnlyStringWrapper(cell.getValue().ruleId()));
        rule.setPrefWidth(170);
        TableColumn<AnalyzerReportV2.Finding, String> issue = new TableColumn<>("问题");
        issue.setCellValueFactory(cell -> new ReadOnlyStringWrapper(cell.getValue().message()));
        issue.setPrefWidth(250);
        findings.getColumns().add(severity);
        findings.getColumns().add(rule);
        findings.getColumns().add(issue);
        findings.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        findings.setPlaceholder(new Label("当前没有可展示的发现；请先分析或打开报告。"));
        findings.getSelectionModel().selectedItemProperty().addListener((observable, before, after) -> {
            details.setText(after == null ? "选择一条发现，查看原始证据和修复建议。" : presentation.finding(after));
            details.positionCaret(0);
        });
        SplitPane split = new SplitPane(findings, details);
        split.setDividerPositions(0.48);
        TabPane tabs = new TabPane(new Tab("概览", overview), new Tab("发现与证据", split), limitsTab);
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        root.setCenter(tabs);
        BorderPane.setMargin(tabs, new Insets(12, 24, 12, 24));
        Label footer = new Label("本机执行 · 不上传源码 · 发现项与证据来自 C++ 分析器");
        footer.setPadding(new Insets(0, 24, 14, 24));
        footer.setStyle("-fx-text-fill: #52657c;");
        root.setBottom(footer);
        root.setStyle("-fx-font-family: 'Microsoft YaHei'; -fx-font-size: 13px; -fx-background-color: white;");
    }

    public void bind(DesktopAnalysisService service) {
        this.service = service;
        project.textProperty().addListener((observable, before, after) -> service.ready(after));
        choose.setOnAction(event -> {
            DirectoryChooser chooser = new DirectoryChooser();
            chooser.setTitle("选择要分析的项目文件夹");
            try { if (new File(project.getText()).isDirectory()) chooser.setInitialDirectory(new File(project.getText())); }
            catch (RuntimeException ignored) { /* A pasted invalid path must not prevent choosing another directory. */ }
            File selected = chooser.showDialog(stage);
            if (selected != null) project.setText(selected.getAbsolutePath());
        });
        analyze.setOnAction(event -> service.analyze(project.getText()));
        cancel.setOnAction(event -> service.cancel());
        open.setOnAction(event -> {
            File selected = chooser("打开协议 2.0 JSON 报告").showOpenDialog(stage);
            if (selected != null) service.open(selected.toPath());
        });
        save.setOnAction(event -> {
            FileChooser chooser = chooser("导出 JSON 报告");
            chooser.setInitialFileName("openpulse-report.json");
            File selected = chooser.showSaveDialog(stage);
            if (selected != null) service.save(selected.toPath());
        });
        render(service.snapshot());
    }

    public Parent root() { return root; }

    public void render(DesktopAnalysisService.Snapshot snapshot) {
        project.setDisable(snapshot.busy());
        choose.setDisable(snapshot.busy());
        analyze.setDisable(snapshot.busy());
        analyze.setText(snapshot.report() == null ? "开始分析" : "重新分析");
        open.setDisable(snapshot.busy());
        save.setDisable(snapshot.busy() || snapshot.report() == null);
        cancel.setDisable(!snapshot.cancellable());
        progress.setVisible(snapshot.busy());
        progress.setManaged(snapshot.busy());
        status.setText(switch (snapshot.state()) {
            case EMPTY -> "等待选择"; case READY -> "准备就绪"; case RUNNING -> "正在处理";
            case SUCCESS -> "扫描完成"; case PARTIAL_SUCCESS -> "部分成功";
            case FAILED -> "失败"; case CANCELLED -> "已取消";
        });
        message.setText(snapshot.message());
        if (displayed != snapshot.report()) {
            displayed = snapshot.report();
            findings.getItems().clear();
            if (displayed == null) {
                findings.setPlaceholder(new Label("本次尚无可用报告，无法判断发现项。"));
                overview.setText("本次尚无可用报告。");
                details.setText("选择一条发现，查看原始证据和修复建议。");
                limitations.setText("本次尚无报告，不能推断没有扫描限制。");
                limitsTab.setText("扫描限制");
            } else {
                overview.setText(presentation.overview(displayed));
                overview.positionCaret(0);
                findings.getItems().setAll(displayed.findings());
                limitations.setText(presentation.limitations(displayed));
                limitations.positionCaret(0);
                limitsTab.setText("扫描限制（" + displayed.limitations().size() + "）");
                if (!displayed.findings().isEmpty()) findings.getSelectionModel().selectFirst();
                findings.setPlaceholder(new Label(displayed.status() == AnalyzerReportV2.Status.FAILED
                        ? "扫描失败，未形成可用发现。" : "本次没有发现项，不代表代码无缺陷。"));
            }
        }
    }

    private FileChooser chooser(String title) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(title);
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("JSON 报告 (*.json)", "*.json"));
        return chooser;
    }

    private static TextArea textArea(String text) {
        TextArea area = new TextArea(text);
        area.setEditable(false);
        area.setWrapText(true);
        return area;
    }
}
