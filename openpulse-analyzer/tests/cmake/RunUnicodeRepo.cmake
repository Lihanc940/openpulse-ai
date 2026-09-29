# Real CLI regression for non-ASCII repository names: copies the fixture
# into a directory with CJK characters, runs the analyzer with
# --protocol 2.0, and requires a successful run whose report carries the
# UTF-8 repository name. Before the UTF-8 fix this crashed the process
# (nlohmann::json invalid UTF-8) and left no report behind.
# Usage:
#   cmake -DANALYZER=<exe> -DFIXTURE=<dir> -DWORK_DIR=<dir> -P RunUnicodeRepo.cmake

if(NOT ANALYZER OR NOT FIXTURE OR NOT WORK_DIR)
  message(FATAL_ERROR "ANALYZER, FIXTURE and WORK_DIR are required")
endif()

set(REPO "${WORK_DIR}/OpenPulse中文仓库")
set(REPORT "${WORK_DIR}/unicode-v2.json")
file(REMOVE_RECURSE "${REPO}")
file(REMOVE "${REPORT}")
file(COPY "${FIXTURE}/" DESTINATION "${REPO}")

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
