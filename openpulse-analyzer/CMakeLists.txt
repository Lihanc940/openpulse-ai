cmake_minimum_required(VERSION 3.20)
project(openpulse-analyzer VERSION 0.2.0 LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
if(MSVC)
  # Ship one executable without a separate Visual C++ redistributable.
  set(CMAKE_MSVC_RUNTIME_LIBRARY "MultiThreaded$<$<CONFIG:Debug>:Debug>")
endif()

include(FetchContent)
FetchContent_Declare(
  nlohmann_json
  GIT_REPOSITORY https://github.com/nlohmann/json.git
  GIT_TAG v3.11.3
)
FetchContent_MakeAvailable(nlohmann_json)

# Inject the real project version into the build; no hand-written version
# strings in source code.
configure_file(
  ${CMAKE_CURRENT_SOURCE_DIR}/include/Version.h.in
  ${CMAKE_CURRENT_BINARY_DIR}/generated/Version.h
  @ONLY
)

set(OPENPULSE_ANALYZER_SOURCES
  src/Analyzer.cpp
  src/RuleCatalog.cpp
  src/ReportV2Builder.cpp
  src/SummaryV2.cpp
)

add_executable(openpulse-analyzer
  src/main.cpp
  ${OPENPULSE_ANALYZER_SOURCES}
)
target_include_directories(openpulse-analyzer PRIVATE
  include
  third_party
  ${CMAKE_CURRENT_BINARY_DIR}/generated
)
if(MSVC)
  target_compile_options(openpulse-analyzer PRIVATE /utf-8)
  target_link_options(openpulse-analyzer PRIVATE /Brepro)
endif()
target_link_libraries(openpulse-analyzer PRIVATE nlohmann_json::nlohmann_json)

install(TARGETS openpulse-analyzer RUNTIME DESTINATION bin COMPONENT Runtime)
install(FILES ${CMAKE_CURRENT_SOURCE_DIR}/../docs/windows-x64-cli.md
  DESTINATION . RENAME README.md COMPONENT Runtime)
install(FILES ${CMAKE_CURRENT_SOURCE_DIR}/../docs/THIRD_PARTY_NOTICES.md
  DESTINATION . COMPONENT Runtime)
install(FILES ${nlohmann_json_SOURCE_DIR}/LICENSE.MIT
  DESTINATION licenses RENAME nlohmann-json.LICENSE COMPONENT Runtime)
install(FILES ${CMAKE_CURRENT_SOURCE_DIR}/third_party/picosha2/LICENSE
  DESTINATION licenses RENAME picosha2.LICENSE COMPONENT Runtime)

# --- Tests ---
option(BUILD_TESTS "Build unit tests" ON)
if(BUILD_TESTS)
  add_executable(openpulse-analyzer-test
    ${OPENPULSE_ANALYZER_SOURCES}
    tests/test_analyzer.cpp
  )
  target_include_directories(openpulse-analyzer-test PRIVATE
    include
    third_party
    ${CMAKE_CURRENT_BINARY_DIR}/generated
  )
  target_compile_definitions(openpulse-analyzer-test PRIVATE OPENPULSE_TEST)
  if(MSVC)
    target_compile_options(openpulse-analyzer-test PRIVATE /utf-8)
  endif()
  target_link_libraries(openpulse-analyzer-test PRIVATE nlohmann_json::nlohmann_json)

  add_executable(openpulse-analyzer-test-v2
    ${OPENPULSE_ANALYZER_SOURCES}
    tests/test_report_v2.cpp
  )
  target_include_directories(openpulse-analyzer-test-v2 PRIVATE
    include
    third_party
    ${CMAKE_CURRENT_BINARY_DIR}/generated
  )
  target_compile_definitions(openpulse-analyzer-test-v2 PRIVATE
    OPENPULSE_TEST
    OPENPULSE_FIXTURE_DIR="${CMAKE_CURRENT_SOURCE_DIR}/tests/fixture"
  )
  if(MSVC)
    target_compile_options(openpulse-analyzer-test-v2 PRIVATE /utf-8)
  endif()
  target_link_libraries(openpulse-analyzer-test-v2 PRIVATE nlohmann_json::nlohmann_json)

  # Test-only adapter used by the repository-level v2 contract verification.
  # It converts controlled scenario descriptions into ScanFacts and delegates
  # all report construction to the production ReportV2Builder.
  add_executable(openpulse-analyzer-contract-report-generator
    ${OPENPULSE_ANALYZER_SOURCES}
    tests/contract_report_generator.cpp
  )
  target_include_directories(openpulse-analyzer-contract-report-generator PRIVATE
    include
    third_party
    ${CMAKE_CURRENT_BINARY_DIR}/generated
  )
  target_compile_definitions(openpulse-analyzer-contract-report-generator PRIVATE OPENPULSE_TEST)
  if(MSVC)
    target_compile_options(openpulse-analyzer-contract-report-generator PRIVATE /utf-8)
  endif()
  target_link_libraries(openpulse-analyzer-contract-report-generator PRIVATE nlohmann_json::nlohmann_json)

  enable_testing()
  set(OPENPULSE_FIXTURE ${CMAKE_CURRENT_SOURCE_DIR}/tests/fixture)
  set(OPENPULSE_REPORT_DIR ${CMAKE_CURRENT_BINARY_DIR}/cli-reports)
  set(OPENPULSE_CONTRACT_FIXTURE
      ${CMAKE_CURRENT_SOURCE_DIR}/../docs/fixtures/analyzer-report-v2-contract)
  set(OPENPULSE_CONTRACT_REPORT_DIR ${CMAKE_CURRENT_BINARY_DIR}/contract-reports)
  file(MAKE_DIRECTORY ${OPENPULSE_REPORT_DIR})
  file(MAKE_DIRECTORY ${OPENPULSE_CONTRACT_REPORT_DIR})

  add_test(
    NAME openpulse-analyzer-test
    COMMAND openpulse-analyzer-test
    WORKING_DIRECTORY ${CMAKE_SOURCE_DIR}
  )
  set_tests_properties(openpulse-analyzer-test PROPERTIES
    ENVIRONMENT "OPENPULSE_TEST_FIXTURE=${OPENPULSE_FIXTURE}"
  )

  add_test(
    NAME openpulse-analyzer-test-v2
    COMMAND openpulse-analyzer-test-v2
    WORKING_DIRECTORY ${CMAKE_SOURCE_DIR}
  )

  # --- CLI protocol selection tests ---

  add_test(NAME cli-help COMMAND openpulse-analyzer --help)
  set_tests_properties(cli-help PROPERTIES
    PASS_REGULAR_EXPRESSION "--protocol")

  add_test(NAME cli-protocol-unknown
    COMMAND openpulse-analyzer --protocol 9.9 --path ${OPENPULSE_FIXTURE}
            --output ${OPENPULSE_REPORT_DIR}/unknown.json)
  set_tests_properties(cli-protocol-unknown PROPERTIES WILL_FAIL TRUE)

  add_test(NAME cli-protocol-missing-value
    COMMAND openpulse-analyzer --protocol)
  set_tests_properties(cli-protocol-missing-value PROPERTIES WILL_FAIL TRUE)

  add_test(NAME cli-protocol-duplicate
    COMMAND openpulse-analyzer --protocol 1.0 --protocol 2.0
            --path ${OPENPULSE_FIXTURE} --output ${OPENPULSE_REPORT_DIR}/dup.json)
  set_tests_properties(cli-protocol-duplicate PROPERTIES WILL_FAIL TRUE)

  add_test(NAME cli-default-v1
    COMMAND openpulse-analyzer --path ${OPENPULSE_FIXTURE}
            --output ${OPENPULSE_REPORT_DIR}/default-v1.json)
  set_tests_properties(cli-default-v1 PROPERTIES
    FIXTURES_SETUP cli-default-v1)

  add_test(NAME cli-explicit-v1
    COMMAND openpulse-analyzer --protocol 1.0 --path ${OPENPULSE_FIXTURE}
            --output ${OPENPULSE_REPORT_DIR}/explicit-v1.json)
  set_tests_properties(cli-explicit-v1 PROPERTIES
    FIXTURES_SETUP cli-explicit-v1)

  add_test(NAME cli-v2-success
    COMMAND openpulse-analyzer --protocol 2.0 --path ${OPENPULSE_FIXTURE}
            --output ${OPENPULSE_REPORT_DIR}/v2.json)
  set_tests_properties(cli-v2-success PROPERTIES
    FIXTURES_SETUP cli-v2-success)

  add_test(NAME cli-default-v1-shape
    COMMAND ${CMAKE_COMMAND}
            -DREPORT=${OPENPULSE_REPORT_DIR}/default-v1.json
            -DEXPECT_PROTOCOL=1.0
            -P ${CMAKE_CURRENT_SOURCE_DIR}/tests/cmake/CheckReport.cmake)
  set_tests_properties(cli-default-v1-shape PROPERTIES
    FIXTURES_REQUIRED cli-default-v1)

  add_test(NAME cli-explicit-v1-equals-default
    COMMAND ${CMAKE_COMMAND}
            -DFIRST=${OPENPULSE_REPORT_DIR}/default-v1.json
            -DSECOND=${OPENPULSE_REPORT_DIR}/explicit-v1.json
            -P ${CMAKE_CURRENT_SOURCE_DIR}/tests/cmake/CompareReports.cmake)
  set_tests_properties(cli-explicit-v1-equals-default PROPERTIES
    FIXTURES_REQUIRED "cli-default-v1;cli-explicit-v1")

  add_test(NAME cli-v2-shape
    COMMAND ${CMAKE_COMMAND}
            -DREPORT=${OPENPULSE_REPORT_DIR}/v2.json
            -DEXPECT_PROTOCOL=2.0
            -DEXPECT_STATUS=SUCCESS
            -P ${CMAKE_CURRENT_SOURCE_DIR}/tests/cmake/CheckReport.cmake)
  set_tests_properties(cli-v2-shape PROPERTIES
    FIXTURES_REQUIRED cli-v2-success)

  # v2 files must be exactly the canonical JSON bytes, no trailing newline
  add_test(NAME cli-v2-canonical-bytes
    COMMAND ${CMAKE_COMMAND}
            -DREPORT=${OPENPULSE_REPORT_DIR}/v2.json
            -P ${CMAKE_CURRENT_SOURCE_DIR}/tests/cmake/CheckCanonicalJson.cmake)
  set_tests_properties(cli-v2-canonical-bytes PROPERTIES
    FIXTURES_REQUIRED cli-v2-success)

  # Real CLI run against a repo whose name contains CJK characters:
  # must exit 0 with a valid UTF-8 report, never crash
  add_test(NAME cli-v2-unicode-repo
    COMMAND ${CMAKE_COMMAND}
            -DANALYZER=$<TARGET_FILE:openpulse-analyzer>
            -DWORK_DIR=${OPENPULSE_REPORT_DIR}/unicode
            -P ${CMAKE_CURRENT_SOURCE_DIR}/tests/cmake/RunUnicodeRepo.cmake)

  add_test(NAME contract-generate-partial
    COMMAND openpulse-analyzer-contract-report-generator
            --scenario ${OPENPULSE_CONTRACT_FIXTURE}/scenarios/partial-success.json
            --output ${OPENPULSE_CONTRACT_REPORT_DIR}/partial-success.json
            --task-id ctest_partial
            --generated-at 2026-10-02T00:00:00Z)
  set_tests_properties(contract-generate-partial PROPERTIES
    FIXTURES_SETUP contract-partial)

  add_test(NAME contract-partial-shape
    COMMAND ${CMAKE_COMMAND}
            -DREPORT=${OPENPULSE_CONTRACT_REPORT_DIR}/partial-success.json
            -DEXPECT_PROTOCOL=2.0
            -DEXPECT_STATUS=PARTIAL_SUCCESS
            -P ${CMAKE_CURRENT_SOURCE_DIR}/tests/cmake/CheckReport.cmake)
  set_tests_properties(contract-partial-shape PROPERTIES
    FIXTURES_REQUIRED contract-partial)

  add_test(NAME contract-generate-failed
    COMMAND openpulse-analyzer-contract-report-generator
            --scenario ${OPENPULSE_CONTRACT_FIXTURE}/scenarios/failed.json
            --output ${OPENPULSE_CONTRACT_REPORT_DIR}/failed.json
            --task-id ctest_failed
            --generated-at 2026-10-02T00:00:00Z)
  set_tests_properties(contract-generate-failed PROPERTIES
    FIXTURES_SETUP contract-failed)

  add_test(NAME contract-failed-shape
    COMMAND ${CMAKE_COMMAND}
            -DREPORT=${OPENPULSE_CONTRACT_REPORT_DIR}/failed.json
            -DEXPECT_PROTOCOL=2.0
            -DEXPECT_STATUS=FAILED
            -P ${CMAKE_CURRENT_SOURCE_DIR}/tests/cmake/CheckReport.cmake)
  set_tests_properties(contract-failed-shape PROPERTIES
    FIXTURES_REQUIRED contract-failed)

  add_test(NAME cli-summary-v1-rejected
    COMMAND openpulse-analyzer --protocol 1.0 --summary --path ${OPENPULSE_FIXTURE}
            --output ${OPENPULSE_REPORT_DIR}/invalid-summary.json)
  set_tests_properties(cli-summary-v1-rejected PROPERTIES WILL_FAIL TRUE)

  add_test(NAME cli-summary-v2
    COMMAND openpulse-analyzer --protocol 2.0 --summary --path ${OPENPULSE_FIXTURE}
            --output ${OPENPULSE_REPORT_DIR}/summary-v2.json)
  set_tests_properties(cli-summary-v2 PROPERTIES
    PASS_REGULAR_EXPRESSION "OpenPulse report summary")
endif()
