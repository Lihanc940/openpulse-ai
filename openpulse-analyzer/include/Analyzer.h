#pragma once

#include <filesystem>
#include <map>
#include <string>
#include <vector>
#include <nlohmann/json.hpp>

namespace openpulse {

struct AnalyzerConfig {
    std::filesystem::path repoPath;
    std::filesystem::path outputPath;
};

struct FileStats {
    size_t totalFiles = 0;
    size_t sourceFiles = 0;
    size_t documentFiles = 0;
    size_t configFiles = 0;
    size_t testFiles = 0;
    size_t totalLines = 0;
    size_t codeLines = 0;
    size_t commentLines = 0;
    size_t blankLines = 0;
    size_t skippedDirs = 0;
    size_t unreadableFiles = 0;
};

struct LangStats {
    std::string name;
    size_t files = 0;
    size_t lines = 0;
};

struct StructureCheck {
    bool hasReadme = false;
    bool hasLicense = false;
    bool hasContributing = false;
    bool hasChangelog = false;
    bool hasCi = false;
    bool hasTests = false;
    bool hasDockerfile = false;
    std::vector<std::string> buildFiles;
};

class Analyzer {
public:
    nlohmann::json generateReport(const AnalyzerConfig& config) const;

private:
    // Scanning
    FileStats scanDirectory(const std::filesystem::path& root) const;
    std::vector<LangStats> detectLanguages(const std::filesystem::path& root) const;
    StructureCheck checkStructure(const std::filesystem::path& root) const;

    // Helpers
    bool shouldSkipDir(const std::filesystem::path& dir) const;
    std::string languageFromExtension(const std::string& ext) const;
    bool isSourceFile(const std::string& ext) const;
    bool isDocumentFile(const std::string& ext) const;
    bool isConfigFile(const std::string& ext) const;
    bool isTestFile(const std::filesystem::path& path) const;

    // JSON builders
    nlohmann::json buildRepository(const std::filesystem::path& path) const;
    nlohmann::json buildSummary(const FileStats& stats) const;
    nlohmann::json buildLanguages(const std::vector<LangStats>& langs) const;
    nlohmann::json buildStructure(const StructureCheck& check) const;
    nlohmann::json buildQuality() const;
    nlohmann::json buildRisks(const StructureCheck& check) const;
    nlohmann::json buildDependencies() const;
    std::string generateTaskId() const;
    std::string generateTimestamp() const;
};

} // namespace openpulse
