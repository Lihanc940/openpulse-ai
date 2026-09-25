#include "ReportV2Builder.h"

#include <algorithm>
#include <set>

#include "picosha2/picosha2.h"

#include "RuleCatalog.h"
#include "Version.h"

namespace openpulse {

namespace {

constexpr char kSeparator = '\x1f';
constexpr std::uint64_t kMaxSafeInteger = 9007199254740991ULL;

const char* kScanFailedMessage = "扫描未能完成，报告不可用于复核。";

std::string skipMessageFor(const std::string& reason) {
    if (reason == "PERMISSION_DENIED") {
        return "没有读取权限，已跳过该条目。";
    }
    return "文件读取失败，未纳入统计。";
}

void checkSafeInteger(std::uint64_t value, const char* field) {
    if (value > kMaxSafeInteger) {
        throw ReportConstructionError(std::string("counter exceeds safe integer range: ") + field);
    }
}

// True when the catalog rule's target structure element is present.
bool ruleSatisfiedBy(const RuleDefinition& rule, const StructureCheck& structure) {
    const std::string id = rule.ruleId;
    if (id == "MISSING_README") return structure.hasReadme;
    if (id == "MISSING_LICENSE") return structure.hasLicense;
    if (id == "MISSING_CI") return structure.hasCi;
    throw ReportConstructionError(std::string("rule has no structure fact: ") + id);
}

} // anonymous namespace

bool ReportV2Builder::isSafeRelativePath(const std::string& path) {
    if (path.empty() || path.size() > 1024) {
        return false;
    }
    if (path.front() == '/' || path.front() == '\\') {
        return false;
    }
    // Drive letters and URI schemes both require a colon; the protocol
    // forbids colons entirely, so a single check covers both.
    if (path.find(':') != std::string::npos ||
        path.find('\\') != std::string::npos) {
        return false;
    }
    size_t start = 0;
    while (true) {
        size_t slash = path.find('/', start);
        std::string segment = path.substr(
            start, slash == std::string::npos ? slash : slash - start);
        if (segment.empty() || segment == "." || segment == "..") {
            return false;
        }
        for (unsigned char c : segment) {
            if (c < 0x20 || c == 0x7f) {
                return false;
            }
        }
        if (slash == std::string::npos) {
            break;
        }
        start = slash + 1;
    }
    return true;
}

std::string ReportV2Builder::sha256Hex(const std::string& utf8) {
    return picosha2::hash256_hex_string(utf8);
}

std::string ReportV2Builder::computeFindingId(const std::string& ruleId,
                                              const std::string& scope,
                                              const std::string& pathPart,
                                              const std::string& locationPart,
                                              const nlohmann::json& canonicalEvidence) {
    std::string input;
    input.reserve(ruleId.size() + scope.size() + pathPart.size() +
                  locationPart.size() + 256);
    input += ruleId;
    input += kSeparator;
    input += scope;
    input += kSeparator;
    input += pathPart;
    input += kSeparator;
    input += locationPart;
    input += kSeparator;
    // nlohmann::json keeps object keys in code-point order and dump()
    // emits no whitespace, matching the protocol canonical JSON rules.
    input += canonicalEvidence.dump();
    return "sha256:" + sha256Hex(input);
}

nlohmann::json ReportV2Builder::build(const ScanFacts& facts,
                                      const std::string& repoName,
                                      const std::string& taskId,
                                      const std::string& generatedAt) const {
    // --- languages: programming languages only, sorted by name ascending ---
    std::vector<LangStats> langs =
        Analyzer::filterProgrammingLanguages(facts.languages);
    std::sort(langs.begin(), langs.end(),
              [](const LangStats& a, const LangStats& b) { return a.name < b.name; });
    for (size_t i = 1; i < langs.size(); ++i) {
        if (langs[i - 1].name == langs[i].name) {
            throw ReportConstructionError("duplicate language name: " + langs[i].name);
        }
    }
    auto languages = nlohmann::json::array();
    for (const auto& l : langs) {
        checkSafeInteger(l.files, "languages.files");
        checkSafeInteger(l.lines, "languages.lines");
        languages.push_back({{"name", l.name}, {"files", l.files}, {"lines", l.lines}});
    }

    // --- structure: build files sorted by path, duplicates rejected ---
    std::vector<std::string> buildFiles = facts.structure.buildFiles;
    for (const auto& bf : buildFiles) {
        if (!isSafeRelativePath(bf)) {
            throw ReportConstructionError("unsafe build file path: " + bf);
        }
    }
    std::sort(buildFiles.begin(), buildFiles.end());
    if (std::adjacent_find(buildFiles.begin(), buildFiles.end()) != buildFiles.end()) {
        throw ReportConstructionError("duplicate build file entry");
    }
    nlohmann::json structure = {
        {"hasReadme", facts.structure.hasReadme},
        {"hasLicense", facts.structure.hasLicense},
        {"hasContributing", facts.structure.hasContributing},
        {"hasChangelog", facts.structure.hasChangelog},
        {"hasCi", facts.structure.hasCi},
        {"hasTests", facts.structure.hasTests},
        {"hasDockerfile", facts.structure.hasDockerfile},
        {"buildFiles", buildFiles}
    };

    // --- findings: one repository-level finding per unsatisfied rule ---
    auto findings = nlohmann::json::array();
    for (const auto& rule : ruleCatalog()) {
        if (ruleSatisfiedBy(rule, facts.structure)) {
            continue;
        }
        std::vector<std::string> expected = rule.candidatePaths;
        if (expected.empty() || expected.size() > 16) {
            throw ReportConstructionError(std::string("invalid candidate list for rule ") + rule.ruleId);
        }
        for (const auto& p : expected) {
            if (!isSafeRelativePath(p)) {
                throw ReportConstructionError("unsafe candidate path in rule catalog: " + p);
            }
        }
        std::sort(expected.begin(), expected.end());
        if (std::adjacent_find(expected.begin(), expected.end()) != expected.end()) {
            throw ReportConstructionError(std::string("duplicate candidate path in rule ") + rule.ruleId);
        }
        nlohmann::json evidence = {
            {"kind", rule.evidenceKind},
            {"expectedPaths", expected}
        };
        findings.push_back({
            {"findingId", computeFindingId(rule.ruleId, "REPOSITORY", "-", "-", evidence)},
            {"ruleId", rule.ruleId},
            {"type", rule.type},
            {"severity", rule.severity},
            {"scope", "REPOSITORY"},
            {"location", nullptr},
            {"message", rule.message},
            {"evidence", std::move(evidence)},
            {"remediation", rule.remediation}
        });
    }
    std::sort(findings.begin(), findings.end(),
              [](const nlohmann::json& a, const nlohmann::json& b) {
                  return a["findingId"].get<std::string>() < b["findingId"].get<std::string>();
              });
    for (size_t i = 1; i < findings.size(); ++i) {
        if (findings[i - 1]["findingId"] == findings[i]["findingId"]) {
            throw ReportConstructionError("duplicate findingId in report");
        }
    }

    // --- limitations: one FILE_SKIPPED per unreadable entry ---
    auto limitations = nlohmann::json::array();
    std::vector<std::string> limitationKeys;
    for (const auto& skip : facts.skippedFiles) {
        if (!isSafeRelativePath(skip.relativePath)) {
            throw ReportConstructionError("unsafe skipped path");
        }
        limitations.push_back({
            {"kind", "FILE_SKIPPED"},
            {"scope", "FILE"},
            {"relativePath", skip.relativePath},
            {"reason", skip.reason},
            {"message", skipMessageFor(skip.reason)}
        });
        limitationKeys.push_back(std::string("FILE_SKIPPED") + kSeparator +
                                 skip.relativePath + kSeparator + skip.reason);
    }
    std::vector<size_t> order(limitations.size());
    for (size_t i = 0; i < order.size(); ++i) order[i] = i;
    std::sort(order.begin(), order.end(),
              [&](size_t a, size_t b) { return limitationKeys[a] < limitationKeys[b]; });
    for (size_t i = 1; i < order.size(); ++i) {
        if (limitationKeys[order[i - 1]] == limitationKeys[order[i]]) {
            throw ReportConstructionError("duplicate limitation stable key");
        }
    }
    auto sortedLimitations = nlohmann::json::array();
    for (size_t i : order) {
        sortedLimitations.push_back(std::move(limitations[i]));
    }

    // --- status ---
    const bool partial = !sortedLimitations.empty();

    const FileStats& s = facts.stats;
    checkSafeInteger(s.totalFiles, "totalFiles");
    checkSafeInteger(s.sourceFiles, "sourceFiles");
    checkSafeInteger(s.documentFiles, "documentFiles");
    checkSafeInteger(s.configFiles, "configFiles");
    checkSafeInteger(s.testFiles, "testFiles");
    checkSafeInteger(s.totalLines, "totalLines");
    checkSafeInteger(s.codeLines, "codeLines");
    checkSafeInteger(s.commentLines, "commentLines");
    checkSafeInteger(s.blankLines, "blankLines");

    return {
        {"protocolVersion", "2.0"},
        {"taskId", taskId},
        {"analyzer", {{"name", OPENPULSE_ANALYZER_NAME}, {"version", OPENPULSE_ANALYZER_VERSION}}},
        {"ruleSet", {{"id", kRuleSetId}, {"version", kRuleSetVersion}}},
        {"status", partial ? "PARTIAL_SUCCESS" : "SUCCESS"},
        {"reviewability", partial ? "PARTIAL" : "COMPLETE"},
        {"repository", {{"name", repoName}, {"root", "."}}},
        {"summary", {
            {"totalFiles", s.totalFiles},
            {"sourceFiles", s.sourceFiles},
            {"documentFiles", s.documentFiles},
            {"configFiles", s.configFiles},
            {"testFiles", s.testFiles},
            {"totalLines", s.totalLines},
            {"codeLines", s.codeLines},
            {"commentLines", s.commentLines},
            {"blankLines", s.blankLines}
        }},
        {"languages", std::move(languages)},
        {"structure", std::move(structure)},
        {"findings", std::move(findings)},
        {"limitations", std::move(sortedLimitations)},
        {"generatedAt", generatedAt}
    };
}

nlohmann::json ReportV2Builder::buildFailed(const std::string& scanFailedReason,
                                            const std::string& repoName,
                                            const std::string& taskId,
                                            const std::string& generatedAt) const {
    if (scanFailedReason != "INTERNAL_ERROR" && scanFailedReason != "TRAVERSAL_ERROR") {
        throw ReportConstructionError("unknown SCAN_FAILED reason: " + scanFailedReason);
    }
    return {
        {"protocolVersion", "2.0"},
        {"taskId", taskId},
        {"analyzer", {{"name", OPENPULSE_ANALYZER_NAME}, {"version", OPENPULSE_ANALYZER_VERSION}}},
        {"ruleSet", {{"id", kRuleSetId}, {"version", kRuleSetVersion}}},
        {"status", "FAILED"},
        {"reviewability", "NOT_USABLE"},
        {"repository", {{"name", repoName}, {"root", "."}}},
        {"findings", nlohmann::json::array()},
        {"limitations", nlohmann::json::array({{
            {"kind", "SCAN_FAILED"},
            {"scope", "REPOSITORY"},
            {"reason", scanFailedReason},
            {"message", kScanFailedMessage}
        }})},
        {"generatedAt", generatedAt}
    };
}

} // namespace openpulse
