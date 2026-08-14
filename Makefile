PYTHON ?= $(shell for candidate in python3.14 python3.13 python3.12 python3; do \
	if command -v "$$candidate" >/dev/null 2>&1 && "$$candidate" -c 'import sys; raise SystemExit(0 if sys.version_info >= (3, 12) else 1)' >/dev/null 2>&1; then \
		printf '%s' "$$candidate"; exit 0; \
	fi; \
done; printf '%s' python3)
VENV_PYTHON := .venv/bin/python
CLI := $(VENV_PYTHON) -m observation_contracts

.PHONY: bootstrap verify

bootstrap:
	@command -v "$(PYTHON)" >/dev/null 2>&1 || { echo "error: Python interpreter '$(PYTHON)' was not found" >&2; exit 1; }
	@$(PYTHON) -c 'import sys; sys.exit(f"error: Python >= 3.12 is required, got {sys.version_info.major}.{sys.version_info.minor}.{sys.version_info.micro}") if sys.version_info < (3, 12) else None'
	@if [ -x "$(VENV_PYTHON)" ] && ! "$(VENV_PYTHON)" -c 'import sys; raise SystemExit(0 if sys.version_info >= (3, 12) else 1)' >/dev/null 2>&1; then \
		echo "error: existing .venv uses an unsupported Python interpreter; remove .venv and rerun make bootstrap" >&2; \
		"$(VENV_PYTHON)" --version >&2 || true; \
		exit 1; \
	fi
	@if [ ! -x "$(VENV_PYTHON)" ]; then $(PYTHON) -m venv .venv; fi
	$(VENV_PYTHON) -m pip install --upgrade pip
	$(VENV_PYTHON) -m pip install -e 'reference[dev]'
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
