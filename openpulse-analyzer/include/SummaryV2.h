#pragma once

#include <string>
#include <nlohmann/json.hpp>

namespace openpulse {
std::string formatSummaryV2(const nlohmann::json& report);
}
