// openpulse-analyzer protocol v2 unit tests
//
// Covers: version injection, rule catalog, stable findingId vectors,
// path safety, sorting, determinism, SUCCESS / PARTIAL_SUCCESS / FAILED
// shapes and rejection of duplicate stable keys.

#include "Analyzer.h"
#include "ReportV2Builder.h"
#include "RuleCatalog.h"
#include "Version.h"

#include <algorithm>
#include <cstdlib>
#include <filesystem>
#include <iostream>
#include <regex>
#include <string>
#include <vector>

namespace fs = std::filesystem;

namespace {

int g_failed = 0;
int g_passed = 0;

void check(const char* name, bool condition) {
    if (condition) {
        g_passed++;
    } else {
        g_failed++;
        std::cerr << "  FAIL: " << name << "\n";
    }
}

#define TEST(expr) check(#expr, (expr))

fs::path fixtureDir() {
    return fs::path(OPENPULSE_FIXTURE_DIR);
}

// A guaranteed-empty scratch directory, cleaned up on creation and removal.
struct EmptyDir {
    fs::path path;
    EmptyDir() {
        path = fs::temp_directory_path() / "openpulse-v2-test-empty";
        fs::remove_all(path);
        fs::create_directories(path);
    }
    ~EmptyDir() { fs::remove_all(path); }
};

openpulse::ScanFacts factsWithSkips(std::vector<openpulse::SkippedFile> skips) {
    openpulse::ScanFacts facts;
    facts.skippedFiles = std::move(skips);
    return facts;
}

const std::regex kSemver(
    R"(^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(-[0-9A-Za-z-]+(\.[0-9A-Za-z-]+)*)?(\+[0-9A-Za-z-]+(\.[0-9A-Za-z-]+)*)?$)");

// --- Tests ---

void test_versionInjection() {
    TEST(std::string(OPENPULSE_ANALYZER_NAME) == "openpulse-analyzer");
    TEST(std::string(OPENPULSE_ANALYZER_VERSION) == "0.2.0");
    TEST(std::regex_match(OPENPULSE_ANALYZER_VERSION, kSemver));

    openpulse::Analyzer a;
    openpulse::ReportV2Builder b;
    auto report = b.build(a.collectFacts(fixtureDir()), "fixture",
                          "task_test", "2026-09-25T12:00:00Z");
    TEST(report["analyzer"]["name"] == OPENPULSE_ANALYZER_NAME);
    TEST(report["analyzer"]["version"] == OPENPULSE_ANALYZER_VERSION);
}

void test_ruleCatalog() {
    const auto& catalog = openpulse::ruleCatalog();
    TEST(catalog.size() == 3);

    TEST(std::string(openpulse::kRuleSetId) == "openpulse-default");
    TEST(std::regex_match(openpulse::kRuleSetVersion, kSemver));

    std::vector<std::string> ids;
    for (const auto& rule : catalog) {
        ids.push_back(rule.ruleId);
        TEST(std::string(rule.type) == "PROJECT_STRUCTURE");
        TEST(std::string(rule.evidenceKind) == "EXPECTED_PATHS_ABSENT");
        std::string severity = rule.severity;
        TEST(severity == "LOW" || severity == "MEDIUM" ||
             severity == "HIGH" || severity == "CRITICAL");

        std::string message = rule.message;
        std::string remediation = rule.remediation;
        TEST(!message.empty() && message.size() <= 300);
        TEST(!remediation.empty() && remediation.size() <= 500);
        TEST(message.find('\n') == std::string::npos);
        TEST(remediation.find('\n') == std::string::npos);

        TEST(!rule.candidatePaths.empty() && rule.candidatePaths.size() <= 16);
        TEST(std::is_sorted(rule.candidatePaths.begin(), rule.candidatePaths.end()));
        for (const auto& p : rule.candidatePaths) {
            TEST(openpulse::ReportV2Builder::isSafeRelativePath(p));
        }
    }
    TEST(ids == std::vector<std::string>({"MISSING_CI", "MISSING_LICENSE", "MISSING_README"}));

    TEST(openpulse::findRuleDefinition("MISSING_CI") != nullptr);
    TEST(openpulse::findRuleDefinition("LONG_FUNCTION") == nullptr);
}

void test_sha256KnownVector() {
    // Classic SHA-256 test vector confirms the hash implementation itself.
    TEST(openpulse::ReportV2Builder::sha256Hex("abc") ==
         "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
}

void test_findingIdFixedVectors() {
    // Protocol section 8 fixed vector
    nlohmann::json licenseEvidence = {
        {"kind", "EXPECTED_PATHS_ABSENT"},
        {"expectedPaths", {"COPYING", "LICENSE"}}
    };
    TEST(openpulse::ReportV2Builder::computeFindingId(
             "MISSING_LICENSE", "REPOSITORY", "-", "-", licenseEvidence) ==
         "sha256:b9dcda3c6f619d65163780c30df7792cc3baf402ba42e2adeab4d8031ac161a8");

    // Vector from the accepted partial-success sample report
    nlohmann::json ciEvidence = {
        {"kind", "EXPECTED_PATHS_ABSENT"},
        {"expectedPaths", {".github/workflows", "azure-pipelines.yml"}}
    };
    TEST(openpulse::ReportV2Builder::computeFindingId(
             "MISSING_CI", "REPOSITORY", "-", "-", ciEvidence) ==
         "sha256:1c090a45b827a6ed23196817880ef2a05968acf5280a0158a7b67ab0ff98eff7");
}

void test_safeRelativePath() {
    using B = openpulse::ReportV2Builder;
    TEST(B::isSafeRelativePath("README.md"));
    TEST(B::isSafeRelativePath(".github/workflows"));
    TEST(B::isSafeRelativePath("src/dir/File Name.cpp"));
    TEST(!B::isSafeRelativePath(""));
    TEST(!B::isSafeRelativePath("/abs/path"));
    TEST(!B::isSafeRelativePath("C:/abs/path"));
    TEST(!B::isSafeRelativePath("C:\\abs\\path"));
    TEST(!B::isSafeRelativePath("https://example.com/x"));
    TEST(!B::isSafeRelativePath("a\\b"));
    TEST(!B::isSafeRelativePath("a/b:c"));
    TEST(!B::isSafeRelativePath("."));
    TEST(!B::isSafeRelativePath(".."));
    TEST(!B::isSafeRelativePath("./a"));
    TEST(!B::isSafeRelativePath("../a"));
    TEST(!B::isSafeRelativePath("a/../b"));
    TEST(!B::isSafeRelativePath("a//b"));
    TEST(!B::isSafeRelativePath("a/"));
    TEST(!B::isSafeRelativePath(std::string("a\tb")));
    TEST(!B::isSafeRelativePath(std::string(1025, 'x')));
    TEST(B::isSafeRelativePath(std::string(1024, 'x')));
}

void test_successReport(const fs::path& fixture) {
    openpulse::Analyzer a;
    openpulse::ReportV2Builder b;
    auto report = b.build(a.collectFacts(fixture), "fixture",
                          "task_test", "2026-09-25T12:00:00Z");

    TEST(report["protocolVersion"] == "2.0");
    TEST(report["taskId"] == "task_test");
    TEST(report["generatedAt"] == "2026-09-25T12:00:00Z");
    TEST(report["status"] == "SUCCESS");
    TEST(report["reviewability"] == "COMPLETE");
    TEST(report["limitations"].empty());
    TEST(report["findings"].empty());  // fixture has README, LICENSE and CI
    TEST(report["repository"]["root"] == ".");
    TEST(report["repository"]["name"] == "fixture");
    TEST(!report["repository"].contains("path"));
    TEST(report["ruleSet"]["id"] == openpulse::kRuleSetId);
    TEST(report["ruleSet"]["version"] == openpulse::kRuleSetVersion);

    // v1-only fields must not leak into v2
    TEST(!report.contains("quality"));
    TEST(!report.contains("risks"));
    TEST(!report.contains("dependencies"));

    // Summary shape
    for (const char* key : {"totalFiles", "sourceFiles", "documentFiles",
                            "configFiles", "testFiles", "totalLines",
                            "codeLines", "commentLines", "blankLines"}) {
        TEST(report["summary"].contains(key));
        TEST(report["summary"][key].is_number_unsigned());
    }
    size_t total = report["summary"]["totalLines"];
    size_t parts = report["summary"]["codeLines"].get<size_t>() +
                   report["summary"]["commentLines"].get<size_t>() +
                   report["summary"]["blankLines"].get<size_t>();
    TEST(parts <= total);
}

void test_repositoryFindingsShape() {
    EmptyDir empty;
    openpulse::Analyzer a;
    openpulse::ReportV2Builder b;
    auto report = b.build(a.collectFacts(empty.path), "empty",
                          "task_test", "2026-09-25T12:00:00Z");

    TEST(report["status"] == "SUCCESS");
    TEST(report["findings"].size() == 3);

    std::string previousId;
    for (const auto& f : report["findings"]) {
        TEST(f["type"] == "PROJECT_STRUCTURE");
        TEST(f["scope"] == "REPOSITORY");
        TEST(f["location"].is_null());
        TEST(f["evidence"]["kind"] == "EXPECTED_PATHS_ABSENT");
        TEST(f["evidence"]["expectedPaths"].size() >= 1);
        TEST(!f["remediation"].get<std::string>().empty());
        TEST(!f["message"].get<std::string>().empty());
        TEST(std::regex_match(f["findingId"].get<std::string>(),
                              std::regex(R"(^sha256:[0-9a-f]{64}$)")));

        // expectedPaths match the catalog candidates, protocol-sorted
        const auto* rule = openpulse::findRuleDefinition(f["ruleId"]);
        TEST(rule != nullptr);
        TEST(f["evidence"]["expectedPaths"] == rule->candidatePaths);

        // findingId can be recomputed from the report content alone
        TEST(f["findingId"] == openpulse::ReportV2Builder::computeFindingId(
                 f["ruleId"], "REPOSITORY", "-", "-", f["evidence"]));

        // sorted by findingId
        if (!previousId.empty()) {
            TEST(previousId < f["findingId"].get<std::string>());
        }
        previousId = f["findingId"];
    }
}

void test_sorting() {
    openpulse::ScanFacts facts;
    facts.languages = {{"Rust", 1, 10}, {"Java", 2, 20}, {"C++", 3, 30}};
    facts.structure.buildFiles = {"pom.xml", "CMakeLists.txt", "go.mod"};
    facts.structure.hasReadme = true;
    facts.structure.hasLicense = true;
    facts.structure.hasCi = true;

    openpulse::ReportV2Builder b;
    auto report = b.build(facts, "repo", "task_test", "2026-09-25T12:00:00Z");

    std::vector<std::string> names;
    for (const auto& l : report["languages"]) names.push_back(l["name"]);
    TEST(names == std::vector<std::string>({"C++", "Java", "Rust"}));

    std::vector<std::string> buildFiles;
    for (const auto& bf : report["structure"]["buildFiles"]) buildFiles.push_back(bf);
    TEST(buildFiles == std::vector<std::string>({"CMakeLists.txt", "go.mod", "pom.xml"}));

    TEST(report["status"] == "SUCCESS");
    TEST(report["findings"].empty());
}

void test_noMachinePaths(const fs::path& fixture) {
    openpulse::Analyzer a;
    openpulse::ReportV2Builder b;
    auto report = b.build(a.collectFacts(fixture), "fixture",
                          "task_test", "2026-09-25T12:00:00Z");
    std::string dump = report.dump();

    TEST(dump.find(fs::absolute(fixture).string()) == std::string::npos);
    TEST(dump.find(fs::absolute(fixture).generic_string()) == std::string::npos);
    TEST(dump.find('\\') == std::string::npos);
    const char* userprofile = std::getenv("USERPROFILE");
    if (userprofile && userprofile[0] != '\0') {
        TEST(dump.find(userprofile) == std::string::npos);
    }
    const char* home = std::getenv("HOME");
    if (home && home[0] != '\0') {
        TEST(dump.find(home) == std::string::npos);
    }
}

void test_determinism(const fs::path& fixture) {
    openpulse::Analyzer a;
    openpulse::ReportV2Builder b;
    auto first = b.build(a.collectFacts(fixture), "fixture",
                         "task_a", "2026-09-25T12:00:00Z");
    auto second = b.build(a.collectFacts(fixture), "fixture",
                          "task_b", "2026-09-25T13:00:00Z");

    first.erase("taskId");
    first.erase("generatedAt");
    second.erase("taskId");
    second.erase("generatedAt");
    TEST(first.dump() == second.dump());

    // No indentation: compact canonical output
    TEST(first.dump().find('\n') == std::string::npos);
}

void test_partialSuccess() {
    openpulse::ScanFacts facts = factsWithSkips({
        {"b/broken.py", "READ_ERROR"},
        {"a/locked.bin", "PERMISSION_DENIED"},
        {"a/also.bin", "READ_ERROR"},
    });
    facts.stats.totalFiles = 3;

    openpulse::ReportV2Builder b;
    auto report = b.build(facts, "repo", "task_test", "2026-09-25T12:00:00Z");

    TEST(report["status"] == "PARTIAL_SUCCESS");
    TEST(report["reviewability"] == "PARTIAL");
    TEST(report.contains("summary"));
    TEST(report.contains("languages"));
    TEST(report.contains("structure"));

    const auto& lims = report["limitations"];
    TEST(lims.size() == 3);
    // sorted by kind U+001F relativePath U+001F reason
    TEST(lims[0]["relativePath"] == "a/also.bin");
    TEST(lims[0]["reason"] == "READ_ERROR");
    TEST(lims[1]["relativePath"] == "a/locked.bin");
    TEST(lims[1]["reason"] == "PERMISSION_DENIED");
    TEST(lims[2]["relativePath"] == "b/broken.py");
    for (const auto& lim : lims) {
        TEST(lim["kind"] == "FILE_SKIPPED");
        TEST(lim["scope"] == "FILE");
        TEST(!lim["message"].get<std::string>().empty());
        TEST(lim["message"].get<std::string>().size() <= 300);
    }
}

void test_failedReport() {
    openpulse::ReportV2Builder b;
    auto report = b.buildFailed("TRAVERSAL_ERROR", "repo",
                                "task_test", "2026-09-25T12:00:00Z");

    TEST(report["protocolVersion"] == "2.0");
    TEST(report["status"] == "FAILED");
    TEST(report["reviewability"] == "NOT_USABLE");
    TEST(report["findings"].empty());
    TEST(!report.contains("summary"));
    TEST(!report.contains("languages"));
    TEST(!report.contains("structure"));

    const auto& lims = report["limitations"];
    TEST(lims.size() == 1);
    TEST(lims[0]["kind"] == "SCAN_FAILED");
    TEST(lims[0]["scope"] == "REPOSITORY");
    TEST(lims[0]["reason"] == "TRAVERSAL_ERROR");
    TEST(!lims[0]["message"].get<std::string>().empty());

    bool threw = false;
    try {
        b.buildFailed("SOME_OTHER_REASON", "repo", "task_test", "2026-09-25T12:00:00Z");
    } catch (const openpulse::ReportConstructionError&) {
        threw = true;
    }
    TEST(threw);
}

void test_duplicateStableKeysRejected() {
    openpulse::ReportV2Builder b;

    // Duplicate limitation stable key
    bool threw = false;
    try {
        b.build(factsWithSkips({{"a.txt", "READ_ERROR"}, {"a.txt", "READ_ERROR"}}),
                "repo", "task_test", "2026-09-25T12:00:00Z");
    } catch (const openpulse::ReportConstructionError&) {
        threw = true;
    }
    TEST(threw);

    // Duplicate language name
    threw = false;
    try {
        openpulse::ScanFacts facts;
        facts.languages = {{"Java", 1, 1}, {"Java", 2, 2}};
        b.build(facts, "repo", "task_test", "2026-09-25T12:00:00Z");
    } catch (const openpulse::ReportConstructionError&) {
        threw = true;
    }
    TEST(threw);

    // Duplicate build file entry
    threw = false;
    try {
        openpulse::ScanFacts facts;
        facts.structure.buildFiles = {"pom.xml", "pom.xml"};
        b.build(facts, "repo", "task_test", "2026-09-25T12:00:00Z");
    } catch (const openpulse::ReportConstructionError&) {
        threw = true;
    }
    TEST(threw);
}

void test_unsafePathsRejected() {
    openpulse::ReportV2Builder b;

    bool threw = false;
    try {
        b.build(factsWithSkips({{"../escape.txt", "READ_ERROR"}}),
                "repo", "task_test", "2026-09-25T12:00:00Z");
    } catch (const openpulse::ReportConstructionError&) {
        threw = true;
    }
    TEST(threw);

    threw = false;
    try {
        openpulse::ScanFacts facts;
        facts.structure.buildFiles = {"C:/abs/pom.xml"};
        b.build(facts, "repo", "task_test", "2026-09-25T12:00:00Z");
    } catch (const openpulse::ReportConstructionError&) {
        threw = true;
    }
    TEST(threw);
}

} // anonymous namespace

int main() {
    std::cout << "=== openpulse-analyzer protocol v2 unit tests ===\n" << std::endl;

    fs::path fixture = fixtureDir();
    if (!fs::exists(fixture)) {
        std::cerr << "ERROR: fixture directory not found: " << fixture.string() << std::endl;
        return 2;
    }
    std::cout << "Fixture: " << fixture.string() << "\n" << std::endl;

    test_versionInjection();
    test_ruleCatalog();
    test_sha256KnownVector();
    test_findingIdFixedVectors();
    test_safeRelativePath();
    test_successReport(fixture);
    test_repositoryFindingsShape();
    test_sorting();
    test_noMachinePaths(fixture);
    test_determinism(fixture);
    test_partialSuccess();
    test_failedReport();
    test_duplicateStableKeysRejected();
    test_unsafePathsRejected();

    std::cout << "\n---\n";
    std::cout << "Passed: " << g_passed << ", Failed: " << g_failed << std::endl;

    return g_failed > 0 ? 1 : 0;
}
