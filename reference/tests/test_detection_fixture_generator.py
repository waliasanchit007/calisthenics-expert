"""The detection fixture's device evidence must survive a re-render, and only a re-render.

Task 4 records, in the fixture metadata, that the pinned model actually fires on these pixels. The
generator writes that same file. Without this behaviour, `python tools/generate_detection_fixture.py`
silently reverts a confirmed admission back to `pending` — and, worse, the reverse failure mode
exists too: iterating the render while keeping the old evidence would leave a `confirmed` status
attached to pixels no device ever saw.
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parents[2] / "tools"))

from generate_detection_fixture import PENDING_EVIDENCE, detection_evidence_for

CONFIRMED = {
    "status": "confirmed",
    "admissible_for_topology_checks": True,
    "confirmed_by": "some/instrumented/test",
    "observations": [{"device_class": "emulator"}],
}


def _metadata(tmp_path: Path, frames: list[dict[str, object]], evidence: dict[str, object]) -> Path:
    path = tmp_path / "fixture_metadata.json"
    path.write_text(json.dumps({"detection_evidence": evidence, "frames": frames}))
    return path


def test_recorded_evidence_survives_a_re_render_of_byte_identical_pixels() -> None:
    frames = [{"frame_index": 0, "sha256": "a" * 64}, {"frame_index": 1, "sha256": "b" * 64}]
    path = _metadata(Path(__file__).parent, frames, CONFIRMED)
    try:
        assert detection_evidence_for(path, frames) == CONFIRMED
    finally:
        path.unlink()


def test_changed_pixels_invalidate_the_recorded_evidence() -> None:
    recorded = [{"frame_index": 0, "sha256": "a" * 64}, {"frame_index": 1, "sha256": "b" * 64}]
    rerendered = [{"frame_index": 0, "sha256": "a" * 64}, {"frame_index": 1, "sha256": "c" * 64}]
    path = _metadata(Path(__file__).parent, recorded, CONFIRMED)
    try:
        evidence = detection_evidence_for(path, rerendered)
    finally:
        path.unlink()

    assert evidence["status"] == "pending"
    assert evidence["admissible_for_topology_checks"] is False
    assert "render" in evidence["missing_precondition"]


def test_a_changed_frame_count_invalidates_the_recorded_evidence() -> None:
    recorded = [{"frame_index": 0, "sha256": "a" * 64}]
    rerendered = [{"frame_index": 0, "sha256": "a" * 64}, {"frame_index": 1, "sha256": "b" * 64}]
    path = _metadata(Path(__file__).parent, recorded, CONFIRMED)
    try:
        assert detection_evidence_for(path, rerendered)["status"] == "pending"
    finally:
        path.unlink()


def test_a_first_generation_starts_pending() -> None:
    missing = Path(__file__).parent / "does-not-exist-fixture_metadata.json"
    assert not missing.exists()

    assert (
        detection_evidence_for(missing, [{"frame_index": 0, "sha256": "a" * 64}])
        == PENDING_EVIDENCE
    )


def test_the_pending_default_is_never_admissible() -> None:
    assert PENDING_EVIDENCE["status"] == "pending"
    assert PENDING_EVIDENCE["admissible_for_topology_checks"] is False
    assert PENDING_EVIDENCE["missing_precondition"]
