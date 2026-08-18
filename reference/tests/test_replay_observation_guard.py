"""Check 6: landmark deltas are reported as observations, never asserted against a threshold.

Design section 7 forbids turning exact landmark hashes and numeric deltas into an invented pass
threshold. That is a negative requirement, so no passing test can demonstrate it. The only
mechanical enforcement is a source scan, in the same shape as the repository guard's other
allowlist rules.
"""

from __future__ import annotations

import re
from pathlib import Path

# A landmark-delta token adjacent to a numeric comparison inside an assertion is the shape of an
# invented tolerance. Equality against a committed golden digest is explicitly fine.
_DELTA_TOKEN = re.compile(
    r"landmark(?:_|[A-Z])?(?:delta|distance|tolerance|epsilon)|delta_landmark",
    re.IGNORECASE,
)
_THRESHOLD = re.compile(r"(?:assert\w*\s*\(|require\s*\()?[^\n]*?[<>]=?\s*[0-9.]")
_SCANNED_SUFFIXES = {".kt", ".kts"}


def repository_root() -> Path:
    return Path(__file__).parents[2]


def _kotlin_sources() -> list[Path]:
    root = repository_root() / "apps" / "diagnostic-harness"
    return sorted(
        path
        for path in root.rglob("*")
        if path.suffix in _SCANNED_SUFFIXES and "build" not in path.parts
    )


def _threshold_offences(text: str) -> list[str]:
    offences: list[str] = []
    for number, line in enumerate(text.splitlines(), start=1):
        if _DELTA_TOKEN.search(line) and _THRESHOLD.search(line):
            offences.append(f"{number}: {line.strip()}")
    return offences


def test_kotlin_sources_exist_to_scan() -> None:
    """A guard that scans nothing passes vacuously."""
    sources = _kotlin_sources()
    assert len(sources) > 10, f"expected the harness Kotlin tree, found {len(sources)} files"


def test_no_landmark_delta_is_compared_against_a_threshold() -> None:
    offending: dict[str, list[str]] = {}
    for path in _kotlin_sources():
        found = _threshold_offences(path.read_text(encoding="utf-8"))
        if found:
            offending[str(path.relative_to(repository_root()))] = found
    assert not offending, (
        "landmark deltas must be reported as observations, never asserted against a "
        f"threshold: {offending}"
    )


def test_the_scan_actually_detects_a_threshold_assertion() -> None:
    """Proves the guard is load-bearing rather than a pattern that never matches."""
    assert _threshold_offences("assertTrue(landmarkDelta < 0.001)")
    assert _threshold_offences("    require(landmark_delta <= 1e-6)")
    assert _threshold_offences("assertTrue(landmarkDistance > 0.5)")
    # Equality against a committed golden digest is the sanctioned form and must not trip.
    assert not _threshold_offences("assertEquals(goldenLandmarkHash, observed.landmarkHash)")
    assert not _threshold_offences("val landmarkDelta = observed - expected")
