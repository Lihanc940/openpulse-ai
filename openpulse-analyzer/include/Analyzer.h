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

// A file or directory entry that could not be read during scanning.
// relativePath uses '/' separators and is relative to the scan root;
// reason is a protocol v2 FILE_SKIPPED reason (READ_ERROR / PERMISSION_DENIED).
struct SkippedFile {
    std::string relativePath;
    std::string reason;
};

// Result of the single shared traversal. Both the v1 and v2 report
// builders consume these facts; neither re-walks the directory tree.
struct ScanFacts {
    FileStats stats;
    std::vector<LangStats> languages; // aggregated for every file, unsorted
    StructureCheck structure;
    std::vector<SkippedFile> skippedFiles;
    bool traversalFailed = false;
};

class Analyzer {
public:
    // Protocol v1 report (default CLI output). Field set unchanged.
    nlohmann::json generateReport(const AnalyzerConfig& config) const;

    // Single shared traversal collecting all scanning facts.
    ScanFacts collectFacts(const std::filesystem::path& root) const;

    // Filters aggregated language stats down to programming languages,
    // preserving input order; callers apply the protocol-specific sort.
    static std::vector<LangStats> filterProgrammingLanguages(std::vector<LangStats> langs);

    std::string generateTaskId() const;
    std::string generateTimestamp() const;

#ifndef OPENPULSE_TEST
private:
#endif
    // Scanning wrappers kept for compatibility; all share collectFacts().
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
};

} // namespace openpulse
