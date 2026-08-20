"""Regressions for the detection-tier replay wrapper and the committed admission artifact.

Two separate vacuity holes are closed here, and both are host-side and hermetic — no device, no
`adb`, no emulator.

The first is the device wrapper. `am instrument` reports test failure in its output while still
exiting 0, so `make verify-harness-detection-replay` has to read the output. Reading it for a
generic `^OK (` line accepts `OK (0 tests)`, accepts a run of some entirely different suite, and
accepts a run that emitted no observation at all. The wrapper must instead require the exact
expected suite, all three of its tests, and the sanitized `ReplayDetectionEvidence` record the
run emits.

The second is the committed admission evidence. `detection_evidence.status = confirmed` is a claim
that a real run against the pinned model fired the detector on the committed pixels. Checking only
that the fields are non-empty makes the claim self-declared: it survives a different model, a
different Tasks Vision pin, a different fixture render, and the total absence of any run artifact.
The claim is bound here to the repository's own pins and to a committed sanitized artifact of the
run that produced it.
"""

from __future__ import annotations

import hashlib
import json
import shutil
from pathlib import Path

import pytest

from observation_contracts.detection_evidence import (
    EVIDENCE_STATUS_KEY,
    EXPECTED_SUITE,
    EXPECTED_TEST_NAMES,
    DetectionEvidenceError,
    load_pinned_inputs,
    verify_committed_evidence,
    verify_instrumented_run,
)

FIXTURE_METADATA = "fixtures/synthetic/valid/fixture_detection/fixture_metadata.json"
MODEL_PROVENANCE = "apps/diagnostic-harness/model-assets/model_provenance.json"
LIBS_VERSIONS = "apps/diagnostic-harness/gradle/libs.versions.toml"
EVIDENCE_DIR = "docs/evidence/detection-replay"


def repository_root() -> Path:
    return Path(__file__).parents[2]


def _copy(root: Path, destination: Path, relative: str) -> None:
    target = destination / relative
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(root / relative, target)


@pytest.fixture
def repo(tmp_path: Path) -> Path:
    """A hermetic copy of exactly the files the binding is derived from."""
    root = repository_root()
    destination = tmp_path / "repo"
    for relative in (FIXTURE_METADATA, MODEL_PROVENANCE, LIBS_VERSIONS):
        _copy(root, destination, relative)
    for artifact in sorted((root / EVIDENCE_DIR).glob("*.json")):
        _copy(root, destination, f"{EVIDENCE_DIR}/{artifact.name}")
    return destination


def _read(root: Path, relative: str) -> dict:
    return json.loads((root / relative).read_text(encoding="utf-8"))


def _write(root: Path, relative: str, payload: dict) -> None:
    (root / relative).write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")


def _observation(root: Path) -> dict:
    return _read(root, FIXTURE_METADATA)["detection_evidence"]["observations"][0]


def _artifact_path(root: Path) -> str:
    path = _observation(root)["evidence_artifact"]["path"]
    assert isinstance(path, str)
    return path


def _rebind(root: Path, artifact: dict) -> None:
    """Rewrite the artifact and re-point the metadata digest at it.

    Mutations of the artifact's *content* must be caught by the binding on the pinned inputs, not
    only by the content digest, so every content mutation below is committed together with a
    digest that matches it.
    """
    relative = _artifact_path(root)
    payload = json.dumps(artifact, indent=2) + "\n"
    (root / relative).write_text(payload, encoding="utf-8")
    metadata = _read(root, FIXTURE_METADATA)
    binding = metadata["detection_evidence"]["observations"][0]["evidence_artifact"]
    binding["sha256"] = hashlib.sha256(payload.encode("utf-8")).hexdigest()
    _write(root, FIXTURE_METADATA, metadata)


def _instrumentation_output(
    root: Path,
    *,
    tests: list[str] | None = None,
    suite: str = EXPECTED_SUITE,
    ok_line: str | None = None,
    evidence: str | None = "committed",
) -> str:
    """Synthesize an `am instrument -w` transcript of the shape AOSP actually emits."""
    names = sorted(EXPECTED_TEST_NAMES) if tests is None else tests
    if evidence == "committed":
        record = json.dumps(_read(root, _artifact_path(root)), separators=(",", ":"))
    else:
        record = evidence
    lines = []
    for position, name in enumerate(names, start=1):
        lines += [
            f"INSTRUMENTATION_STATUS: class={suite}",
            f"INSTRUMENTATION_STATUS: current={position}",
            "INSTRUMENTATION_STATUS: id=AndroidJUnitRunner",
            f"INSTRUMENTATION_STATUS: numtests={len(names)}",
            "INSTRUMENTATION_STATUS: stream=",
            f"{suite}:",
            f"INSTRUMENTATION_STATUS: test={name}",
            "INSTRUMENTATION_STATUS_CODE: 1",
            f"INSTRUMENTATION_STATUS: class={suite}",
            f"INSTRUMENTATION_STATUS: current={position}",
            "INSTRUMENTATION_STATUS: id=AndroidJUnitRunner",
            f"INSTRUMENTATION_STATUS: numtests={len(names)}",
            "INSTRUMENTATION_STATUS: stream=.",
            f"INSTRUMENTATION_STATUS: test={name}",
            "INSTRUMENTATION_STATUS_CODE: 0",
        ]
    if record is not None:
        lines += [
            f"INSTRUMENTATION_STATUS: {EVIDENCE_STATUS_KEY}={record}",
            "INSTRUMENTATION_STATUS_CODE: 2",
        ]
    lines += [
        "INSTRUMENTATION_RESULT: stream=",
        "",
        "Time: 41.213",
        "",
        ok_line if ok_line is not None else f"OK ({len(names)} tests)",
        "",
        "INSTRUMENTATION_CODE: -1",
    ]
    return "\n".join(lines) + "\n"


# --- the wrapper: the run that is claimed to have happened ------------------------------------


def test_the_committed_state_passes_both_verifications(repo: Path) -> None:
    """A guard that rejects everything is as useless as one that accepts everything."""
    verify_committed_evidence(repo)
    verify_instrumented_run(repo, _instrumentation_output(repo))


def test_a_run_that_executed_no_tests_is_rejected(repo: Path) -> None:
    """`OK (0 tests)` is the exact shape a class-name typo produces, and it is not evidence."""
    with pytest.raises(DetectionEvidenceError, match="0"):
        verify_instrumented_run(
            repo, _instrumentation_output(repo, tests=[], ok_line="OK (0 tests)")
        )


def test_a_run_missing_one_of_the_three_tests_is_rejected(repo: Path) -> None:
    kept = sorted(EXPECTED_TEST_NAMES)[:2]
    with pytest.raises(DetectionEvidenceError, match="test"):
        verify_instrumented_run(repo, _instrumentation_output(repo, tests=kept))


def test_a_run_with_a_duplicated_terminal_outcome_is_rejected(repo: Path) -> None:
    """Exact-suite admission is about cardinality, not membership.

    Comparing the executed names as a *set* accepts a transcript carrying the three expected names
    plus one of them a second time, and `OK (3 tests)` alongside it — the runner's own count and the
    terminal outcomes then disagree about what ran. Each expected method must report exactly one
    terminal outcome.
    """
    names = sorted(EXPECTED_TEST_NAMES)
    with pytest.raises(DetectionEvidenceError, match="once"):
        verify_instrumented_run(
            repo,
            _instrumentation_output(repo, tests=[*names, names[0]], ok_line="OK (3 tests)"),
        )


def test_a_run_of_some_other_suite_is_rejected(repo: Path) -> None:
    with pytest.raises(DetectionEvidenceError, match="suite"):
        verify_instrumented_run(
            repo, _instrumentation_output(repo, suite="com.calisthenics.diagnostic.OtherTest")
        )


def test_a_passing_run_that_emitted_no_evidence_record_is_rejected(repo: Path) -> None:
    with pytest.raises(DetectionEvidenceError, match=EVIDENCE_STATUS_KEY):
        verify_instrumented_run(repo, _instrumentation_output(repo, evidence=None))


def test_a_reported_test_failure_is_rejected_even_though_am_instrument_exits_zero(
    repo: Path,
) -> None:
    output = _instrumentation_output(repo).replace("OK (3 tests)", "FAILURES!!!\nTests run: 3")
    with pytest.raises(DetectionEvidenceError):
        verify_instrumented_run(repo, output)


def test_an_evidence_record_bound_to_a_different_model_is_rejected(repo: Path) -> None:
    record = _read(repo, _artifact_path(repo))
    record["provenance"]["model_sha256"] = "0" * 64
    with pytest.raises(DetectionEvidenceError, match="model_sha256"):
        verify_instrumented_run(
            repo,
            _instrumentation_output(repo, evidence=json.dumps(record, separators=(",", ":"))),
        )


def test_an_evidence_record_bound_to_a_different_frame_set_is_rejected(repo: Path) -> None:
    record = _read(repo, _artifact_path(repo))
    record["fixture"]["frame_sha256"][2] = "0" * 64
    with pytest.raises(DetectionEvidenceError, match="frame_sha256"):
        verify_instrumented_run(
            repo,
            _instrumentation_output(repo, evidence=json.dumps(record, separators=(",", ":"))),
        )


def test_a_record_from_the_committed_device_must_match_the_committed_artifact(repo: Path) -> None:
    """Same device fingerprint, different observation, is drift and must be loud."""
    record = _read(repo, _artifact_path(repo))
    record["landmark_observation_sha256"][0] = "f" * 64
    with pytest.raises(DetectionEvidenceError, match="committed artifact"):
        verify_instrumented_run(
            repo,
            _instrumentation_output(repo, evidence=json.dumps(record, separators=(",", ":"))),
        )


def test_a_run_on_a_different_device_is_a_separate_observation_not_a_failure(repo: Path) -> None:
    """Task 5's S25 run must be able to use this target; a divergence there is a finding."""
    record = _read(repo, _artifact_path(repo))
    record["environment"]["device_class"] = "physical_device"
    record["environment"]["device_fingerprint"] = (
        "samsung/e3q/e3q:16/BP2A.250705.008/x:user/release-keys"
    )
    record["environment"]["device_identity"] = "samsung/SM-S938B/e3q/e3qxxx"
    record["landmark_observation_sha256"] = [f"{index:064x}" for index in range(1, 7)]
    verify_instrumented_run(
        repo, _instrumentation_output(repo, evidence=json.dumps(record, separators=(",", ":")))
    )


# --- the committed artifact: the binding to the pinned inputs ---------------------------------


def test_a_confirmed_status_with_no_artifact_is_rejected(repo: Path) -> None:
    metadata = _read(repo, FIXTURE_METADATA)
    del metadata["detection_evidence"]["observations"][0]["evidence_artifact"]
    _write(repo, FIXTURE_METADATA, metadata)
    with pytest.raises(DetectionEvidenceError, match="evidence_artifact"):
        verify_committed_evidence(repo)


def test_a_missing_artifact_file_is_rejected(repo: Path) -> None:
    (repo / _artifact_path(repo)).unlink()
    with pytest.raises(DetectionEvidenceError, match="missing"):
        verify_committed_evidence(repo)


def test_an_artifact_that_changed_under_its_recorded_digest_is_rejected(repo: Path) -> None:
    relative = _artifact_path(repo)
    record = _read(repo, relative)
    record["topology"]["pose_count_per_frame"] = [1, 1, 1, 1, 1, 2]
    (repo / relative).write_text(json.dumps(record, indent=2) + "\n", encoding="utf-8")
    with pytest.raises(DetectionEvidenceError, match="sha256"):
        verify_committed_evidence(repo)


def test_an_artifact_bound_to_a_different_model_sha_is_rejected(repo: Path) -> None:
    record = _read(repo, _artifact_path(repo))
    record["provenance"]["model_sha256"] = "1" * 64
    _rebind(repo, record)
    with pytest.raises(DetectionEvidenceError, match="model_sha256"):
        verify_committed_evidence(repo)


def test_an_artifact_bound_to_a_different_tasks_vision_pin_is_rejected(repo: Path) -> None:
    record = _read(repo, _artifact_path(repo))
    record["provenance"]["tasks_vision_version"] = "0.10.14"
    _rebind(repo, record)
    with pytest.raises(DetectionEvidenceError, match="tasks_vision_version"):
        verify_committed_evidence(repo)


def test_an_artifact_bound_to_a_different_frame_set_is_rejected(repo: Path) -> None:
    record = _read(repo, _artifact_path(repo))
    record["fixture"]["frame_sha256"] = list(reversed(record["fixture"]["frame_sha256"]))
    _rebind(repo, record)
    with pytest.raises(DetectionEvidenceError, match="frame_sha256"):
        verify_committed_evidence(repo)


def test_an_artifact_bound_to_a_different_fixture_id_is_rejected(repo: Path) -> None:
    record = _read(repo, _artifact_path(repo))
    record["fixture"]["fixture_id"] = "00000000-0000-4000-8000-000000000000"
    _rebind(repo, record)
    with pytest.raises(DetectionEvidenceError, match="fixture_id"):
        verify_committed_evidence(repo)


def test_an_artifact_outside_the_evidence_root_is_rejected(repo: Path) -> None:
    metadata = _read(repo, FIXTURE_METADATA)
    binding = metadata["detection_evidence"]["observations"][0]["evidence_artifact"]
    stray = repo / "fixtures/synthetic/valid/fixture_detection/run.json"
    stray.write_bytes((repo / _artifact_path(repo)).read_bytes())
    binding["path"] = "fixtures/synthetic/valid/fixture_detection/run.json"
    _write(repo, FIXTURE_METADATA, metadata)
    with pytest.raises(DetectionEvidenceError, match="docs/evidence"):
        verify_committed_evidence(repo)


def test_an_artifact_claiming_the_s25_gate_is_rejected(repo: Path) -> None:
    record = _read(repo, _artifact_path(repo))
    record["satisfies_s25_device_gate"] = True
    _rebind(repo, record)
    with pytest.raises(DetectionEvidenceError, match="s25"):
        verify_committed_evidence(repo)


def test_an_artifact_whose_topology_shows_no_detection_is_rejected(repo: Path) -> None:
    record = _read(repo, _artifact_path(repo))
    record["topology"]["pose_present_frames"] = [0, 1, 2, 3, 4]
    _rebind(repo, record)
    with pytest.raises(DetectionEvidenceError, match="pose_present_frames"):
        verify_committed_evidence(repo)


def test_an_artifact_with_a_frozen_landmark_observation_is_rejected(repo: Path) -> None:
    record = _read(repo, _artifact_path(repo))
    record["landmark_observation_sha256"] = [record["landmark_observation_sha256"][0]] * 6
    _rebind(repo, record)
    with pytest.raises(DetectionEvidenceError, match="distinct"):
        verify_committed_evidence(repo)


def test_an_observation_that_contradicts_its_own_artifact_is_rejected(repo: Path) -> None:
    metadata = _read(repo, FIXTURE_METADATA)
    metadata["detection_evidence"]["observations"][0]["api_level"] = 34
    _write(repo, FIXTURE_METADATA, metadata)
    with pytest.raises(DetectionEvidenceError, match="api_level"):
        verify_committed_evidence(repo)


def test_the_artifact_carries_no_landmark_coordinates(repo: Path) -> None:
    """Sanitized means digests, topology, environment, provenance — never raw coordinates."""
    record = _read(repo, _artifact_path(repo))
    assert record["contains_landmark_coordinates"] is False
    flattened = json.dumps(record)
    assert '"x"' not in flattened
    assert '"normalized_image_landmarks"' not in flattened
    assert '"world_landmarks"' not in flattened


def test_the_pins_the_binding_is_derived_from_agree_with_each_other(repo: Path) -> None:
    """The Tasks Vision pin exists in two places; a silent split would weaken the binding."""
    pinned = load_pinned_inputs(repo)
    assert pinned.tasks_vision_version == "1.0.0"
    assert pinned.model_sha256 == _read(repo, MODEL_PROVENANCE)["sha256"]
    toml = (repo / LIBS_VERSIONS).read_text(encoding="utf-8")
    assert f'mediapipe = "{pinned.tasks_vision_version}"' in toml


def test_a_split_tasks_vision_pin_is_rejected(repo: Path) -> None:
    toml = (repo / LIBS_VERSIONS).read_text(encoding="utf-8")
    (repo / LIBS_VERSIONS).write_text(
        toml.replace('mediapipe = "1.0.0"', 'mediapipe = "0.10.14"'), encoding="utf-8"
    )
    with pytest.raises(DetectionEvidenceError, match="tasks_vision_version"):
        load_pinned_inputs(repo)
