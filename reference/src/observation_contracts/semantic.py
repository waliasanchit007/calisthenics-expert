"""Semantic validation of a raw pose stream read against its session manifest.

Structural validation has already answered whether each record has the declared shape. This
module answers the questions that need more than one record, or more than one artifact:
sequencing, stream constancy, and reconciliation with the topology and the observation
availability the manifest declares.

Observations are preserved exactly. Nothing here smooths, imputes, clamps, re-derives, or
reinterprets a value, and no coordinate range and no confidence threshold is imposed: a
zero visibility and an out-of-unit coordinate are observations, not contract failures. Only
contradictions are reported — an ordering that cannot happen, a cardinality that disagrees
with the declared topology, or a frame that contradicts its own manifest.

Every check is independent and every issue is collected, so one run reports the whole
picture rather than the first failure. Issues come back in a deterministic order: the
manifest-level topology issue first, then each record in stream order, then each record's
own checks in a fixed order.

Inputs are expected to have passed structural validation already. A field that is missing or
carries the wrong primitive type is left to the structural pass rather than diagnosed a
second time here, so this module never re-reports a shape problem under a semantic code.
"""

from __future__ import annotations

import json
from collections.abc import Mapping, Sequence
from dataclasses import dataclass
from typing import Any

from observation_contracts.errors import ValidationIssue
from observation_contracts.topology import (
    LandmarkTopology,
    UnknownTopologyError,
    get_topology,
    supported_topology_names,
)

_FRAME_KIND = "raw_pose_frame"
_MANIFEST_KIND = "session_manifest"

_NORMALIZED = "normalized_image_landmarks"
_WORLD = "world_landmarks"
_SESSION_COUNT_CODE = "SESSION.COUNT_MISMATCH"
_FAILURE_CLASSIFICATIONS = frozenset(
    {"backend_call_failed", "session_teardown_timeout"}
)


@dataclass(frozen=True)
class _StreamRules:
    """What one stream is judged against.

    ``topology`` is ``None`` when the manifest declared a topology this reader cannot
    interpret, which disables every cardinality check rather than silently substituting a
    different topology's count.
    """

    topology: LandmarkTopology | None
    session_id: str | None
    normalized_available: bool | None
    world_available: bool | None
    schema_version: str | None
    frame_path: str


def validate_frame_stream(
    manifest: Mapping[str, Any],
    frames: Sequence[Mapping[str, Any]],
    manifest_path: str,
    frame_path: str,
) -> list[ValidationIssue]:
    """Return every semantic issue in one raw pose stream, in deterministic order.

    ``frames`` is the sequence of records in stream order, as returned by the structural
    NDJSON pass. Issue messages identify a record by its zero-based position in that
    sequence, which is stable even when ``frame_index`` is the field under suspicion.
    """
    topology, issues = _resolve_topology(manifest, manifest_path)
    available = _mapping(manifest, "available_observations")
    rules = _StreamRules(
        topology=topology,
        session_id=_text(manifest, "session_id"),
        normalized_available=_flag(available, "normalized_image_landmarks_available"),
        world_available=_flag(available, "world_landmarks_available"),
        schema_version=_text(frames[0], "raw_pose_frame_schema_version") if frames else None,
        frame_path=frame_path,
    )

    previous: Mapping[str, Any] | None = None
    for position, frame in enumerate(frames):
        issues.extend(_constancy_issues(frame, position, rules))
        issues.extend(_sequence_issues(frame, previous, position, rules))
        issues.extend(_landmark_issues(frame, position, rules))
        previous = frame
    return issues


def validate_session_manifest_semantics(
    manifest: Mapping[str, Any],
    frames: Sequence[Mapping[str, Any]],
    path: str,
) -> list[ValidationIssue]:
    """Return deterministic session-level count, failure, and media issues.

    Structural validation owns required fields and primitive types. This pass therefore only
    evaluates values that have the expected shape and reports contradictions using the one
    released session-level error code. Runtime failure classifications remain distinct from
    validator error codes, but unknown classifications still make the session inconsistent.
    """
    issues: list[ValidationIssue] = []
    counts = _mapping(manifest, "counts")

    camera_received = _integer(counts, "camera_frames_received")
    submitted = _integer(counts, "inference_frames_submitted")
    emitted = _integer(counts, "inference_records_emitted")
    detected = _integer(counts, "pose_detected_records")
    skipped = _integer(counts, "frames_skipped_before_inference")
    trailing = _integer(counts, "trailing_frames_skipped_after_last_emitted_record")
    failures = _integer(counts, "inference_failures")

    if (
        camera_received is not None
        and submitted is not None
        and skipped is not None
        and camera_received != submitted + skipped
    ):
        issues.append(
            _session_issue(
                path,
                "camera_frames_received must equal inference_frames_submitted plus "
                "frames_skipped_before_inference",
                pointer="/counts/camera_frames_received",
                expected=submitted + skipped,
                actual=camera_received,
            )
        )

    if (
        submitted is not None
        and emitted is not None
        and failures is not None
        and submitted != emitted + failures
    ):
        issues.append(
            _session_issue(
                path,
                "inference_frames_submitted must equal inference_records_emitted plus "
                "inference_failures",
                pointer="/counts/inference_frames_submitted",
                expected=emitted + failures,
                actual=submitted,
            )
        )

    if emitted is not None and emitted != len(frames):
        issues.append(
            _session_issue(
                path,
                "inference_records_emitted must equal the number of emitted frame records",
                pointer="/counts/inference_records_emitted",
                expected=len(frames),
                actual=emitted,
            )
        )

    detected_rows = sum(1 for frame in frames if _flag(frame, "pose_present") is True)
    if detected is not None and detected != detected_rows:
        issues.append(
            _session_issue(
                path,
                "pose_detected_records must equal emitted records with pose_present true",
                pointer="/counts/pose_detected_records",
                expected=detected_rows,
                actual=detected,
            )
        )

    machine_failures = manifest.get("machine_inference_failures")
    if isinstance(machine_failures, list):
        submission_indices: set[int] = set()
        if failures is not None and len(machine_failures) != failures:
            issues.append(
                _session_issue(
                    path,
                    "machine_inference_failures length must equal inference_failures",
                    pointer="/machine_inference_failures",
                    expected=failures,
                    actual=len(machine_failures),
                )
            )
        for index, failure in enumerate(machine_failures):
            if not isinstance(failure, Mapping):
                continue
            submission_index = _integer(failure, "submission_index")
            if submission_index is not None:
                if submitted is not None and not 0 <= submission_index < submitted:
                    issues.append(
                        _session_issue(
                            path,
                            "machine inference failure submission_index must identify a "
                            "submitted call",
                            pointer=f"/machine_inference_failures/{index}/submission_index",
                            expected=f"0 <= index < {submitted}",
                            actual=submission_index,
                        )
                    )
                elif submission_index in submission_indices:
                    issues.append(
                        _session_issue(
                            path,
                            "machine inference failure submission_index values must be unique",
                            pointer=f"/machine_inference_failures/{index}/submission_index",
                            actual=submission_index,
                        )
                    )
                else:
                    submission_indices.add(submission_index)
            classification = _text(failure, "classification")
            if classification not in _FAILURE_CLASSIFICATIONS:
                issues.append(
                    _session_issue(
                        path,
                        "machine inference failure has an unknown runtime classification",
                        pointer=f"/machine_inference_failures/{index}/classification",
                        expected=sorted(_FAILURE_CLASSIFICATIONS),
                        actual=classification,
                    )
                )

    frame_skips = [
        _integer(frame, "frames_skipped_since_last_emitted_record") for frame in frames
    ]
    if (
        skipped is not None
        and trailing is not None
        and all(value is not None for value in frame_skips)
    ):
        skipped_from_frames = sum(value for value in frame_skips if value is not None)
        if skipped_from_frames + trailing != skipped:
            issues.append(
                _session_issue(
                    path,
                    "per-frame skipped counts plus trailing skipped frames must equal "
                    "frames_skipped_before_inference",
                    pointer="/counts/frames_skipped_before_inference",
                    expected=skipped_from_frames + trailing,
                    actual=skipped,
                )
            )

    timeout = _integer(manifest, "inference_drain_timeout_ms")
    if timeout is not None and timeout <= 0:
        issues.append(
            _session_issue(
                path,
                "inference_drain_timeout_ms must be positive",
                pointer="/inference_drain_timeout_ms",
                expected="> 0",
                actual=timeout,
            )
        )

    media_references = manifest.get("media_references")
    if isinstance(media_references, list):
        roles: list[str] = []
        for reference in media_references:
            if isinstance(reference, Mapping):
                role = _text(reference, "role")
                if role is not None:
                    roles.append(role)

        study_context = manifest.get("study_context")
        if isinstance(study_context, Mapping) and study_context:
            static_reference_count = roles.count("static_reference_clip")
            if static_reference_count != 1:
                issues.append(
                    _session_issue(
                        path,
                        "a study session must contain exactly one static_reference_clip",
                        pointer="/media_references",
                        expected=1,
                        actual=static_reference_count,
                    )
                )

        seen_roles: set[str] = set()
        for index, reference in enumerate(media_references):
            if not isinstance(reference, Mapping):
                continue
            role = _text(reference, "role")
            if role is not None and role in seen_roles:
                issues.append(
                    _session_issue(
                        path,
                        "media reference roles must be unique",
                        pointer=f"/media_references/{index}/role",
                        actual=role,
                    )
                )
            if role is not None:
                seen_roles.add(role)

            has_offset = "sync_offset_ns" in reference
            has_drift = "sync_drift_ns_per_s" in reference
            if has_offset != has_drift:
                issues.append(
                    _session_issue(
                        path,
                        "synchronized media must include both sync_offset_ns and "
                        "sync_drift_ns_per_s",
                        pointer=f"/media_references/{index}",
                        expected="sync_offset_ns and sync_drift_ns_per_s together",
                        actual=sorted(
                            key
                            for key, present in (
                                ("sync_offset_ns", has_offset),
                                ("sync_drift_ns_per_s", has_drift),
                            )
                            if present
                        ),
                    )
                )

    return issues


def validate_annotations(
    manifest: Mapping[str, Any],
    annotations: Sequence[Mapping[str, Any]],
    paths: Sequence[str],
) -> list[ValidationIssue]:
    """Return deterministic provenance and observational-label issues.

    Structural validation owns required fields, primitive types, and closed enumerations.
    This pass checks relationships between annotation artifacts and the label invariants that
    need an artifact-level semantic interpretation. Annotation labels remain observations:
    this function does not infer movement, apply thresholds, or validate predictions.
    """
    issues: list[ValidationIssue] = []
    by_id: dict[str, tuple[Mapping[str, Any], str]] = {}

    for index, annotation in enumerate(annotations):
        path = _annotation_path(paths, index)
        annotation_id = _text(annotation, "annotation_id")
        if annotation_id is None:
            continue
        if annotation_id in by_id:
            issues.append(
                _annotation_issue(
                    "ANNOTATION.LINK_DUPLICATE",
                    path,
                    "annotation_id is duplicated by another annotation artifact",
                    pointer="/annotation_id",
                    actual=annotation_id,
                )
            )
            continue
        by_id[annotation_id] = (annotation, path)

    manifest_session_id = _text(manifest, "session_id")
    for index, annotation in enumerate(annotations):
        path = _annotation_path(paths, index)
        session_id = _text(annotation, "session_id")
        if (
            session_id is not None
            and manifest_session_id is not None
            and session_id != manifest_session_id
        ):
            issues.append(
                _annotation_issue(
                    "ANNOTATION.LINK_MISSING",
                    path,
                    "annotation session_id does not match the manifest session_id",
                    pointer="/session_id",
                    expected=manifest_session_id,
                    actual=session_id,
                )
            )

        stage = _text(annotation, "annotation_stage")
        input_ids = annotation.get("input_annotation_ids")
        inputs = input_ids if isinstance(input_ids, list) else []

        if stage not in {"independent", "adjudicated"}:
            issues.append(
                _annotation_issue(
                    "ANNOTATION.INVALID_STAGE",
                    path,
                    "annotation_stage must be independent or adjudicated",
                    pointer="/annotation_stage",
                    expected=["adjudicated", "independent"],
                    actual=stage,
                )
            )
        elif stage == "independent" and inputs:
            issues.append(
                _annotation_issue(
                    "ANNOTATION.INVALID_STAGE",
                    path,
                    "independent annotations must not link input annotations",
                    pointer="/input_annotation_ids",
                    expected=[],
                    actual=inputs,
                )
            )
        elif stage == "adjudicated" and (
            len(inputs) < 2 or len({item for item in inputs if isinstance(item, str)}) != len(inputs)
        ):
            issues.append(
                _annotation_issue(
                    "ANNOTATION.INVALID_STAGE",
                    path,
                    "adjudicated annotations must link at least two distinct inputs",
                    pointer="/input_annotation_ids",
                    expected="at least two distinct annotation IDs",
                    actual=inputs,
                )
            )

        if stage == "adjudicated":
            for input_index, input_id in enumerate(inputs):
                if not isinstance(input_id, str) or input_id not in by_id:
                    issues.append(
                        _annotation_issue(
                            "ANNOTATION.LINK_MISSING",
                            path,
                            "input_annotation_id does not identify a supplied annotation",
                            pointer=f"/input_annotation_ids/{input_index}",
                            actual=input_id,
                        )
                    )
                    continue

                linked, _linked_path = by_id[input_id]
                linked_stage = _text(linked, "annotation_stage")
                if linked_stage != "independent":
                    issues.append(
                        _annotation_issue(
                            "ANNOTATION.INVALID_STAGE",
                            path,
                            "adjudicated annotations may link only independent annotations",
                            pointer=f"/input_annotation_ids/{input_index}",
                            expected="independent",
                            actual=linked_stage,
                        )
                    )

                linked_session_id = _text(linked, "session_id")
                if (
                    session_id is not None
                    and linked_session_id is not None
                    and linked_session_id != session_id
                ):
                    issues.append(
                        _annotation_issue(
                            "ANNOTATION.LINK_MISSING",
                            path,
                            "linked annotation belongs to a different session",
                            pointer=f"/input_annotation_ids/{input_index}",
                            expected=session_id,
                            actual=linked_session_id,
                        )
                    )

        events = annotation.get("events")
        if isinstance(events, list):
            issues.extend(_annotation_event_issues(events, path))

    return issues


def _annotation_event_issues(
    events: list[Any],
    path: str,
) -> list[ValidationIssue]:
    """Return semantic issues for the observational label fields of each event."""
    issues: list[ValidationIssue] = []
    for event_index, event in enumerate(events):
        if not isinstance(event, Mapping):
            continue
        prefix = f"/events/{event_index}"
        event_type = _text(event, "type")
        rep_index = event.get("rep_index")
        label = _text(event, "label")
        reasons = event.get("invalid_reasons")
        reason_values = reasons if isinstance(reasons, list) else []

        if event_type == "rep_label" and (
            "rep_index" not in event or not isinstance(rep_index, int) or isinstance(rep_index, bool)
        ):
            issues.append(
                _annotation_issue(
                    "ANNOTATION.INVALID_LABEL",
                    path,
                    "rep_label events require rep_index",
                    pointer=f"{prefix}/rep_index",
                )
            )

        if label == "invalid" and not reason_values:
            issues.append(
                _annotation_issue(
                    "ANNOTATION.INVALID_LABEL",
                    path,
                    "invalid labels require at least one invalid reason",
                    pointer=f"{prefix}/invalid_reasons",
                    expected="at least one reason",
                    actual=reason_values,
                )
            )
        elif label != "invalid" and reason_values:
            issues.append(
                _annotation_issue(
                    "ANNOTATION.INVALID_LABEL",
                    path,
                    "only invalid labels may carry invalid reasons",
                    pointer=f"{prefix}/invalid_reasons",
                    expected=[],
                    actual=reason_values,
                )
            )
    return issues


def _annotation_issue(
    code: str,
    path: str,
    message: str,
    *,
    pointer: str | None = None,
    expected: Any = None,
    actual: Any = None,
) -> ValidationIssue:
    """Return one annotation issue with the stable artifact identity."""
    return ValidationIssue(
        code=code,
        artifact_path=path,
        artifact_kind="annotation",
        message=message,
        pointer=pointer,
        expected=expected,
        actual=actual,
    )


def _annotation_path(paths: Sequence[str], index: int) -> str:
    """Return the supplied path for an annotation, with a stable fallback for malformed input."""
    return paths[index] if index < len(paths) else f"annotation[{index}]"


def _session_issue(
    path: str,
    message: str,
    *,
    pointer: str,
    expected: Any = None,
    actual: Any = None,
) -> ValidationIssue:
    """Return one session-level issue with the stable artifact identity."""
    return ValidationIssue(
        code=_SESSION_COUNT_CODE,
        artifact_path=path,
        artifact_kind=_MANIFEST_KIND,
        message=message,
        pointer=pointer,
        expected=expected,
        actual=actual,
    )


def _resolve_topology(
    manifest: Mapping[str, Any],
    manifest_path: str,
) -> tuple[LandmarkTopology | None, list[ValidationIssue]]:
    """Return the manifest's topology, or ``None`` and the issue explaining why it is unusable.

    The issue is raised against the manifest field that declared the topology, because that
    is the value a human has to change, even though the code names the frame-level
    consequence.
    """
    declared = _text(_mapping(manifest, "pose_pipeline"), "landmark_topology")
    if declared is None:
        return None, []
    try:
        return get_topology(declared), []
    except UnknownTopologyError:
        return None, [
            ValidationIssue(
                code="FRAME.TOPOLOGY_UNSUPPORTED",
                artifact_path=manifest_path,
                artifact_kind=_MANIFEST_KIND,
                message=(
                    f"landmark topology {declared!r} is not one this reader can interpret; "
                    "landmark cardinality was not inspected"
                ),
                pointer="/pose_pipeline/landmark_topology",
                expected=list(supported_topology_names()),
                actual=declared,
            )
        ]


def _constancy_issues(
    frame: Mapping[str, Any],
    position: int,
    rules: _StreamRules,
) -> list[ValidationIssue]:
    """Return issues for what must not change across a stream: artifact version and session.

    Both are compared against a fixed anchor rather than against the previous record — the
    stream's first record for the version, the manifest for the session — so every diverging
    record is reported instead of only the one where the value first changed.
    """
    issues: list[ValidationIssue] = []

    version = _text(frame, "raw_pose_frame_schema_version")
    if version is not None and rules.schema_version is not None and version != rules.schema_version:
        issues.append(
            _issue(
                "STREAM.SCHEMA_VERSION_CHANGED",
                rules,
                position,
                f"schema version {version} differs from the stream's first record",
                pointer="/raw_pose_frame_schema_version",
                expected=rules.schema_version,
                actual=version,
            )
        )

    session = _text(frame, "session_id")
    if session is not None and rules.session_id is not None and session != rules.session_id:
        issues.append(
            _issue(
                "STREAM.SESSION_CHANGED",
                rules,
                position,
                "session_id does not match the manifest this stream is joined to",
                pointer="/session_id",
                expected=rules.session_id,
                actual=session,
            )
        )
    return issues


def _sequence_issues(
    frame: Mapping[str, Any],
    previous: Mapping[str, Any] | None,
    position: int,
    rules: _StreamRules,
) -> list[ValidationIssue]:
    """Return issues for record ordering and for a timing sequence that cannot have happened.

    Neither order check states an expected value: strict increase is required but contiguity
    is not, so there is no single correct next index or timestamp to report.
    """
    issues: list[ValidationIssue] = []
    index = _integer(frame, "frame_index")
    capture = _integer(frame, "capture_timestamp_ns")

    if previous is not None:
        previous_index = _integer(previous, "frame_index")
        if index is not None and previous_index is not None and index <= previous_index:
            issues.append(
                _issue(
                    "FRAME.INDEX_ORDER",
                    rules,
                    position,
                    f"frame_index {index} does not exceed the previous record's {previous_index}",
                    pointer="/frame_index",
                    actual=index,
                )
            )
        previous_capture = _integer(previous, "capture_timestamp_ns")
        if capture is not None and previous_capture is not None and capture <= previous_capture:
            issues.append(
                _issue(
                    "FRAME.TIMESTAMP_ORDER",
                    rules,
                    position,
                    f"capture_timestamp_ns {capture} does not exceed the previous "
                    f"record's {previous_capture}",
                    pointer="/capture_timestamp_ns",
                    actual=capture,
                )
            )

    start = _integer(frame, "inference_start_ns")
    end = _integer(frame, "inference_end_ns")
    if capture is not None and start is not None and start < capture:
        issues.append(
            _issue(
                "FRAME.INFERENCE_TIME_ORDER",
                rules,
                position,
                f"inference_start_ns {start} precedes capture_timestamp_ns {capture}",
                pointer="/inference_start_ns",
                actual=start,
            )
        )
    if start is not None and end is not None and end < start:
        issues.append(
            _issue(
                "FRAME.INFERENCE_TIME_ORDER",
                rules,
                position,
                f"inference_end_ns {end} precedes inference_start_ns {start}",
                pointer="/inference_end_ns",
                actual=end,
            )
        )
    return issues


def _landmark_issues(
    frame: Mapping[str, Any],
    position: int,
    rules: _StreamRules,
) -> list[ValidationIssue]:
    """Return issues for the coordinate arrays of one record.

    A record with no detected pose is a complete observation, not a gap to be filled, so the
    only rule that applies to it is that neither coordinate family was fabricated.
    """
    pose_present = _flag(frame, "pose_present")
    if pose_present is None:
        return []

    normalized = frame.get(_NORMALIZED)
    world = frame.get(_WORLD)

    if not pose_present:
        families = ((_NORMALIZED, normalized), (_WORLD, world))
        carried = [name for name, value in families if value is not None]
        if not carried:
            return []
        return [
            _issue(
                "FRAME.ABSENT_POSE_HAS_LANDMARKS",
                rules,
                position,
                f"pose_present is false but {' and '.join(carried)} is not null",
                pointer=f"/{carried[0]}",
            )
        ]

    issues: list[ValidationIssue] = []
    if rules.topology is not None:
        issues.extend(_count_issues(normalized, world, position, rules.topology, rules))

    if normalized is not None and rules.normalized_available is False:
        issues.append(
            _issue(
                "FRAME.NORMALIZED_AVAILABILITY",
                rules,
                position,
                "normalized image landmarks are present but the manifest declares them "
                "unavailable for this session",
                pointer=f"/{_NORMALIZED}",
            )
        )

    if world is not None and rules.world_available is False:
        issues.append(
            _issue(
                "FRAME.WORLD_AVAILABILITY",
                rules,
                position,
                "world landmarks are present but the manifest declares them unavailable "
                "for this session",
                pointer=f"/{_WORLD}",
            )
        )

    if _arrays_are_identical(normalized, world):
        issues.append(
            _issue(
                "FRAME.COORDINATE_ARRAYS_IDENTICAL",
                rules,
                position,
                "normalized image landmarks and world landmarks are byte-identical, so one "
                "coordinate family was copied rather than observed",
                pointer=f"/{_WORLD}",
            )
        )
    return issues


def _count_issues(
    normalized: object,
    world: object,
    position: int,
    topology: LandmarkTopology,
    rules: _StreamRules,
) -> list[ValidationIssue]:
    """Return cardinality issues for a present pose, measured only against the declared topology.

    A present pose carries a complete normalized array. World landmarks are either absent
    for this record or complete; a partial world array is rejected because its landmarks
    cannot be aligned to topology indexes safely.
    """
    issues: list[ValidationIssue] = []
    expected = topology.landmark_count

    normalized_count = len(normalized) if isinstance(normalized, list) else None
    if normalized_count != expected:
        issues.append(
            _issue(
                "FRAME.LANDMARK_COUNT",
                rules,
                position,
                f"present pose carries {_describe(normalized_count)} normalized landmarks; "
                f"topology {topology.name} declares {expected}",
                pointer=f"/{_NORMALIZED}",
                expected=expected,
                actual=normalized_count,
            )
        )

    if world is not None:
        world_count = len(world) if isinstance(world, list) else None
        if world_count != expected:
            issues.append(
                _issue(
                    "FRAME.LANDMARK_COUNT",
                    rules,
                    position,
                    f"world landmarks are present but carry {_describe(world_count)} entries; "
                    f"topology {topology.name} declares {expected}",
                    pointer=f"/{_WORLD}",
                    expected=expected,
                    actual=world_count,
                )
            )
    return issues


def _issue(
    code: str,
    rules: _StreamRules,
    position: int,
    message: str,
    *,
    pointer: str | None = None,
    expected: Any = None,
    actual: Any = None,
) -> ValidationIssue:
    """Return one stream issue, stamped with the record's position in the stream."""
    return ValidationIssue(
        code=code,
        artifact_path=rules.frame_path,
        artifact_kind=_FRAME_KIND,
        message=f"record {position}: {message}",
        pointer=pointer,
        expected=expected,
        actual=actual,
    )


def _arrays_are_identical(normalized: object, world: object) -> bool:
    """Return whether both coordinate families are present and serialize to the same bytes.

    Comparison is on the canonical serialization rather than on the parsed values, so this
    stays an exact-duplication check: it cannot be satisfied by two arrays that merely
    compare equal in Python, such as an integer coordinate and its float twin.
    """
    if not isinstance(normalized, list) or not isinstance(world, list):
        return False
    return _serialized(normalized) == _serialized(world)


def _serialized(landmarks: list[Any]) -> str:
    """Return one canonical serialization of a landmark array, for exact-equality comparison."""
    return json.dumps(landmarks, sort_keys=True, separators=(",", ":"))


def _describe(count: int | None) -> str:
    """Describe an observed cardinality, distinguishing a null array from an empty one."""
    return "no" if count is None else str(count)


def _mapping(document: Mapping[str, Any], key: str) -> Mapping[str, Any]:
    """Return a nested object, or an empty mapping when the structural pass already rejected it."""
    value = document.get(key)
    return value if isinstance(value, Mapping) else {}


def _text(document: Mapping[str, Any], key: str) -> str | None:
    """Return a string field, or ``None`` when it is absent or not a string."""
    value = document.get(key)
    return value if isinstance(value, str) else None


def _integer(document: Mapping[str, Any], key: str) -> int | None:
    """Return an integer field, or ``None`` when it is absent or not an integer.

    ``bool`` is excluded deliberately: it is a subclass of ``int`` in Python, and accepting
    it here would let a boolean be compared as a timestamp.
    """
    value = document.get(key)
    return value if isinstance(value, int) and not isinstance(value, bool) else None


def _flag(document: Mapping[str, Any], key: str) -> bool | None:
    """Return a boolean field, or ``None`` when it is absent or not a boolean."""
    value = document.get(key)
    return value if isinstance(value, bool) else None
