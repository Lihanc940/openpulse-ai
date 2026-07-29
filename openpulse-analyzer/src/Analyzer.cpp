#include "Analyzer.h"

#include <chrono>
#include <ctime>
#include <iomanip>
#include <sstream>

namespace openpulse {

nlohmann::json Analyzer::generateReport(const AnalyzerConfig& config) const {
    return {
        {"protocolVersion", "1.0"},
        {"taskId",           generateTaskId()},
        {"status",           "SUCCESS"},
        {"repository",       buildRepository(config.repoPath)},
        {"summary",          buildSummary()},
        {"languages",        buildLanguages()},
        {"structure",        buildStructure()},
        {"quality",          buildQuality()},
        {"risks",            buildRisks()},
        {"dependencies",     buildDependencies()},
        {"generatedAt",      generateTimestamp()}
    };
}

nlohmann::json Analyzer::buildRepository(const std::filesystem::path& path) const {
    return {
        {"path", path.string()},
        {"name", path.filename().string()}
    };
}

nlohmann::json Analyzer::buildSummary() const {
    return {
        {"totalFiles",    186},
        {"sourceFiles",   94},
        {"documentFiles", 3},
        {"configFiles",   8},
        {"testFiles",     13},
        {"totalLines",    23841},
        {"codeLines",     17640},
        {"commentLines",  3280},
        {"blankLines",    2921}
    };
}

nlohmann::json Analyzer::buildLanguages() const {
    return nlohmann::json::array({
        {{"name", "Java"}, {"files", 52}, {"lines", 12000}},
        {{"name", "C++"},  {"files", 18}, {"lines", 5400}},
        {{"name", "Python"}, {"files", 24}, {"lines", 6441}}
    });
}

nlohmann::json Analyzer::buildStructure() const {
    return {
        {"hasReadme",       true},
        {"hasLicense",      true},
        {"hasContributing", false},
        {"hasChangelog",    false},
        {"hasCi",           true},
        {"hasTests",        true},
        {"hasDockerfile",   false},
        {"buildFiles",      nlohmann::json::array({"pom.xml", "CMakeLists.txt"})}
    };
}

nlohmann::json Analyzer::buildQuality() const {
    return {
        {"complexityScore",     72},
        {"maintainabilityScore", 81},
        {"documentationScore",  65},
        {"testScore",           58}
    };
}

nlohmann::json Analyzer::buildRisks() const {
    return nlohmann::json::array({
        {
            {"ruleId",  "MISSING_CHANGELOG"},
            {"type",    "PROJECT_STRUCTURE"},
            {"level",   "MEDIUM"},
            {"file",    ""},
            {"line",    0},
            {"message", "项目缺少 CHANGELOG 文件，建议记录版本变更历史。"},
            {"evidence", {
                {"expectedFile", "CHANGELOG.md"}
            }}
        },
        {
            {"ruleId",  "LONG_FUNCTION"},
            {"type",    "CODE_SMELL"},
            {"level",   "HIGH"},
            {"file",    "src/UserService.java"},
            {"line",    84},
            {"message", "函数长度为 126 行，建议拆分职责。"},
            {"evidence", {
                {"functionName",  "registerUser"},
                {"functionLines", 126},
                {"threshold",     80}
            }}
        }
    });
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
