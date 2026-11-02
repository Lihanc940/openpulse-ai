// openpulse-analyzer unit tests
//
// Minimal self-contained test harness — no external test framework needed.
// Each test is a function returning true (pass) / false (fail).

#include "Analyzer.h"
#include "ReportV2Builder.h"

#include <cassert>
#include <chrono>
#include <cstdlib>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <regex>
#include <stdexcept>
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

struct TestDirectory {
    fs::path path = fs::temp_directory_path() /
        ("openpulse-structure-" + std::to_string(
            std::chrono::steady_clock::now().time_since_epoch().count()));

    TestDirectory() {
        if (!fs::create_directory(path)) {
            throw std::runtime_error("Cannot create isolated structure fixture");
        }
    }

    ~TestDirectory() {
        std::error_code ec;
        fs::remove_all(path, ec);
    }

    void write(const fs::path& relative, const char* content = "fixture\n") const {
        fs::create_directories((path / relative).parent_path());
        std::ofstream stream(path / relative);
        stream << content;
        if (!stream) throw std::runtime_error("Cannot write structure fixture");
    }
};

// --- Tests ---

void test_shouldSkipDir() {
    openpulse::Analyzer a;
    TEST(a.shouldSkipDir(".git"));
    TEST(a.shouldSkipDir("node_modules"));
    TEST(a.shouldSkipDir("target"));
    TEST(a.shouldSkipDir("build"));
    TEST(a.shouldSkipDir("dist"));
    TEST(a.shouldSkipDir(".tools"));
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

void test_structure_nestedModules() {
    TestDirectory fixture;
    fixture.write("platform/pom.xml");
    fixture.write("platform/src/test/java/ServiceTest.java");
    fixture.write("analyzer/CMakeLists.txt");
    fixture.write("analyzer/tests/scanner.cpp");
    fixture.write("nested/package.json");
    fixture.write("platform/README.md");
    fixture.write("platform/LICENSE");
    fixture.write("platform/.github/workflows/ci.yml");
    fs::create_directories(fixture.path / "not-a-build/pom.xml");

    openpulse::Analyzer a;
    auto facts = a.collectFacts(fixture.path);
    const std::vector<std::string> expected = {
        "analyzer/CMakeLists.txt", "nested/package.json", "platform/pom.xml"
    };
    TEST(facts.stats.testFiles == 2);
    TEST(facts.structure.hasTests);
    TEST(facts.structure.buildFiles == expected);
    TEST(!facts.structure.hasReadme);
    TEST(!facts.structure.hasLicense);
    TEST(!facts.structure.hasCi);
    TEST(a.checkStructure(fixture.path).buildFiles == expected);
    auto report = a.generateReport({fixture.path, fixture.path / "unused.json"});
    TEST(report["structure"]["hasTests"] == true);
    TEST(report["structure"]["buildFiles"] == expected);
    openpulse::ReportV2Builder builder;
    auto v2 = builder.build(facts, "monorepo", "task_monorepo", "2026-11-02T00:00:00Z");
    TEST(v2["structure"] == report["structure"]);
}

void test_structure_excludesGeneratedModules() {
    TestDirectory fixture;
    for (const auto* directory : {"node_modules", "target", "build", "out", ".git", ".tools"}) {
        fixture.write(fs::path(directory) / "module/pom.xml");
        fixture.write(fs::path(directory) / "module/tests/Test.java");
    }
    fixture.write("src/main.cpp");
    openpulse::Analyzer a;
    auto facts = a.collectFacts(fixture.path);
    TEST(facts.stats.totalFiles == 1);
    TEST(facts.stats.testFiles == 0);
    TEST(!facts.structure.hasTests);
    TEST(facts.structure.buildFiles.empty());
}

void test_structure_testHeuristicsStayInsideRepository() {
    TestDirectory fixture;
    fixture.write("tests/repository/src/main.cpp");
    openpulse::Analyzer a;
    auto facts = a.collectFacts(fixture.path / "tests/repository");
    TEST(facts.stats.testFiles == 0);
    TEST(!facts.structure.hasTests);

    fixture.write("tests/repository/src/ServiceTest.java");
    facts = a.collectFacts(fixture.path / "tests/repository");
    TEST(facts.stats.testFiles == 1);
    TEST(facts.structure.hasTests);
}

void test_structure_emptyNestedTestsAndUnicodeBuildPaths() {
    TestDirectory fixture;
    const fs::path module = fs::path(std::u8string(u8"module-\u4e2d\u6587 space"));
    fixture.write(module / "pom.xml");
    fs::create_directories(fixture.path / module / "specs");
    openpulse::Analyzer a;
    auto facts = a.collectFacts(fixture.path);
    auto utf8Path = (module / "pom.xml").generic_u8string();
    const std::vector<std::string> expected = {
        std::string(utf8Path.begin(), utf8Path.end())
    };
    TEST(facts.stats.testFiles == 0);
    TEST(facts.structure.hasTests);
    TEST(facts.structure.buildFiles == expected);
    TEST(facts.skippedFiles.empty());
    openpulse::ReportV2Builder builder;
    auto report = builder.build(facts, "unicode", "task_unicode", "2026-11-02T00:00:00Z");
    TEST(report["structure"]["buildFiles"] == expected);
    TEST(report["status"] == "SUCCESS");
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

    // Timestamp must end with Z or ±HH:MM (ISO-8601 offset)
    std::string ts = report["generatedAt"];
    TEST(ts.find("T") != std::string::npos);
    // Matches strings that end with Z or +HH:MM or -HH:MM
    TEST(std::regex_search(ts, std::regex(R"((Z|[+-]\d{2}:\d{2})$)")));
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
    test_structure_nestedModules();
    test_structure_excludesGeneratedModules();
    test_structure_testHeuristicsStayInsideRepository();
    test_structure_emptyNestedTestsAndUnicodeBuildPaths();
    test_buildSummary_jsonFields(fixture);
    test_generateReport_smoke(fixture);

    std::cout << "\n---\n";
    std::cout << "Passed: " << g_passed << ", Failed: " << g_failed << std::endl;

    return g_failed > 0 ? 1 : 0;
}
