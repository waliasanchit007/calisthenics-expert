# ADR-0003: Reference Validation, Contract Evolution, and Data Guarding

- Status: Accepted
- Date: 2026-08-12
- Decision owners: Sanchit Walia, `claude-engineer`, and the coordinating agent

## Context

Future Android, iOS, and analysis implementations need one executable interpretation of the observation schemas. The repository must also prevent irreversible commits of participant video, biometric-derived observations, consent records, identity mappings, and credentials.

## Decision

- JSON Schema Draft 2020-12 is the language-neutral structural contract.
- Python 3.12 is the canonical semantic validator because Phase 0 analysis will also run in Python.
- Every artifact carries an independent semantic version. Compatible newer minor or patch versions tolerate unknown optional fields; unknown majors fail before processing.
- Positive synthetic fixtures and declared-negative synthetic fixtures are committed. Negative fixtures include an `expected_failure.json` declaration and are required to fail with the named validator error.
- A committed `.githooks/pre-commit` runs the staged-path data guard. `make bootstrap` idempotently sets `core.hooksPath=.githooks` before write-mode work.
- A committed GitHub Actions workflow runs `make verify` on pull requests and pushes. `make verify` scans the complete tracked tree independently of hook installation.
- Validator error codes are a versioned registry. Released codes are never renamed or reused, and declared-negative fixtures are validated against a tooling schema.

## Consequences

- Both platform exporters are judged by the same structural and semantic behavior.
- Negative compatibility tests coexist with the data guard without exemptions for arbitrary files.
- Hook installation changes repository-local Git configuration but does not modify global Git settings.
- A local commit made before `make bootstrap` remains a documented residual risk; remote integration is still blocked by CI.
- Schema lifecycle follows `contracts/schema-release-process.md`; released schema files are immutable.
