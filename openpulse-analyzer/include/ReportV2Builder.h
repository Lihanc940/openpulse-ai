#pragma once

#include <stdexcept>
#include <string>

#include <nlohmann/json.hpp>

#include "Analyzer.h"

namespace openpulse {

// Thrown when a v2 report would violate a protocol invariant (unsafe path,
// duplicate stable key, out-of-range counter). Callers must treat this as a
// real scan failure, never as a successful report.
class ReportConstructionError : public std::runtime_error {
public:
    using std::runtime_error::runtime_error;
};

// Builds protocol v2 reports from collected scan facts and the rule
// catalog. Contains no scanning logic of its own.
class ReportV2Builder {
public:
    // Builds a SUCCESS or PARTIAL_SUCCESS report from collected facts.
    nlohmann::json build(const ScanFacts& facts, const std::string& repoName,
                         const std::string& taskId, const std::string& generatedAt) const;

    // Builds a minimal FAILED report. scanFailedReason is a protocol
    // SCAN_FAILED reason (INTERNAL_ERROR / TRAVERSAL_ERROR).
    nlohmann::json buildFailed(const std::string& scanFailedReason,
                               const std::string& repoName,
                               const std::string& taskId,
                               const std::string& generatedAt) const;

    // Protocol section 8: ruleId, scope, pathPart and locationPart joined
    // with single U+001F separators, followed by the canonical evidence
    // JSON, hashed as UTF-8 SHA-256 with a "sha256:" prefix.
    static std::string computeFindingId(const std::string& ruleId,
                                        const std::string& scope,
                                        const std::string& pathPart,
                                        const std::string& locationPart,
                                        const nlohmann::json& canonicalEvidence);

    static std::string sha256Hex(const std::string& utf8);

    // Protocol safe relative path: '/' separators, no leading '/', no drive
    // letters, no URI schemes, no backslashes, colons or control characters,
    // no empty, "." or ".." segments, 1..1024 bytes.
    static bool isSafeRelativePath(const std::string& path);
};

} // namespace openpulse
