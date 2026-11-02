package io.github.lihanc940.openpulse.desktop;

import io.github.lihanc940.openpulse.integration.analyzer.model.AnalyzerReportV2;
import tools.jackson.databind.ObjectMapper;

/** Plain text only. Protocol DTOs and raw evidence are never rewritten by the view. */
public final class ReportPresentation {
    private final ObjectMapper mapper = new ObjectMapper();

    public String overview(AnalyzerReportV2 report) {
        String header = "项目：" + report.repository().name()
                + "\n分析器：" + report.analyzer().name() + " " + report.analyzer().version()
                + "\n协议：" + report.protocolVersion() + "    状态：" + report.status()
                + "\n可复核性：" + report.reviewability()
                + "\n规则集：" + report.ruleSet().id() + " @ " + report.ruleSet().version()
                + "\n生成时间：" + report.generatedAt() + "\n任务 ID：" + report.taskId();
        if (report.summary() == null) return header + "\n\n扫描失败，未生成可用统计。不能解释为零问题。";
        var s = report.summary();
        var structure = report.structure();
        StringBuilder text = new StringBuilder(header).append("\n\n文件统计\n")
                .append("全部：").append(s.totalFiles()).append("    源码：").append(s.sourceFiles())
                .append("    文档：").append(s.documentFiles()).append("    配置：").append(s.configFiles())
                .append("    测试文件：").append(s.testFiles())
                .append("\n总行数：").append(s.totalLines()).append("    代码行：").append(s.codeLines())
                .append("    注释行：").append(s.commentLines()).append("    空白行：").append(s.blankLines())
                .append("\n\n语言统计\n");
        report.languages().forEach(language -> text.append(language.name()).append("：")
                .append(language.files()).append(" 个文件，").append(language.lines()).append(" 行\n"));
        text.append("\n结构事实（检测到文件不代表运行过测试或构建）\n")
                .append("README：").append(yes(structure.hasReadme())).append("    许可证：").append(yes(structure.hasLicense()))
                .append("    CI：").append(yes(structure.hasCi())).append("    测试结构：").append(yes(structure.hasTests()))
                .append("\n贡献指南：").append(yes(structure.hasContributing())).append("    更新日志：").append(yes(structure.hasChangelog()))
                .append("    Dockerfile：").append(yes(structure.hasDockerfile()))
                .append("\n构建文件：\n").append(String.join("\n", structure.buildFiles()))
                .append("\n\n发现：").append(report.findings().size()).append(" 项    扫描限制：").append(report.limitations().size())
                .append(" 项\n没有发现项不代表代码无缺陷，COMPLETE 不代表测试已通过。");
        return text.toString();
    }

    public String finding(AnalyzerReportV2.Finding finding) {
        return "问题\n" + finding.message() + "\n\n规则：" + finding.ruleId()
                + "\n严重程度：" + severity(finding.severity()) + "（" + finding.severity() + "）"
                + "\n发现 ID：" + finding.findingId() + "\n范围：" + finding.scope()
                + "\n位置：" + (finding.location() == null ? "仓库级" : json(finding.location()))
                + "\n\n原始证据\n" + json(finding.evidence())
                + "\n\n修复建议\n" + finding.remediation();
    }

    public String limitations(AnalyzerReportV2 report) {
        if (report.limitations().isEmpty()) return "本报告没有列出扫描限制。当前规则覆盖范围仍有限，不能推断代码无缺陷。";
        StringBuilder text = new StringBuilder("请保留并阅读这些限制；以下是报告中的原始内容。\n\n");
        report.limitations().forEach(limit -> text.append(limit.kind()).append("\n")
                .append(limit.message()).append("\n").append(json(limit)).append("\n\n"));
        return text.toString();
    }

    public static String severity(AnalyzerReportV2.Severity severity) {
        return switch (severity) { case LOW -> "低"; case MEDIUM -> "中"; case HIGH -> "高"; case CRITICAL -> "严重"; };
    }
    private String yes(boolean value) { return value ? "检测到" : "未检测到"; }
    private String json(Object value) { return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(value); }
}
