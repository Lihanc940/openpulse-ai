#include "Analyzer.h"
#include "ReportV2Builder.h"

#include <filesystem>
#include <fstream>
#include <iostream>
#include <stdexcept>
#include <string>

#include <nlohmann/json.hpp>

namespace fs = std::filesystem;

namespace {

struct Arguments {
    fs::path scenario;
    fs::path output;
    std::string taskId;
    std::string generatedAt;
};

Arguments parseArguments(int argc, char* argv[]) {
    Arguments arguments;
    for (int index = 1; index < argc; ++index) {
        const std::string name = argv[index];
        if (index + 1 >= argc) {
            throw std::runtime_error("every argument requires a value");
        }
        const std::string value = argv[++index];
        if (name == "--scenario") {
            arguments.scenario = value;
        } else if (name == "--output") {
            arguments.output = value;
        } else if (name == "--task-id") {
            arguments.taskId = value;
        } else if (name == "--generated-at") {
            arguments.generatedAt = value;
        } else {
            throw std::runtime_error("unknown argument");
        }
    }
    if (arguments.scenario.empty() || arguments.output.empty()
            || arguments.taskId.empty() || arguments.generatedAt.empty()) {
        throw std::runtime_error(
            "required: --scenario --output --task-id --generated-at");
    }
    return arguments;
}

nlohmann::json readJson(const fs::path& path) {
    std::ifstream input(path, std::ios::binary);
    if (!input) {
        throw std::runtime_error("scenario file cannot be read");
    }
    nlohmann::json value;
    input >> value;
    return value;
}

void requireExactKeys(const nlohmann::json& value,
                      std::initializer_list<const char*> expected) {
    if (!value.is_object() || value.size() != expected.size()) {
        throw std::runtime_error("scenario object has unexpected fields");
    }
    for (const char* key : expected) {
        if (!value.contains(key)) {
            throw std::runtime_error("scenario object is missing a required field");
        }
    }
}

std::size_t readCounter(const nlohmann::json& value, const char* key) {
    if (!value.at(key).is_number_unsigned()) {
        throw std::runtime_error("scenario counter must be an unsigned integer");
    }
    return value.at(key).get<std::size_t>();
}

openpulse::ScanFacts readFacts(const nlohmann::json& scenario) {
    requireExactKeys(scenario, {"formatVersion", "kind", "repositoryName", "facts"});
    const auto& factsJson = scenario.at("facts");
    requireExactKeys(factsJson, {"summary", "languages", "structure", "skippedFiles"});

    openpulse::ScanFacts facts;
    const auto& summary = factsJson.at("summary");
    requireExactKeys(summary, {"totalFiles", "sourceFiles", "documentFiles",
                               "configFiles", "testFiles", "totalLines",
                               "codeLines", "commentLines", "blankLines"});
    facts.stats.totalFiles = readCounter(summary, "totalFiles");
    facts.stats.sourceFiles = readCounter(summary, "sourceFiles");
    facts.stats.documentFiles = readCounter(summary, "documentFiles");
    facts.stats.configFiles = readCounter(summary, "configFiles");
    facts.stats.testFiles = readCounter(summary, "testFiles");
    facts.stats.totalLines = readCounter(summary, "totalLines");
    facts.stats.codeLines = readCounter(summary, "codeLines");
    facts.stats.commentLines = readCounter(summary, "commentLines");
    facts.stats.blankLines = readCounter(summary, "blankLines");

    for (const auto& language : factsJson.at("languages")) {
        requireExactKeys(language, {"name", "files", "lines"});
        facts.languages.push_back({language.at("name").get<std::string>(),
                                   readCounter(language, "files"),
                                   readCounter(language, "lines")});
    }

    const auto& structure = factsJson.at("structure");
    requireExactKeys(structure, {"hasReadme", "hasLicense", "hasContributing",
                                 "hasChangelog", "hasCi", "hasTests",
                                 "hasDockerfile", "buildFiles"});
    facts.structure.hasReadme = structure.at("hasReadme").get<bool>();
    facts.structure.hasLicense = structure.at("hasLicense").get<bool>();
    facts.structure.hasContributing = structure.at("hasContributing").get<bool>();
    facts.structure.hasChangelog = structure.at("hasChangelog").get<bool>();
    facts.structure.hasCi = structure.at("hasCi").get<bool>();
    facts.structure.hasTests = structure.at("hasTests").get<bool>();
    facts.structure.hasDockerfile = structure.at("hasDockerfile").get<bool>();
    facts.structure.buildFiles = structure.at("buildFiles").get<std::vector<std::string>>();

    for (const auto& skipped : factsJson.at("skippedFiles")) {
        requireExactKeys(skipped, {"relativePath", "reason"});
        facts.skippedFiles.push_back({skipped.at("relativePath").get<std::string>(),
                                      skipped.at("reason").get<std::string>()});
    }
    return facts;
}

void writeCanonical(const fs::path& output, const nlohmann::json& report) {
    std::ofstream stream(output, std::ios::binary | std::ios::trunc);
    if (!stream) {
        throw std::runtime_error("report file cannot be written");
    }
    stream << report.dump();
    stream.close();
    if (!stream) {
        throw std::runtime_error("report file write failed");
    }
}

} // namespace

int main(int argc, char* argv[]) {
    try {
        const Arguments arguments = parseArguments(argc, argv);
        const nlohmann::json scenario = readJson(arguments.scenario);
        if (scenario.at("formatVersion") != "openpulse-v2-contract-scenario@1") {
            throw std::runtime_error("unsupported scenario formatVersion");
        }
        const std::string kind = scenario.at("kind").get<std::string>();
        const std::string repositoryName = scenario.at("repositoryName").get<std::string>();
        openpulse::ReportV2Builder builder;
        nlohmann::json report;
        if (kind == "PARTIAL_SUCCESS") {
            report = builder.build(readFacts(scenario), repositoryName,
                                   arguments.taskId, arguments.generatedAt);
            if (report.at("status") != "PARTIAL_SUCCESS") {
                throw std::runtime_error("scenario did not produce PARTIAL_SUCCESS");
            }
        } else if (kind == "FAILED") {
            requireExactKeys(scenario,
                             {"formatVersion", "kind", "repositoryName", "reason"});
            report = builder.buildFailed(scenario.at("reason").get<std::string>(),
                                         repositoryName, arguments.taskId,
                                         arguments.generatedAt);
        } else {
            throw std::runtime_error("unsupported scenario kind");
        }
        writeCanonical(arguments.output, report);
        return 0;
    } catch (const std::exception& exception) {
        std::cerr << "contract report generation failed: " << exception.what() << '\n';
        return 1;
    }
}
