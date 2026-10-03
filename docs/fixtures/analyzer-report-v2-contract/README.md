# Analyzer report v2 contract fixtures

This directory contains fictional, test-only inputs for Task 12. Nothing here
is a real repository or a validation experiment result.

## Format and cases

- Fixture manifest format: `openpulse-v2-contract-cases@1`
- Scenario format: `openpulse-v2-contract-scenario@1`
- Mutation manifest format: `openpulse-v2-contract-mutations@1`
- `repositories/complete` is scanned by the production CLI and contains all
  three structure markers.
- `repositories/missing-structure` is scanned by the production CLI and omits
  README, license, and CI markers intentionally.
- `scenarios/partial-success.json` and `scenarios/failed.json` are consumed
  only by the C++ test adapter. The adapter creates controlled `ScanFacts` or a
  controlled scan-failure reason, then delegates to the production
  `ReportV2Builder`.
- `expected/*.v2.normalized.json` contains complete production-builder output
  with only top-level `taskId` and `generatedAt` removed.

## Update rule

Golden files must never be rewritten from an observed failure. Regenerate them
only after an intentional analyzer, rule-set, protocol, or scan-fact change;
review the full JSON first, then update the matching SHA-256 in `cases.json`.
The repository-level verifier treats the golden file as authoritative and the
manifest hash as a redundant check.

The fixtures contain no symlinks, Git metadata, credentials, personal paths,
third-party source, or formal validation CSV data.
