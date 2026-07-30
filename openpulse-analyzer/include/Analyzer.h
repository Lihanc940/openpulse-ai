#pragma once

#include <filesystem>
#include <string>
#include <nlohmann/json.hpp>

namespace openpulse {

struct AnalyzerConfig {
    std::filesystem::path repoPath;
    std::filesystem::path outputPath;
};

class Analyzer {
public:
    nlohmann::json generateReport(const AnalyzerConfig& config) const;

private:
    nlohmann::json buildRepository(const std::filesystem::path& path) const;
    nlohmann::json buildSummary() const;
    nlohmann::json buildLanguages() const;
    nlohmann::json buildStructure() const;
    nlohmann::json buildQuality() const;
    nlohmann::json buildRisks() const;
    nlohmann::json buildDependencies() const;
    std::string generateTaskId() const;
    std::string generateTimestamp() const;
};

} // namespace openpulse
