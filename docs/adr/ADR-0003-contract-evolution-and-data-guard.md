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
- `make verify` and CI scan the complete tracked tree independently of hook installation.

## Consequences

- Both platform exporters are judged by the same structural and semantic behavior.
- Negative compatibility tests coexist with the data guard without exemptions for arbitrary files.
- Hook installation changes repository-local Git configuration but does not modify global Git settings.
- Schema lifecycle follows `contracts/schema-release-process.md`; released schema files are immutable.
