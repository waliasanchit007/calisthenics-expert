"""Hermetic checks for the pinned model-asset fetch. No network required.

The quarantine behaviour here was found by deliberately corrupting a cached asset and watching
what happened: the first implementation left the corrupt file at the path the build reads when the
refetch failed. These tests exist so that probe is permanent rather than a one-off manual act.
"""

from __future__ import annotations

import hashlib
import json
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).parents[2] / "tools"))

from fetch_model_asset import MANIFEST, ModelAssetError, fetch_model_asset

PAYLOAD = b"synthetic-model-bytes" * 64


def _root(tmp_path: Path, *, source_url: str) -> tuple[Path, Path]:
    root = tmp_path / "repo"
    manifest_path = root / MANIFEST
    manifest_path.parent.mkdir(parents=True)
    manifest_path.write_text(
        json.dumps(
            {
                "asset_name": "pose_landmarker_full.task",
                "sha256": hashlib.sha256(PAYLOAD).hexdigest(),
                "byte_count": len(PAYLOAD),
                "source_url": source_url,
            }
        ),
        encoding="utf-8",
    )
    return root, manifest_path.parent / "pose_landmarker_full.task"


def _served(tmp_path: Path, content: bytes) -> str:
    served = tmp_path / "served.task"
    served.write_bytes(content)
    return served.as_uri()


def test_fetches_and_verifies_from_the_manifest_url(tmp_path: Path) -> None:
    root, destination = _root(tmp_path, source_url=_served(tmp_path, PAYLOAD))

    assert fetch_model_asset(root) == destination
    assert destination.read_bytes() == PAYLOAD


def test_is_idempotent_when_the_cache_is_already_verified(tmp_path: Path) -> None:
    root, destination = _root(tmp_path, source_url=_served(tmp_path, PAYLOAD))
    fetch_model_asset(root)
    stamp = destination.stat().st_mtime_ns

    fetch_model_asset(root)

    assert destination.stat().st_mtime_ns == stamp, "a verified cache must not be refetched"


def test_rejects_a_payload_that_does_not_match_the_pin(tmp_path: Path) -> None:
    root, destination = _root(tmp_path, source_url=_served(tmp_path, b"not-the-pinned-bytes"))

    with pytest.raises(ModelAssetError) as error:
        fetch_model_asset(root)

    assert "byte_count mismatch" in str(error.value) or "sha256 mismatch" in str(error.value)
    assert not destination.exists(), "an unverified payload must never occupy the read path"


def test_leaves_no_partial_file_behind_after_a_failed_fetch(tmp_path: Path) -> None:
    root, destination = _root(tmp_path, source_url=(tmp_path / "absent.task").as_uri())

    with pytest.raises(ModelAssetError):
        fetch_model_asset(root)

    residue = [path.name for path in destination.parent.iterdir() if path.suffix == ".partial"]
    assert residue == []


def test_quarantines_a_corrupt_cache_even_when_the_refetch_fails(tmp_path: Path) -> None:
    """The founding case: a detected-corrupt asset must not survive a failed refetch.

    Deleting the quarantine step makes this red, because the corrupt bytes stay readable.
    """
    root, destination = _root(tmp_path, source_url=(tmp_path / "absent.task").as_uri())
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_bytes(b"corrupt-cached-bytes")

    with pytest.raises(ModelAssetError):
        fetch_model_asset(root)

    assert not destination.exists(), (
        "a corrupt cache must be quarantined before refetching, so a failed fetch leaves nothing"
    )


def test_a_verified_cache_survives_an_unreachable_source(tmp_path: Path) -> None:
    """Quarantine must not be over-eager: a good cache is never discarded."""
    root, destination = _root(tmp_path, source_url=(tmp_path / "absent.task").as_uri())
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_bytes(PAYLOAD)

    assert fetch_model_asset(root) == destination
    assert destination.read_bytes() == PAYLOAD
