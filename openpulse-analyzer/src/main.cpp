#include <filesystem>
#include <fstream>
#include <iostream>
#include <string>

#include "Analyzer.h"
#include "ReportV2Builder.h"
#include "Version.h"

namespace {

void printUsage(std::ostream& os, const char* prog) {
    os << "Usage: " << prog << " --path <repo-path> --output <report.json> [--protocol <1.0|2.0>]\n"
       << "\n"
       << "Options:\n"
       << "  --path <dir>      repository path to analyze (required)\n"
       << "  --output <file>   report JSON output path (required)\n"
       << "  --protocol <ver>  report protocol version: 1.0 or 2.0 (default: 1.0)\n"
       << "  --help, -h        show this help\n"
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
    std::string protocol = "1.0";
    bool protocolSet = false;
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
        } else if (arg == "--protocol") {
            if (args.protocolSet) {
                std::cerr << "Error: duplicate --protocol argument\n";
                return args;
            }
            args.protocolSet = true;
            if (i + 1 >= argc) {
                std::cerr << "Error: --protocol requires a value\n";
                return args;
            }
            std::string value(argv[++i]);
            if (value != "1.0" && value != "2.0") {
                std::cerr << "Error: unknown protocol version: " << value
                          << " (supported: 1.0, 2.0)\n";
                return args;
            }
            args.protocol = value;
        } else if (arg == "--help" || arg == "-h") {
            printUsage(std::cout, argv[0]);
            std::exit(0);
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

// Minimal UTF-8 validator: rejects truncated sequences and bad
// continuation bytes, so a name that would break JSON serialization
// falls back to the placeholder instead of crashing the process.
bool isValidUtf8(const std::string& s) {
    size_t i = 0;
    while (i < s.size()) {
        const unsigned char c = static_cast<unsigned char>(s[i]);
        if (c < 0x80) {
            ++i;
            continue;
        }
        size_t extra;
        if (c >= 0xc2 && c < 0xe0) {
            extra = 1;
        } else if (c >= 0xe0 && c < 0xf0) {
            extra = 2;
        } else if (c >= 0xf0 && c < 0xf5) {
            extra = 3;
        } else {
            return false;
        }
        if (i + extra >= s.size()) {
            return false;
        }
        for (size_t k = 1; k <= extra; ++k) {
            if ((static_cast<unsigned char>(s[i + k]) & 0xc0) != 0x80) {
                return false;
            }
        }
        i += extra + 1;
    }
    return true;
}

// Repository directory name as UTF-8; never includes parent directories.
// Falls back to a fixed placeholder when the name is empty, unsafe, or
// not valid UTF-8, so report construction can never fail on the name.
std::string repositoryName(const std::filesystem::path& repoPath) {
    std::error_code ec;
    std::filesystem::path resolved = std::filesystem::absolute(repoPath, ec);
    if (ec) {
        resolved = repoPath;
    }
    // path::string() would return active-code-page bytes on Windows,
    // which are not UTF-8 for non-ASCII names and break nlohmann::json.
#ifdef __cpp_char8_t
    const std::u8string u8 = resolved.lexically_normal().filename().u8string();
    const std::string name(reinterpret_cast<const char*>(u8.data()), u8.size());
#else
    const std::string name = resolved.lexically_normal().filename().u8string();
#endif
    bool safe = !name.empty() && name.size() <= 128 && isValidUtf8(name);
    if (safe) {
        for (unsigned char c : name) {
            if (c < 0x20 || c == 0x7f) {
                safe = false;
                break;
            }
        }
    }
    return safe ? name : "repository";
}

bool writeReport(const std::filesystem::path& outputPath, const std::string& content,
                 bool trailingNewline) {
    std::ofstream out(outputPath, std::ios::binary | std::ios::trunc);
    if (!out) {
        std::cerr << "Error: cannot write to " << outputPath.string() << "\n";
        return false;
    }
    // v1 keeps its historical trailing newline; v2 must be exactly the
    // canonical JSON bytes with no extra whitespace (protocol section 10).
    out << content;
    if (trailingNewline) {
        out << "\n";
    }
    out.close();
    if (!out) {
        std::cerr << "Error: failed to write report to " << outputPath.string() << "\n";
        return false;
    }
    return true;
}

int runV2(const CliArgs& args) {
    openpulse::Analyzer analyzer;
    openpulse::ReportV2Builder builder;
    const std::string taskId = analyzer.generateTaskId();
    const std::string timestamp = analyzer.generateTimestamp();
    const std::string name = repositoryName(args.repoPath);

    try {
        openpulse::ScanFacts facts = analyzer.collectFacts(args.repoPath);
        if (facts.traversalFailed) {
            // Best-effort FAILED report; the exit code remains the real
            // failure signal and must stay non-zero.
            auto report = builder.buildFailed("TRAVERSAL_ERROR", name, taskId, timestamp);
            if (!writeReport(args.outputPath, report.dump(), false)) {
                return 4;
            }
            std::cerr << "Error: scan failed: cannot traverse repository\n";
            return 3;
        }
        auto report = builder.build(facts, name, taskId, timestamp);
        if (!writeReport(args.outputPath, report.dump(), false)) {
            return 4;
        }
        std::cout << "Report written to " << args.outputPath.string() << std::endl;
        return 0;
    } catch (const std::exception& e) {
        std::cerr << "Error: scan failed: " << e.what() << "\n";
        // Best effort; never let FAILED-report construction crash the
        // process — the non-zero exit code is the real failure signal.
        try {
            auto report = builder.buildFailed("INTERNAL_ERROR", name, taskId, timestamp);
            writeReport(args.outputPath, report.dump(), false);
        } catch (const std::exception& inner) {
            std::cerr << "Error: cannot write FAILED report: " << inner.what() << "\n";
        }
        return 3;
    }
}

} // namespace

int main(int argc, char* argv[]) {
    std::cout << "OpenPulse Analyzer v" << OPENPULSE_ANALYZER_VERSION << std::endl;

    auto args = parseArgs(argc, argv);
    if (!args.valid) {
        return 1;
    }

    // Validate --path exists and is a directory
    std::error_code ec;
    if (!std::filesystem::exists(args.repoPath, ec)) {
        std::cerr << "Error: path does not exist: " << args.repoPath.string() << "\n";
        return 2;
    }
    if (!std::filesystem::is_directory(args.repoPath, ec)) {
        std::cerr << "Error: path is not a directory: " << args.repoPath.string() << "\n";
        return 2;
    }

    if (args.protocol == "2.0") {
        return runV2(args);
    }

    // Protocol v1 (default)
    try {
        openpulse::AnalyzerConfig config{args.repoPath, args.outputPath};
        openpulse::Analyzer analyzer;
        nlohmann::json report = analyzer.generateReport(config);

        if (!writeReport(args.outputPath, report.dump(2), true)) {
            return 4;
        }
        std::cout << "Report written to " << args.outputPath.string() << std::endl;
        return 0;
    } catch (const std::exception& e) {
        std::cerr << "Error: scan failed: " << e.what() << "\n";
        return 3;
    }
}
