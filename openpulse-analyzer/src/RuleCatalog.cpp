#include "RuleCatalog.h"

namespace openpulse {

namespace {

std::vector<RuleDefinition> buildCatalog() {
    return {
        {
            "MISSING_CI",
            "PROJECT_STRUCTURE",
            "LOW",
            "EXPECTED_PATHS_ABSENT",
            "仓库缺少已识别的持续集成配置。",
            "根据项目构建方式选择持续集成服务，并添加最小构建与测试流程。",
            {".circleci", ".github/workflows", ".gitlab-ci.yml", ".travis.yml",
             "Jenkinsfile", "azure-pipelines.yml"}
        },
        {
            "MISSING_LICENSE",
            "PROJECT_STRUCTURE",
            "MEDIUM",
            "EXPECTED_PATHS_ABSENT",
            "仓库缺少许可证文件。",
            "确认项目的发布方式，并添加与项目授权意图一致的许可证文件。",
            {"LICENSE", "LICENSE.md", "LICENSE.txt"}
        },
        {
            "MISSING_README",
            "PROJECT_STRUCTURE",
            "MEDIUM",
            "EXPECTED_PATHS_ABSENT",
            "仓库缺少 README 说明文件。",
            "添加 README 文件，说明项目用途、构建方式和基本用法。",
            {"README", "README.md", "README.rst", "README.txt"}
        },
    };
}

} // anonymous namespace

const std::vector<RuleDefinition>& ruleCatalog() {
    static const std::vector<RuleDefinition> catalog = buildCatalog();
    return catalog;
}

const RuleDefinition* findRuleDefinition(const std::string& ruleId) {
    for (const auto& rule : ruleCatalog()) {
        if (ruleId == rule.ruleId) {
            return &rule;
        }
    }
    return nullptr;
}

} // namespace openpulse
