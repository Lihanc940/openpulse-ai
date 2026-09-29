# Compares two reports after removing the dynamic fields taskId and
# generatedAt (the only fields allowed to differ between runs).
# Usage: cmake -DFIRST=<a.json> -DSECOND=<b.json> -P CompareReports.cmake

if(NOT FIRST OR NOT SECOND)
  message(FATAL_ERROR "FIRST and SECOND are required")
endif()

file(READ "${FIRST}" A)
file(READ "${SECOND}" B)

foreach(KEY taskId generatedAt)
  string(REGEX REPLACE ",?\"${KEY}\": ?\"[^\"]*\"" "" A "${A}")
  string(REGEX REPLACE ",?\"${KEY}\": ?\"[^\"]*\"" "" B "${B}")
endforeach()

if(NOT A STREQUAL B)
  message(FATAL_ERROR "reports differ after removing taskId and generatedAt:\n--- first ---\n${A}\n--- second ---\n${B}")
endif()

message(STATUS "normalized reports are identical: ${FIRST} == ${SECOND}")
