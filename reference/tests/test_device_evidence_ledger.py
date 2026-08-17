import json
from pathlib import Path

ROOT = Path(__file__).parents[2]


def test_s25_process_kill_ledger_reports_pending_or_recorded_status() -> None:
    ledger = json.loads(
        (ROOT / "docs/evidence/device-probes/s25-ultra-process-kill-ledger.json").read_text()
    )
    assert ledger["status"] in {"pending", "recorded"}
    if ledger["status"] == "pending":
        assert ledger["missing_precondition"]
    else:
        assert ledger["evidence_file"]
