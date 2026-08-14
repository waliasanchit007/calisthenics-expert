PYTHON ?= python3
VENV_PYTHON := .venv/bin/python
VENV_PIP := .venv/bin/pip
CLI := $(VENV_PYTHON) -m observation_contracts

.PHONY: bootstrap verify

bootstrap:
	$(PYTHON) -m venv .venv
	$(VENV_PIP) install -e 'reference[dev]'
	git config --local core.hooksPath .githooks

verify:
	$(VENV_PYTHON) -m ruff format --check reference
	$(VENV_PYTHON) -m ruff check reference
	$(VENV_PYTHON) -m mypy --config-file reference/pyproject.toml reference/src
	$(VENV_PYTHON) -m pytest reference/tests
	$(CLI) validate-session fixtures/synthetic/valid/session/manifest.json fixtures/synthetic/valid/session/frames.ndjson fixtures/synthetic/valid/session/annotation_independent_a.json fixtures/synthetic/valid/session/annotation_independent_b.json fixtures/synthetic/valid/session/annotation_adjudicated.json
	$(CLI) verify-fixture fixtures/synthetic/valid/session/fixture_metadata.json
	$(VENV_PYTHON) -c 'from pathlib import Path; from observation_contracts.fixture_integrity import discover_negative_fixtures, run_declared_validation; root = Path(".").resolve(); cases = discover_negative_fixtures(root); assert cases; assert all(run_declared_validation(case).codes == [case.expected_error_code] for case in cases)'
	$(CLI) verify-preprocessing-vector fixtures/synthetic/preprocessing/scale_pad_golden/vector.json
	$(CLI) guard-repository .
