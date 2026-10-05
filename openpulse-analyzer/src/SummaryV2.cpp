#include "SummaryV2.h"

#include <algorithm>
#include <sstream>

#include "RuleCatalog.h"

namespace openpulse {
namespace {
constexpr std::size_t kEntryLimit = 5;
}

std::string formatSummaryV2(const nlohmann::json& report) {
    std::ostringstream out;
    out << "OpenPulse report summary (protocol 2.0)\n"
        << "Analyzer: " << report.at("analyzer").at("name").get<std::string>()
        << ' ' << report.at("analyzer").at("version").get<std::string>() << '\n'
        << "Rule set: " << report.at("ruleSet").at("id").get<std::string>()
        << ' ' << report.at("ruleSet").at("version").get<std::string>() << '\n'
        << "Status: " << report.at("status").get<std::string>()
        << " | Reviewability: " << report.at("reviewability").get<std::string>() << '\n';

    const auto& findings = report.at("findings");
    const auto& limitations = report.at("limitations");
    if (report.contains("summary")) {
        out << "Files: " << report.at("summary").at("totalFiles").get<std::uint64_t>()
            << " | Languages: " << report.at("languages").size() << '\n';
    } else {
        out << "Files: unavailable | Languages: unavailable\n";
    }
    out << "Findings: " << findings.size() << " | Limitations: " << limitations.size() << '\n';

    for (std::size_t i = 0; i < std::min(findings.size(), kEntryLimit); ++i) {
        const auto& finding = findings.at(i);
        const auto ruleId = finding.at("ruleId").get<std::string>();
        const RuleDefinition* rule = findRuleDefinition(ruleId);
        // Only catalog text is printed. Report evidence, messages and paths
        // never enter the terminal summary.
        if (rule != nullptr) {
            out << "  " << rule->ruleId << " [" << rule->severity << ", "
                << finding.at("scope").get<std::string>() << "]: "
                << rule->remediation << '\n';
        }
    }
    if (findings.size() > kEntryLimit) {
        out << "  ... " << (findings.size() - kEntryLimit) << " more findings\n";
    }
    for (std::size_t i = 0; i < std::min(limitations.size(), kEntryLimit); ++i) {
        const auto& limitation = limitations.at(i);
        out << "  Limitation: " << limitation.at("kind").get<std::string>() << '\n';
    }
    if (limitations.size() > kEntryLimit) {
        out << "  ... " << (limitations.size() - kEntryLimit) << " more limitations\n";
    }
    return out.str();
}
} // namespace openpulse
