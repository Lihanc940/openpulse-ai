// openpulse-analyzer unit tests
//
// Minimal self-contained test harness — no external test framework needed.
// Each test is a function returning true (pass) / false (fail).

#include "Analyzer.h"

#include <cassert>
#include <cstdlib>
#include <filesystem>
#include <iostream>
#include <string>
#include <vector>

namespace fs = std::filesystem;

// --- Test helpers ---

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

// Macro to capture the expression as the test name
#define TEST(expr) check(#expr, (expr))

// Resolve the fixture directory at runtime.
// Default: <exe-dir>/../tests/fixture (when run from out/build/*/).
// Override: set OPENPULSE_TEST_FIXTURE env var.
fs::path fixtureDir() {
    const char* env = std::getenv("OPENPULSE_TEST_FIXTURE");
    if (env && env[0] != '\0') {
        return fs::path(env);
    }
    // best-effort relative to executable
    fs::path exe = fs::current_path();
    return fs::canonical(exe / "../../../tests/fixture");
}

// --- Tests ---

void test_shouldSkipDir() {
    openpulse::Analyzer a;
    TEST(a.shouldSkipDir(".git"));
    TEST(a.shouldSkipDir("node_modules"));
    TEST(a.shouldSkipDir("target"));
    TEST(a.shouldSkipDir("build"));
    TEST(a.shouldSkipDir("dist"));
    TEST(!a.shouldSkipDir("src"));
    TEST(!a.shouldSkipDir("include"));
    TEST(!a.shouldSkipDir("docs"));
}

void test_fileClassification() {
    openpulse::Analyzer a;
    TEST(a.isSourceFile(".cpp"));
    TEST(a.isSourceFile(".java"));
    TEST(a.isSourceFile(".py"));
    TEST(a.isSourceFile(".js"));
    TEST(a.isSourceFile(".go"));
    TEST(!a.isSourceFile(".md"));
    TEST(!a.isSourceFile(".json"));

    TEST(a.isDocumentFile(".md"));
    TEST(a.isDocumentFile(".rst"));
    TEST(!a.isDocumentFile(".cpp"));

    TEST(a.isConfigFile(".json"));
    TEST(a.isConfigFile(".yaml"));
    TEST(a.isConfigFile(".yml"));
    TEST(a.isConfigFile(".toml"));
    TEST(a.isConfigFile(".cmake"));
    TEST(!a.isConfigFile(".cpp"));
}

void test_isTestFile() {
    openpulse::Analyzer a;
    // by directory name
    TEST(a.isTestFile("test/foo.cpp"));
    TEST(a.isTestFile("tests/bar.py"));
    TEST(a.isTestFile("spec/baz.js"));
    TEST(a.isTestFile("specs/qux.go"));
    // nested under test directory
    TEST(a.isTestFile("project/test/unit/util.cpp"));
    // by filename prefix/suffix
    TEST(a.isTestFile("src/util.test.cpp"));
    TEST(a.isTestFile("src/TestUtil.java"));
    TEST(a.isTestFile("src/util_spec.js"));
    TEST(a.isTestFile("src/SpecHelper.rs"));
    // non-test files
    TEST(!a.isTestFile("src/main.cpp"));
    TEST(!a.isTestFile("include/header.h"));
}

void test_languageDetection() {
    openpulse::Analyzer a;
    TEST(a.languageFromExtension(".cpp") == "C++");
    TEST(a.languageFromExtension(".java") == "Java");
    TEST(a.languageFromExtension(".py") == "Python");
    TEST(a.languageFromExtension(".js") == "JavaScript");
    TEST(a.languageFromExtension(".ts") == "TypeScript");
    TEST(a.languageFromExtension(".md") == "Markdown");
    TEST(a.languageFromExtension(".json") == "JSON");
    TEST(a.languageFromExtension(".yaml") == "YAML");
    TEST(a.languageFromExtension(".xyz") == "Other");
}

void test_scanDirectory_basicStats(const fs::path& fixture) {
    openpulse::Analyzer a;
    auto stats = a.scanDirectory(fixture);

    // We created: README.md, LICENSE, src/main.cpp, src/util.py,
    //             src/config.json, src/util.spec.js, .github/workflows/ci.yml,
    //             CMakeLists.txt
    // Skipped: __pycache__/, bin/
    TEST(stats.totalFiles == 8);
    TEST(stats.sourceFiles >= 3);  // main.cpp, util.py, util.spec.js (js is source)
    TEST(stats.documentFiles >= 1); // README.md
    TEST(stats.configFiles >= 1);   // config.json, ci.yml (yml is config)
    TEST(stats.skippedDirs >= 2);   // __pycache__, bin
    TEST(stats.totalLines > 0);
    TEST(stats.codeLines > 0);
    TEST(stats.commentLines > 0);   // both cpp and py have comments
}

void test_scanDirectory_testFileDetection(const fs::path& fixture) {
    openpulse::Analyzer a;
    auto stats = a.scanDirectory(fixture);
    // util.spec.js should be detected as test file
    TEST(stats.testFiles >= 1);
}

void test_detectLanguages_programmingOnly(const fs::path& fixture) {
    openpulse::Analyzer a;
    auto langs = a.detectLanguages(fixture);

    // Must have programming languages
    bool hasCPP = false, hasPython = false, hasJS = false;
    for (const auto& l : langs) {
        if (l.name == "C++") hasCPP = true;
        if (l.name == "Python") hasPython = true;
        if (l.name == "JavaScript") hasJS = true;

        // Must NOT include non-programming languages
        TEST(l.name != "Markdown");
        TEST(l.name != "JSON");
        TEST(l.name != "YAML");
        TEST(l.name != "Other");
        TEST(l.name != "Plain Text");
    }
    TEST(hasCPP);
    TEST(hasPython);
    TEST(hasJS);
}

void test_checkStructure_allPresent(const fs::path& fixture) {
    openpulse::Analyzer a;
    auto s = a.checkStructure(fixture);

    TEST(s.hasReadme == true);
    TEST(s.hasLicense == true);
    TEST(s.hasCi == true);
    TEST(!s.buildFiles.empty());
}

void test_checkStructure_allMissing(const fs::path& fixture) {
    // Create a minimal empty directory — everything should be absent
    auto emptyDir = fixture / "_empty_test";
    fs::create_directory(emptyDir);

    openpulse::Analyzer a;
    auto s = a.checkStructure(emptyDir);

    TEST(s.hasReadme == false);
    TEST(s.hasLicense == false);
    TEST(s.hasCi == false);

    fs::remove(emptyDir);
}

void test_buildSummary_jsonFields(const fs::path& fixture) {
    openpulse::Analyzer a;
    auto stats = a.scanDirectory(fixture);
    auto summary = a.buildSummary(stats);

    // Protocol fields must exist
    TEST(summary.contains("totalFiles"));
    TEST(summary.contains("sourceFiles"));
    TEST(summary.contains("documentFiles"));
    TEST(summary.contains("configFiles"));
    TEST(summary.contains("testFiles"));
    TEST(summary.contains("totalLines"));
    TEST(summary.contains("codeLines"));
    TEST(summary.contains("commentLines"));
    TEST(summary.contains("blankLines"));

    // Protocol fields that must NOT exist (added post-v1)
    TEST(!summary.contains("skippedDirs"));
    TEST(!summary.contains("unreadableFiles"));

    // Types
    TEST(summary["totalFiles"].is_number_unsigned());
    TEST(summary["totalLines"].is_number_unsigned());
}

void test_buildRisks_withMissingItems() {
    openpulse::StructureCheck sc;  // all false
    openpulse::Analyzer a;
    auto risks = a.buildRisks(sc);
    TEST(risks.is_array());
    TEST(risks.size() == 3);  // MISSING_README + MISSING_LICENSE + MISSING_CI

    // MISSING_README
    TEST(risks[0]["ruleId"] == "MISSING_README");
    TEST(risks[0]["type"] == "PROJECT_STRUCTURE");
    TEST(risks[0]["level"] == "MEDIUM");

    // MISSING_CI
    TEST(risks[2]["ruleId"] == "MISSING_CI");
    TEST(risks[2]["level"] == "LOW");
}

void test_buildRisks_allPresent() {
    openpulse::StructureCheck sc;
    sc.hasReadme = true;
    sc.hasLicense = true;
    sc.hasCi = true;

    openpulse::Analyzer a;
    auto risks = a.buildRisks(sc);
    TEST(risks.is_array());
    TEST(risks.size() == 0);
}

void test_generateReport_smoke(const fs::path& fixture) {
    openpulse::Analyzer a;
    openpulse::AnalyzerConfig cfg{fixture, fixture / "_test_report.json"};
    auto report = a.generateReport(cfg);

    // Top-level fields
    TEST(report["protocolVersion"] == "1.0");
    TEST(report.contains("taskId"));
    TEST(report["status"] == "SUCCESS");
    TEST(report.contains("repository"));
    TEST(report.contains("summary"));
    TEST(report.contains("languages"));
    TEST(report.contains("structure"));
    TEST(report.contains("quality"));
    TEST(report.contains("risks"));
    TEST(report.contains("dependencies"));
    TEST(report.contains("generatedAt"));

    // Repository
    TEST(report["repository"].contains("path"));
    TEST(report["repository"].contains("name"));
    TEST(report["repository"]["name"] == fixture.filename().string());

    // No non-protocol fields in summary
    TEST(!report["summary"].contains("skippedDirs"));
    TEST(!report["summary"].contains("unreadableFiles"));

    // Languages must be programming only
    for (const auto& lang : report["languages"]) {
        std::string name = lang["name"];
        TEST(name != "Markdown");
        TEST(name != "JSON");
        TEST(name != "YAML");
        TEST(name != "Other");
    }

    // Timestamp should be ISO 8601 with timezone
    std::string ts = report["generatedAt"];
    TEST(ts.find("T") != std::string::npos);
    TEST(ts.find("+") != std::string::npos || ts.find("-") != std::string::npos);
}

// --- Run-all driver ---

int main() {
    std::cout << "=== openpulse-analyzer unit tests ===\n" << std::endl;

    fs::path fixture;
    try {
        fixture = fixtureDir();
        std::cout << "Fixture: " << fixture.string() << "\n" << std::endl;
    } catch (const fs::filesystem_error& e) {
        std::cerr << "ERROR: cannot resolve fixture directory.\n"
                  << "  Set OPENPULSE_TEST_FIXTURE or run from out/build/*.\n"
                  << "  " << e.what() << std::endl;
        return 2;
    }

    if (!fs::exists(fixture)) {
        std::cerr << "ERROR: fixture directory not found: " << fixture.string() << std::endl;
        return 2;
    }

    // Tests that don't need fixture
    test_shouldSkipDir();
    test_fileClassification();
    test_isTestFile();
    test_languageDetection();
    test_buildRisks_withMissingItems();
    test_buildRisks_allPresent();

    // Tests that need the fixture
    test_scanDirectory_basicStats(fixture);
    test_scanDirectory_testFileDetection(fixture);
    test_detectLanguages_programmingOnly(fixture);
    test_checkStructure_allPresent(fixture);
    test_checkStructure_allMissing(fixture);
    test_buildSummary_jsonFields(fixture);
    test_generateReport_smoke(fixture);

    std::cout << "\n---\n";
    std::cout << "Passed: " << g_passed << ", Failed: " << g_failed << std::endl;

    return g_failed > 0 ? 1 : 0;
}
