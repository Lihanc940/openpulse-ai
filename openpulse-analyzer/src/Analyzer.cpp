#include "Analyzer.h"

#include <algorithm>
#include <chrono>
#include <cmath>
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

// ---- Comment style helpers ------------------------------------------------

bool isSlashStarExt(const std::string& ext) {
    // C-family extensions that support /* */ block comments
    static const std::set<std::string> EXTS = {
        ".c", ".cpp", ".cc", ".cxx", ".c++", ".h", ".hpp", ".hxx", ".hh",
        ".java", ".kt", ".kts", ".scala",
        ".js", ".jsx", ".ts", ".tsx", ".mjs", ".cjs",
        ".go", ".rs", ".swift", ".cs", ".php", ".dart",
        ".css", ".scss", ".less"
    };
    return EXTS.count(ext) > 0;
}

bool isHashExt(const std::string& ext) {
    // Script / config languages that use # for comments
    static const std::set<std::string> EXTS = {
        ".py", ".pyx", ".rb", ".sh", ".bash", ".pl", ".pm",
        ".r", ".lua", ".ps1", ".yaml", ".yml", ".toml",
        ".cfg", ".ini", ".cmake", ".sql"
    };
    return EXTS.count(ext) > 0;
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
    // Heuristic: filename or enclosing directory starts / ends with "test" or "spec"
    for (auto it = path.begin(); it != path.end(); ++it) {
        std::string seg = it->string();
        std::transform(seg.begin(), seg.end(), seg.begin(), ::tolower);
        if (seg == "test" || seg == "tests" || seg == "spec" || seg == "specs")
            return true;
    }
    // Also check filestem (filename without extension) for "test" / "spec" as prefix or suffix
    std::string stem = path.stem().string();
    std::transform(stem.begin(), stem.end(), stem.begin(), ::tolower);
    if (stem.starts_with("test") || stem.ends_with("test") ||
        stem.starts_with("spec") || stem.ends_with("spec"))
        return true;
    return false;
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
            // Skip unreadable entries, count one skipped entity
            if (it.depth() > 0 && !ec.default_error_condition()) {
                stats.skippedDirs++;
            }
            ++it;
            ec.clear();
            continue;
        }

        if (it->is_directory(ec) && shouldSkipDir(it->path())) {
            it.disable_recursion_pending();
            stats.skippedDirs++;
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

            // Count lines with comment-style detection
            std::ifstream file(it->path().string());
            if (file.is_open()) {
                std::string line;
                bool inBlock = false;  // inside /* */ block comment
                while (std::getline(file, line)) {
                    stats.totalLines++;

                    // Trim leading whitespace
                    size_t start = line.find_first_not_of(" \t\r");

                    if (start == std::string::npos) {
                        stats.blankLines++;
                        continue;
                    }

                    // Check for # comment first (script / config languages)
                    if (isHashExt(ext)) {
                        if (line[start] == '#') {
                            stats.commentLines++;
                        } else {
                            stats.codeLines++;
                        }
                        continue;
                    }

                    // C-family /* */ block comment languages
                    if (isSlashStarExt(ext)) {
                        // Handle this line within a block comment
                        if (inBlock) {
                            stats.commentLines++;
                            auto endPos = line.find("*/", start);
                            if (endPos != std::string::npos) {
                                inBlock = false;
                            }
                            continue;
                        }

                        // Check for // single-line comment
                        if (line.size() - start >= 2 && line[start] == '/' && line[start + 1] == '/') {
                            stats.commentLines++;
                            continue;
                        }

                        // Check for /* possibly on this line
                        auto blockStart = line.find("/*", start);
                        if (blockStart != std::string::npos) {
                            auto afterStart = blockStart + 2;
                            auto blockEnd = line.find("*/", afterStart);
                            if (blockEnd != std::string::npos) {
                                // /* and */ on same line — inline block comment
                                // Check if there is code before or after the comment
                                bool codeBefore = false;
                                for (size_t i = start; i < blockStart; ++i) {
                                    if (line[i] != ' ' && line[i] != '\t') {
                                        codeBefore = true;
                                        break;
                                    }
                                }
                                bool codeAfter = false;
                                for (size_t i = blockEnd + 2; i < line.size(); ++i) {
                                    if (line[i] != ' ' && line[i] != '\t') {
                                        codeAfter = true;
                                        break;
                                    }
                                }
                                if (codeBefore || codeAfter) {
                                    stats.codeLines++;
                                } else {
                                    stats.commentLines++;
                                }
                            } else {
                                // /* starts here but doesn't end — enter block mode
                                inBlock = true;
                                // Check code before /*
                                bool hasCode = false;
                                for (size_t i = start; i < blockStart; ++i) {
                                    if (line[i] != ' ' && line[i] != '\t') {
                                        hasCode = true;
                                        break;
                                    }
                                }
                                if (hasCode) {
                                    stats.codeLines++;
                                } else {
                                    stats.commentLines++;
                                }
                            }
                            continue;
                        }

                        // Plain code line
                        stats.codeLines++;
                        continue;
                    }

                    // Unknown type, treat as code
                    stats.codeLines++;
                }
                if (file.bad()) {
                    stats.unreadableFiles++;
                }
            } else {
                stats.unreadableFiles++;
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

    // Only include programming languages in the output.
    // Markup / config / data languages (Markdown, JSON, YAML, etc.) are
    // counted in summary.*Files but are not "languages" in the protocol sense.
    static const std::set<std::string> PROGRAMMING_LANGUAGES = {
        "C", "C++", "C/C++ Header",
        "Java", "Kotlin", "Scala",
        "Python", "Cython",
        "JavaScript", "TypeScript",
        "Go", "Rust", "Ruby", "PHP",
        "Swift", "C#", "F#",
        "Vue", "Svelte",
        "CSS", "SCSS", "Less",
        "SQL",
        "Shell", "PowerShell", "Batch",
        "Lua", "R", "Perl", "Dart", "Zig", "Nim"
    };

    // Sort by file count descending
    std::vector<LangStats> result;
    for (auto& [name, ls] : langMap) {
        if (PROGRAMMING_LANGUAGES.count(name) > 0) {
            result.push_back(std::move(ls));
        }
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

        // CI detection — look for well-known CI config files at repo root,
        // or check that .github/workflows directory exists (not just .github).
        if (lower == ".github") {
            if (entry.is_directory(ec)) {
                auto workflowsPath = entry.path() / "workflows";
                check.hasCi = std::filesystem::exists(workflowsPath, ec);
            }
        } else if (lower == ".gitlab-ci.yml" || lower == "jenkinsfile" ||
                   lower == ".circleci" || lower == ".travis.yml" ||
                   lower == "azure-pipelines.yml") {
            check.hasCi = true;
        }

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
        {"totalFiles",      stats.totalFiles},
        {"sourceFiles",     stats.sourceFiles},
        {"documentFiles",   stats.documentFiles},
        {"configFiles",     stats.configFiles},
        {"testFiles",       stats.testFiles},
        {"totalLines",      stats.totalLines},
        {"codeLines",       stats.codeLines},
        {"commentLines",    stats.commentLines},
        {"blankLines",      stats.blankLines}
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
    std::tm local_tm{};
#ifdef _WIN32
    localtime_s(&local_tm, &time);
#else
    localtime_r(&time, &local_tm);
#endif

    // Format date-time portion: YYYY-MM-DDTHH:MM:SS
    std::ostringstream oss;
    oss << std::put_time(&local_tm, "%Y-%m-%dT%H:%M:%S");

    // Compute UTC offset in seconds.
    // Interpret the local broken-down time as UTC via _mkgmtime/timegm;
    // the difference from the real UTC time gives the offset with sign.
    // Example UTC+8 (local ahead): _mkgmtime returns an epoch *larger* than
    // the true epoch → offset positive → formatted as +08:00.
    std::tm tm_for_offset = local_tm;
    long offset_seconds = 0;
#ifdef _WIN32
    auto utc_from_local = _mkgmtime(&tm_for_offset);
    if (utc_from_local != -1) {
        offset_seconds = static_cast<long>(std::difftime(utc_from_local, time));
    }
#else
    auto utc_from_local = timegm(&tm_for_offset);
    if (utc_from_local != -1) {
        offset_seconds = static_cast<long>(std::difftime(utc_from_local, time));
    }
#endif

    // Format offset as Z, +HH:MM, or -HH:MM
    if (offset_seconds == 0) {
        oss << 'Z';
    } else {
        long abs_offset = std::labs(offset_seconds);
        long hours   = abs_offset / 3600;
        long minutes = (abs_offset % 3600) / 60;
        oss << (offset_seconds > 0 ? '+' : '-')
            << std::setfill('0') << std::setw(2) << hours
            << ':'
            << std::setfill('0') << std::setw(2) << minutes;
    }
    return oss.str();
}

} // namespace openpulse
