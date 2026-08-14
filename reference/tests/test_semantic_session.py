"""Session-level reconciliation for counts, failures, and media references."""

from __future__ import annotations

from collections.abc import Callable, Mapping, Sequence
from typing import Any, cast

import pytest

from observation_contracts import semantic as semantic_module
from observation_contracts.errors import ValidationIssue
from tests.samples import minimal_manifest, minimal_pose_frame


def codes(issues: Sequence[ValidationIssue]) -> list[str]:
    """Return stable codes while leaving message wording free to evolve."""
    return [issue.code for issue in issues]


def validate_session(
    manifest: Mapping[str, Any], frames: Sequence[Mapping[str, Any]]
) -> list[ValidationIssue]:
    """Call the Task 4 API, turning its absence into a focused RED assertion."""
    validator = getattr(semantic_module, "validate_session_manifest_semantics", None)
    assert callable(validator), "session manifest reconciliation is not implemented"
    return cast(
        Callable[[Mapping[str, Any], Sequence[Mapping[str, Any]], str], list[ValidationIssue]],
        validator,
    )(manifest, frames, "manifest.json")


def failure(
    *, classification: str = "backend_call_failed", submission_index: int = 1
) -> dict[str, Any]:
    """Return one synthetic machine failure with a valid runtime classification."""
    return {
        "submission_index": submission_index,
        "capture_timestamp_ns": 200,
        "failure_source": "backend",
        "classification": classification,
        "source_error_code": "MEDIAPIPE_STATUS_13",
    }


def test_counts_close_across_failed_call_and_trailing_skips() -> None:
    manifest = minimal_manifest(
        camera_frames_received=9,
        inference_frames_submitted=3,
        inference_records_emitted=2,
        pose_detected_records=2,
        frames_skipped_before_inference=6,
        trailing_frames_skipped_after_last_emitted_record=1,
        inference_failures=1,
    )
    manifest["machine_inference_failures"] = [failure()]
    frames = [
        minimal_pose_frame(index=0, capture_ns=100, skipped=0),
        minimal_pose_frame(index=1, capture_ns=300, skipped=5),
    ]

    assert validate_session(manifest, frames) == []


def test_rejects_skip_reason_total_that_includes_a_future_integer_reason() -> None:
    manifest = minimal_manifest()
    manifest["counts"]["frames_skipped_before_inference_by_reason"]["future_runtime_reason"] = 1

    issues = validate_session(manifest, [minimal_pose_frame()])

    assert [(issue.code, issue.pointer) for issue in issues] == [
        ("SESSION.COUNT_MISMATCH", "/counts/frames_skipped_before_inference_by_reason")
    ]


@pytest.mark.parametrize("invalid_count", [True, -1, 1.0, "1"])
def test_rejects_inappropriate_skip_reason_counts(invalid_count: object) -> None:
    manifest = minimal_manifest()
    manifest["counts"]["frames_skipped_before_inference_by_reason"]["future_runtime_reason"] = (
        invalid_count
    )

    issues = validate_session(manifest, [minimal_pose_frame()])

    assert [(issue.code, issue.pointer) for issue in issues] == [
        ("SESSION.COUNT_MISMATCH", "/counts/frames_skipped_before_inference_by_reason")
    ]


@pytest.mark.parametrize(
    ("name", "manifest", "frames"),
    [
        (
            "camera frame closure",
            minimal_manifest(camera_frames_received=8),
            [minimal_pose_frame()],
        ),
        (
            "submitted call closure",
            minimal_manifest(
                camera_frames_received=10,
                inference_frames_submitted=4,
                inference_records_emitted=2,
                pose_detected_records=2,
                frames_skipped_before_inference=6,
                trailing_frames_skipped_after_last_emitted_record=1,
                inference_failures=0,
            ),
            [
                minimal_pose_frame(index=0, capture_ns=100, skipped=0),
                minimal_pose_frame(index=1, capture_ns=300, skipped=5),
            ],
        ),
        (
            "emitted record closure",
            minimal_manifest(
                camera_frames_received=3,
                inference_frames_submitted=3,
                inference_records_emitted=3,
                pose_detected_records=2,
            ),
            [
                minimal_pose_frame(index=0, capture_ns=100),
                minimal_pose_frame(index=1, capture_ns=300),
            ],
        ),
        (
            "detected record closure",
            minimal_manifest(pose_detected_records=0),
            [minimal_pose_frame()],
        ),
        (
            "skipped frame closure",
            minimal_manifest(
                camera_frames_received=8,
                inference_frames_submitted=2,
                inference_records_emitted=2,
                pose_detected_records=2,
                frames_skipped_before_inference=6,
                trailing_frames_skipped_after_last_emitted_record=2,
            ),
            [
                minimal_pose_frame(index=0, capture_ns=100, skipped=0),
                minimal_pose_frame(index=1, capture_ns=300, skipped=5),
            ],
        ),
    ],
)
def test_rejects_each_broken_count_identity(
    name: str, manifest: Mapping[str, Any], frames: Sequence[Mapping[str, Any]]
) -> None:
    issues = validate_session(manifest, frames)

    assert issues, name
    assert codes(issues) == ["SESSION.COUNT_MISMATCH"]


def test_rejects_machine_failure_list_length_mismatch() -> None:
    manifest = minimal_manifest(
        camera_frames_received=9,
        inference_frames_submitted=3,
        inference_records_emitted=2,
        pose_detected_records=2,
        frames_skipped_before_inference=6,
        trailing_frames_skipped_after_last_emitted_record=1,
        inference_failures=1,
    )

    manifest["machine_inference_failures"] = []
    issues = validate_session(
        manifest,
        [
            minimal_pose_frame(index=0, capture_ns=100, skipped=0),
            minimal_pose_frame(index=1, capture_ns=300, skipped=5),
        ],
    )

    assert codes(issues) == ["SESSION.COUNT_MISMATCH"]


def test_rejects_unknown_machine_failure_classification() -> None:
    manifest = minimal_manifest(
        camera_frames_received=9,
        inference_frames_submitted=3,
        inference_records_emitted=2,
        pose_detected_records=2,
        frames_skipped_before_inference=6,
        trailing_frames_skipped_after_last_emitted_record=1,
        inference_failures=1,
    )
    manifest["machine_inference_failures"] = [failure(classification="unknown")]

    issues = validate_session(
        manifest,
        [
            minimal_pose_frame(index=0, capture_ns=100, skipped=0),
            minimal_pose_frame(index=1, capture_ns=300, skipped=5),
        ],
    )

    assert codes(issues) == ["SESSION.COUNT_MISMATCH"]


@pytest.mark.parametrize(
    ("failure_entries", "inference_failures"),
    [
        ([failure(submission_index=3)], 1),
        ([failure(), failure()], 2),
    ],
)
def test_rejects_failure_entries_that_do_not_classify_distinct_submissions(
    failure_entries: list[dict[str, Any]], inference_failures: int
) -> None:
    manifest = minimal_manifest(
        camera_frames_received=10 if inference_failures == 2 else 9,
        inference_frames_submitted=4 if inference_failures == 2 else 3,
        inference_records_emitted=2,
        pose_detected_records=2,
        frames_skipped_before_inference=6,
        trailing_frames_skipped_after_last_emitted_record=1,
        inference_failures=inference_failures,
    )
    manifest["machine_inference_failures"] = failure_entries
    frames = [
        minimal_pose_frame(index=0, capture_ns=100, skipped=0),
        minimal_pose_frame(index=1, capture_ns=300, skipped=5),
    ]

    issues = validate_session(manifest, frames)

    assert codes(issues) == ["SESSION.COUNT_MISMATCH"]


def test_requires_one_static_reference_clip_for_a_study_session() -> None:
    manifest = minimal_manifest()
    manifest["media_references"] = []

    issues = validate_session(manifest, [minimal_pose_frame()])

    assert codes(issues) == ["SESSION.COUNT_MISMATCH"]


def test_requires_one_static_reference_clip_for_an_empty_study_context() -> None:
    manifest = minimal_manifest()
    manifest["study_context"] = {}
    manifest["media_references"] = []

    issues = validate_session(manifest, [minimal_pose_frame()])

    assert [(issue.code, issue.pointer) for issue in issues] == [
        ("SESSION.COUNT_MISMATCH", "/media_references")
    ]


def test_rejects_duplicate_media_roles() -> None:
    manifest = minimal_manifest()
    first_session_video = dict(manifest["media_references"][0])
    first_session_video["role"] = "session_video"
    second_session_video = dict(first_session_video)
    manifest["media_references"].extend([first_session_video, second_session_video])

    issues = validate_session(manifest, [minimal_pose_frame()])

    assert codes(issues) == ["SESSION.COUNT_MISMATCH"]


def test_requires_sync_offset_and_drift_together() -> None:
    manifest = minimal_manifest()
    manifest["media_references"][0]["sync_offset_ns"] = 10

    issues = validate_session(manifest, [minimal_pose_frame()])

    assert codes(issues) == ["SESSION.COUNT_MISMATCH"]


def test_accepts_zero_sync_offset_when_drift_is_present() -> None:
    manifest = minimal_manifest()
    manifest["media_references"][0]["sync_offset_ns"] = 0
    manifest["media_references"][0]["sync_drift_ns_per_s"] = 0.0

    assert validate_session(manifest, [minimal_pose_frame()]) == []


@pytest.mark.parametrize("timeout_ms", [0, -1])
def test_rejects_nonpositive_inference_drain_timeout(timeout_ms: int) -> None:
    manifest = minimal_manifest()
    manifest["inference_drain_timeout_ms"] = timeout_ms

    issues = validate_session(manifest, [minimal_pose_frame()])

    assert codes(issues) == ["SESSION.COUNT_MISMATCH"]
