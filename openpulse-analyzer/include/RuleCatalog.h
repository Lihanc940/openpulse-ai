#pragma once

#include <string>
#include <vector>

namespace openpulse {

// Rule set identity for the protocol v2 rule catalog.
inline constexpr char kRuleSetId[] = "openpulse-default";
inline constexpr char kRuleSetVersion[] = "1.0.0";

// Deterministic metadata for one rule. Only rules with a real scanner
// implementation may be registered here.
struct RuleDefinition {
    const char* ruleId;
    const char* type;         // protocol v2 finding type
    const char* severity;     // LOW / MEDIUM / HIGH / CRITICAL
    const char* evidenceKind; // approved evidence kind for this rule
    const char* message;      // fixed, deterministic finding message
    const char* remediation;  // fixed, non-empty remediation direction
    // Candidate paths the scanner actually checks, sorted by Unicode code
    // point order as required for EXPECTED_PATHS_ABSENT evidence.
    std::vector<std::string> candidatePaths;
};

// Fixed catalog of the three implemented PROJECT_STRUCTURE rules.
const std::vector<RuleDefinition>& ruleCatalog();

const RuleDefinition* findRuleDefinition(const std::string& ruleId);

} // namespace openpulse
