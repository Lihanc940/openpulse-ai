# Real CLI regression for non-ASCII repository names: builds a minimal
# self-contained sample repository with a CJK directory name, runs the
# analyzer with --protocol 2.0, and requires a successful run whose
# report carries the UTF-8 repository name. Before the UTF-8 fix this
# crashed the process (nlohmann::json invalid UTF-8) and left no report.
#
# The sample is created fresh under WORK_DIR instead of copying
# tests/fixture: other tests create and delete entries inside that
# shared fixture, which made a parallel file(COPY) race with them.
# The sample satisfies README/LICENSE/CI so the expected status stays
# SUCCESS.
# Usage:
#   cmake -DANALYZER=<exe> -DWORK_DIR=<dir> -P RunUnicodeRepo.cmake

if(NOT ANALYZER OR NOT WORK_DIR)
  message(FATAL_ERROR "ANALYZER and WORK_DIR are required")
endif()

set(REPO "${WORK_DIR}/OpenPulse中文仓库")
set(REPORT "${WORK_DIR}/unicode-v2.json")
file(REMOVE_RECURSE "${REPO}")
file(REMOVE "${REPORT}")
file(WRITE "${REPO}/README.md" "# sample\n")
file(WRITE "${REPO}/LICENSE" "MIT\n")
file(WRITE "${REPO}/.github/workflows/ci.yml" "name: ci\n")
file(WRITE "${REPO}/src/main.cpp" "int main() { return 0; }\n")

execute_process(
  COMMAND "${ANALYZER}" --protocol 2.0 --path "${REPO}" --output "${REPORT}"
  RESULT_VARIABLE EXIT_CODE
  OUTPUT_VARIABLE STDOUT
  ERROR_VARIABLE STDERR
)
if(NOT EXIT_CODE STREQUAL "0")
  message(FATAL_ERROR "analyzer exited with '${EXIT_CODE}' on a CJK-named repo\nstdout: ${STDOUT}\nstderr: ${STDERR}")
endif()

if(NOT EXISTS "${REPORT}")
  message(FATAL_ERROR "analyzer exited 0 but wrote no report: ${REPORT}")
endif()

file(READ "${REPORT}" REPORT_JSON)

string(JSON PROTOCOL ERROR_VARIABLE PROTOCOL_ERR GET "${REPORT_JSON}" protocolVersion)
if(PROTOCOL_ERR OR NOT PROTOCOL STREQUAL "2.0")
  message(FATAL_ERROR "report protocolVersion check failed: ${PROTOCOL_ERR}")
endif()
string(JSON STATUS GET "${REPORT_JSON}" status)
if(NOT STATUS STREQUAL "SUCCESS")
  message(FATAL_ERROR "status is ${STATUS}, expected SUCCESS")
endif()
string(JSON REPO_NAME GET "${REPORT_JSON}" repository name)
if(NOT REPO_NAME STREQUAL "OpenPulse中文仓库")
  message(FATAL_ERROR "repository.name is '${REPO_NAME}', expected 'OpenPulse中文仓库'")
endif()

message(STATUS "unicode repo CLI check passed: ${REPO}")
