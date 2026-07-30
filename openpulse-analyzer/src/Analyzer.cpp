#include "Analyzer.h"

#include <algorithm>
#include <chrono>
#include <ctime>
#include <fstream>
#include <iomanip>
#include <set>
#include <sstream>

namespace openpulse {

namespace {

const std::set<std::string> SKIP_DIRS = {
    ".git", "node_modules", "target", "build", "dist",
    ".vs", "out", ".idea", "__pycache__", ".mvn",
    "bin", "obj"  // C# / VS build outputs
};

const std::set<std::string> DOC_EXTS = {".md", ".rst", ".txt", ".adoc"};
const std::set<std::string> CONFIG_EXTS = {
    ".json", ".yaml", ".yml", ".toml", ".ini", ".cfg",
    ".properties", ".xml", ".gradle", ".cmake"
};
const std::set<std::string> SOURCE_EXTS = {
    ".c", ".cpp", ".cc", ".cxx", ".c++", ".h", ".hpp", ".hxx", ".hh",
    ".java", ".kt", ".kts", ".scala",
    ".py", ".pyx",
    ".js", ".jsx", ".ts", ".tsx", ".mjs", ".cjs",
    ".go", ".rs", ".rb", ".php", ".swift", ".cs", ".fs",
    ".vue", ".svelte", ".css", ".scss", ".less",
    ".sql", ".sh", ".bash", ".ps1", ".bat", ".cmd",
    ".lua", ".r", ".pl", ".pm", ".dart", ".zig", ".nim"
};

std::string lowerExt(const std::filesystem::path& path) {
    std::string ext = path.extension().string();
    std::transform(ext.begin(), ext.end(), ext.begin(), ::tolower);
    return ext;
}

std::string languageFromExt(const std::string& ext) {
    static const std::map<std::string, std::string> LANG_MAP = {
        {".c", "C"}, {".cpp", "C++"}, {".cc", "C++"}, {".cxx", "C++"}, {".c++", "C++"},
        {".h", "C/C++ Header"}, {".hpp", "C/C++ Header"}, {".hxx", "C/C++ Header"}, {".hh", "C/C++ Header"},
        {".java", "Java"}, {".kt", "Kotlin"}, {".kts", "Kotlin"},
        {".scala", "Scala"},
        {".py", "Python"}, {".pyx", "Cython"},
        {".js", "JavaScript"}, {".jsx", "JavaScript"}, {".mjs", "JavaScript"}, {".cjs", "JavaScript"},
        {".ts", "TypeScript"}, {".tsx", "TypeScript"},
        {".go", "Go"}, {".rs", "Rust"}, {".rb", "Ruby"}, {".php", "PHP"},
        {".swift", "Swift"}, {".cs", "C#"}, {".fs", "F#"},
        {".vue", "Vue"}, {".svelte", "Svelte"},
        {".css", "CSS"}, {".scss", "SCSS"}, {".less", "Less"},
        {".sql", "SQL"}, {".sh", "Shell"}, {".bash", "Shell"}, {".ps1", "PowerShell"},
        {".bat", "Batch"}, {".cmd", "Batch"},
        {".lua", "Lua"}, {".r", "R"}, {".pl", "Perl"}, {".pm", "Perl"},
        {".dart", "Dart"}, {".zig", "Zig"}, {".nim", "Nim"},
        {".md", "Markdown"}, {".rst", "reStructuredText"}, {".txt", "Plain Text"},
        {".json", "JSON"}, {".yaml", "YAML"}, {".yml", "YAML"}, {".toml", "TOML"},
        {".ini", "INI"}, {".cfg", "Config"}, {".properties", "Properties"},
        {".xml", "XML"}, {".gradle", "Gradle"}, {".cmake", "CMake"}
    };
    auto it = LANG_MAP.find(ext);
    return it != LANG_MAP.end() ? it->second : "Other";
}

} // anonymous namespace

// --- Public interface ---

nlohmann::json Analyzer::generateReport(const AnalyzerConfig& config) const {
    FileStats stats = scanDirectory(config.repoPath);
    std::vector<LangStats> langs = detectLanguages(config.repoPath);
    StructureCheck structure = checkStructure(config.repoPath);

    return {
        {"protocolVersion", "1.0"},
        {"taskId",          generateTaskId()},
        {"status",          "SUCCESS"},
        {"repository",      buildRepository(config.repoPath)},
        {"summary",         buildSummary(stats)},
        {"languages",       buildLanguages(langs)},
        {"structure",       buildStructure(structure)},
        {"quality",         buildQuality()},
        {"risks",           buildRisks(structure)},
        {"dependencies",    buildDependencies()},
        {"generatedAt",     generateTimestamp()}
    };
}

// --- Directory scanning ---

bool Analyzer::shouldSkipDir(const std::filesystem::path& dir) const {
    auto name = dir.filename().string();
    return SKIP_DIRS.count(name) > 0;
}

bool Analyzer::isSourceFile(const std::string& ext) const {
    return SOURCE_EXTS.count(ext) > 0;
}

bool Analyzer::isDocumentFile(const std::string& ext) const {
    return DOC_EXTS.count(ext) > 0;
}

bool Analyzer::isConfigFile(const std::string& ext) const {
    return CONFIG_EXTS.count(ext) > 0;
}

bool Analyzer::isTestFile(const std::filesystem::path& path) const {
    auto p = path.string();
    std::transform(p.begin(), p.end(), p.begin(), ::tolower);
    // Test file heuristics: path contains "test" or filename starts/ends with "test"
    return p.find("test") != std::string::npos ||
           p.find("spec") != std::string::npos;
}

std::string Analyzer::languageFromExtension(const std::string& ext) const {
    return languageFromExt(ext);
}

FileStats Analyzer::scanDirectory(const std::filesystem::path& root) const {
    FileStats stats;
    std::error_code ec;

    for (auto it = std::filesystem::recursive_directory_iterator(root, ec);
         it != std::filesystem::recursive_directory_iterator(); ) {
        if (ec) {
            // Skip unreadable entries
            ++it;
            ec.clear();
            continue;
        }

        if (it->is_directory(ec) && shouldSkipDir(it->path())) {
            it.disable_recursion_pending();
            ++it;
            continue;
        }

        if (it->is_regular_file(ec)) {
            std::string ext = lowerExt(it->path());
            stats.totalFiles++;

            if (isSourceFile(ext)) {
                stats.sourceFiles++;
            } else if (isDocumentFile(ext)) {
                stats.documentFiles++;
            } else if (isConfigFile(ext)) {
                stats.configFiles++;
            }

            if (isTestFile(it->path())) {
                stats.testFiles++;
            }

            // Count lines
            std::ifstream file(it->path().string());
            if (file.is_open()) {
                std::string line;
                while (std::getline(file, line)) {
                    stats.totalLines++;

                    // Trim whitespace
                    size_t start = line.find_first_not_of(" \t\r");
                    if (start == std::string::npos) {
                        stats.blankLines++;
                    } else if (line[start] == '#') {
                        stats.commentLines++;
                    } else if (ext == ".cpp" || ext == ".cc" || ext == ".cxx" || ext == ".c++" ||
                               ext == ".c" || ext == ".h" || ext == ".hpp" || ext == ".hxx" ||
                               ext == ".java" || ext == ".kt" || ext == ".swift" ||
                               ext == ".cs" || ext == ".go" || ext == ".rs" ||
                               ext == ".js" || ext == ".jsx" || ext == ".ts" || ext == ".tsx" ||
                               ext == ".scala" || ext == ".dart" || ext == ".php") {
                        if (line.substr(start, 2) == "//") {
                            stats.commentLines++;
                        } else {
                            stats.codeLines++;
                        }
                    } else if (ext == ".py" || ext == ".pyx" || ext == ".rb" ||
                               ext == ".sh" || ext == ".bash" || ext == ".pl" ||
                               ext == ".r" || ext == ".lua" || ext == ".ps1" ||
                               ext == ".yaml" || ext == ".yml" || ext == ".toml" ||
                               ext == ".cfg" || ext == ".ini" || ext == ".cmake" ||
                               ext == ".sql") {
                        if (line[start] == '#') {
                            stats.commentLines++;
                        } else {
                            stats.codeLines++;
                        }
                    } else {
                        // Unknown type, treat as code
                        stats.codeLines++;
                    }
                }
            }
        }

        ++it;
    }

    return stats;
}

// --- Language detection ---

std::vector<LangStats> Analyzer::detectLanguages(const std::filesystem::path& root) const {
    std::map<std::string, LangStats> langMap;
    std::error_code ec;

    for (auto it = std::filesystem::recursive_directory_iterator(root, ec);
         it != std::filesystem::recursive_directory_iterator(); ) {
        if (ec) { ++it; ec.clear(); continue; }

        if (it->is_directory(ec) && shouldSkipDir(it->path())) {
            it.disable_recursion_pending();
            ++it;
            continue;
        }

        if (it->is_regular_file(ec)) {
            std::string ext = lowerExt(it->path());
            std::string lang = languageFromExt(ext);

            auto& ls = langMap[lang];
            ls.name = lang;
            ls.files++;

            // Quick line count
            std::ifstream file(it->path().string());
            if (file.is_open()) {
                std::string line;
                while (std::getline(file, line)) {
                    ls.lines++;
                }
            }
        }

        ++it;
    }

    // Sort by file count descending
    std::vector<LangStats> result;
    for (auto& [name, ls] : langMap) {
        result.push_back(std::move(ls));
    }
    std::sort(result.begin(), result.end(),
              [](const LangStats& a, const LangStats& b) { return a.files > b.files; });

    return result;
}

// --- Structure checks ---

StructureCheck Analyzer::checkStructure(const std::filesystem::path& root) const {
    StructureCheck check;
    std::error_code ec;

    for (const auto& entry : std::filesystem::directory_iterator(root, ec)) {
        if (ec) break;
        std::string name = entry.path().filename().string();
        std::string lower;
        lower.reserve(name.size());
        std::transform(name.begin(), name.end(), std::back_inserter(lower), ::tolower);

        if (lower.find("readme") != std::string::npos) check.hasReadme = true;
        if (lower.find("license") != std::string::npos || lower.find("licence") != std::string::npos)
            check.hasLicense = true;
        if (lower.find("contributing") != std::string::npos) check.hasContributing = true;
        if (lower.find("changelog") != std::string::npos) check.hasChangelog = true;
        if (lower == "dockerfile" || lower.find(".dockerfile") != std::string::npos)
            check.hasDockerfile = true;

        // CI detection
        if (lower == ".github" || lower == ".gitlab-ci.yml" || lower == "jenkinsfile" ||
            lower == ".circleci" || lower == ".travis.yml" || lower == "azure-pipelines.yml")
            check.hasCi = true;

        // Build files
        static const std::set<std::string> BUILD_FILES = {
            "pom.xml", "build.gradle", "build.gradle.kts", "settings.gradle",
            "CMakeLists.txt", "Makefile", "makefile", "GNUmakefile",
            "package.json", "Cargo.toml", "go.mod", "requirements.txt",
            "setup.py", "setup.cfg", "pyproject.toml"
        };
        if (BUILD_FILES.count(name) > 0) {
            check.buildFiles.push_back(name);
        }

        // Test directory
        if (entry.is_directory(ec) && (lower == "test" || lower == "tests" || lower == "spec"))
            check.hasTests = true;
    }

    return check;
}

// --- JSON builders ---

nlohmann::json Analyzer::buildRepository(const std::filesystem::path& path) const {
    std::filesystem::path resolved;
    std::error_code ec;
    // canonical requires the path to exist; fall back to absolute
    resolved = std::filesystem::canonical(path, ec);
    if (ec) {
        resolved = std::filesystem::absolute(path, ec);
    }
    return {
        {"path", resolved.string()},
        {"name", resolved.filename().string()}
    };
}

nlohmann::json Analyzer::buildSummary(const FileStats& stats) const {
    return {
        {"totalFiles",    stats.totalFiles},
        {"sourceFiles",   stats.sourceFiles},
        {"documentFiles", stats.documentFiles},
        {"configFiles",   stats.configFiles},
        {"testFiles",     stats.testFiles},
        {"totalLines",    stats.totalLines},
        {"codeLines",     stats.codeLines},
        {"commentLines",  stats.commentLines},
        {"blankLines",    stats.blankLines}
    };
}

nlohmann::json Analyzer::buildLanguages(const std::vector<LangStats>& langs) const {
    auto arr = nlohmann::json::array();
    for (const auto& l : langs) {
        arr.push_back({
            {"name",  l.name},
            {"files", l.files},
            {"lines", l.lines}
        });
    }
    return arr;
}

nlohmann::json Analyzer::buildStructure(const StructureCheck& check) const {
    return {
        {"hasReadme",       check.hasReadme},
        {"hasLicense",      check.hasLicense},
        {"hasContributing", check.hasContributing},
        {"hasChangelog",    check.hasChangelog},
        {"hasCi",           check.hasCi},
        {"hasTests",        check.hasTests},
        {"hasDockerfile",   check.hasDockerfile},
        {"buildFiles",      check.buildFiles}
    };
}

nlohmann::json Analyzer::buildQuality() const {
    return {
        {"complexityScore",     0},
        {"maintainabilityScore", 0},
        {"documentationScore",  0},
        {"testScore",           0}
    };
}

nlohmann::json Analyzer::buildRisks(const StructureCheck& check) const {
    auto risks = nlohmann::json::array();

    if (!check.hasReadme) {
        risks.push_back({
            {"ruleId",  "MISSING_README"},
            {"type",    "PROJECT_STRUCTURE"},
            {"level",   "MEDIUM"},
            {"file",    ""},
            {"line",    0},
            {"message", "项目缺少 README 文件，建议添加项目说明文档。"},
            {"evidence", {{"expectedFile", "README.md"}}}
        });
    }
    if (!check.hasLicense) {
        risks.push_back({
            {"ruleId",  "MISSING_LICENSE"},
            {"type",    "PROJECT_STRUCTURE"},
            {"level",   "MEDIUM"},
            {"file",    ""},
            {"line",    0},
            {"message", "项目缺少 LICENSE 文件，建议添加开源许可证。"},
            {"evidence", {{"expectedFile", "LICENSE"}}}
        });
    }
    if (!check.hasCi) {
        risks.push_back({
            {"ruleId",  "MISSING_CI"},
            {"type",    "PROJECT_STRUCTURE"},
            {"level",   "LOW"},
            {"file",    ""},
            {"line",    0},
            {"message", "项目缺少 CI/CD 配置文件，建议添加自动化构建流程。"},
            {"evidence", {}}
        });
    }

    return risks;
}

nlohmann::json Analyzer::buildDependencies() const {
    return {
        {"nodes", nlohmann::json::array()},
        {"edges", nlohmann::json::array()}
    };
}

std::string Analyzer::generateTaskId() const {
    auto now = std::chrono::system_clock::now();
    auto millis = std::chrono::duration_cast<std::chrono::milliseconds>(
        now.time_since_epoch()).count();
    return "task_" + std::to_string(millis);
}

std::string Analyzer::generateTimestamp() const {
    auto now = std::chrono::system_clock::now();
    auto time = std::chrono::system_clock::to_time_t(now);
    std::tm tm{};
#ifdef _WIN32
    localtime_s(&tm, &time);
#else
    localtime_r(&time, &tm);
#endif
    std::ostringstream oss;
    oss << std::put_time(&tm, "%Y-%m-%dT%H:%M:%S");
    char tz[10]{};
    std::strftime(tz, sizeof(tz), "%z", &tm);
    std::string tzStr(tz);
    if (tzStr.size() == 5) {
        tzStr.insert(3, ":");  // +0800 -> +08:00
    }
    oss << tzStr;
    return oss.str();
}

} // namespace openpulse
