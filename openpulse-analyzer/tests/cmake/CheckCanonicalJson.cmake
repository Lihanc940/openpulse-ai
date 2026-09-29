# Validates that a protocol v2 report file contains exactly the canonical
# JSON bytes: it must end with '}' with no trailing newline or whitespace
# (protocol section 10).
# Usage: cmake -DREPORT=<file> -P CheckCanonicalJson.cmake

if(NOT REPORT)
  message(FATAL_ERROR "REPORT is required")
endif()

file(READ "${REPORT}" REPORT_HEX HEX)
string(REGEX MATCH "..$" LAST_BYTE "${REPORT_HEX}")
if(NOT LAST_BYTE STREQUAL "7d")
  message(FATAL_ERROR "v2 report must end with '}' (0x7d), last byte is 0x${LAST_BYTE}")
endif()

message(STATUS "canonical byte check passed: ${REPORT}")
