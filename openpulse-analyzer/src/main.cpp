#include <filesystem>
#include <fstream>
#include <iostream>
#include <string>
#include <vector>

#include "Analyzer.h"

namespace {

void printUsage(std::ostream& os, const char* prog) {
    os << "Usage: " << prog << " --path <repo-path> --output <report.json>\n"
       << "\n"
       << "Exit codes:\n"
       << "  0  success\n"
       << "  1  invalid arguments\n"
       << "  2  path does not exist\n"
       << "  3  scan failed\n"
       << "  4  JSON output failed\n";
}

struct CliArgs {
    std::filesystem::path repoPath;
    std::filesystem::path outputPath;
    bool valid = false;
};

CliArgs parseArgs(int argc, char* argv[]) {
    CliArgs args;
    for (int i = 1; i < argc; ++i) {
        std::string arg(argv[i]);
        if (arg == "--path") {
            if (i + 1 < argc) {
                args.repoPath = argv[++i];
            } else {
                std::cerr << "Error: --path requires a value\n";
                return args;
            }
        } else if (arg == "--output") {
            if (i + 1 < argc) {
                args.outputPath = argv[++i];
            } else {
                std::cerr << "Error: --output requires a value\n";
                return args;
            }
        } else if (arg == "--help" || arg == "-h") {
            printUsage(std::cout, argv[0]);
            return args;  // valid remains false
        } else {
            std::cerr << "Error: unknown argument: " << arg << "\n";
            printUsage(std::cerr, argv[0]);
            return args;
        }
    }

    if (args.repoPath.empty()) {
        std::cerr << "Error: --path is required\n";
        printUsage(std::cerr, argv[0]);
        return args;
    }
    if (args.outputPath.empty()) {
        std::cerr << "Error: --output is required\n";
        printUsage(std::cerr, argv[0]);
        return args;
    }

    args.valid = true;
    return args;
}

} // namespace

int main(int argc, char* argv[]) {
    std::cout << "OpenPulse Analyzer v0.1" << std::endl;

    auto args = parseArgs(argc, argv);
    if (!args.valid) {
        return 1;
    }

    // Validate --path exists
    std::error_code ec;
    if (!std::filesystem::exists(args.repoPath, ec)) {
        std::cerr << "Error: path does not exist: " << args.repoPath.string() << "\n";
        return 2;
    }

    // Generate report
    openpulse::AnalyzerConfig config{args.repoPath, args.outputPath};
    openpulse::Analyzer analyzer;
    nlohmann::json report = analyzer.generateReport(config);

    // Write JSON to output file
    std::ofstream out(args.outputPath);
    if (!out) {
        std::cerr << "Error: cannot write to " << args.outputPath.string() << "\n";
        return 4;
    }
    out << report.dump(2) << "\n";
    out.close();
    if (!out) {
        std::cerr << "Error: failed to write report to " << args.outputPath.string() << "\n";
        return 4;
    }

    std::cout << "Report written to " << args.outputPath.string() << std::endl;
    return 0;
}
