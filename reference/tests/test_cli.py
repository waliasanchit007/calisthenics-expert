"""Regression tests for the observation-contract verification CLI."""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any

import pytest

from observation_contracts import cli
from tests.samples import minimal_annotation, minimal_manifest, minimal_pose_frame


def _write_json(path: Path, document: dict[str, Any]) -> None:
    path.write_text(json.dumps(document), encoding="utf-8")


def _write_valid_session(tmp_path: Path) -> tuple[Path, Path, list[Path]]:
    manifest_path = tmp_path / "manifest.json"
    frames_path = tmp_path / "frames.ndjson"
    annotation_paths = [
        tmp_path / "annotation_a.json",
        tmp_path / "annotation_b.json",
        tmp_path / "annotation_consensus.json",
    ]
    _write_json(manifest_path, minimal_manifest())
    frames_path.write_text(json.dumps(minimal_pose_frame()) + "\n", encoding="utf-8")
    _write_json(annotation_paths[0], minimal_annotation())
    _write_json(
        annotation_paths[1],
        minimal_annotation(annotation_id="1b2c3d4e-5f6a-4b7c-8d9e-0f1a2b3c4d5e"),
    )
    _write_json(
        annotation_paths[2],
        minimal_annotation(
            annotation_id="2c3d4e5f-6a7b-4c8d-9e0f-1a2b3c4d5e6f",
            stage="adjudicated",
            input_annotation_ids=[
                "0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d",
                "1b2c3d4e-5f6a-4b7c-8d9e-0f1a2b3c4d5e",
            ],
        ),
    )
    return manifest_path, frames_path, annotation_paths


def test_validate_session_accepts_multiple_linked_annotations(
    tmp_path: Path, capsys: pytest.CaptureFixture[str]
) -> None:
    manifest_path, frames_path, annotation_paths = _write_valid_session(tmp_path)

    result = cli.main(
        [
            "validate-session",
            str(manifest_path),
            str(frames_path),
            *(str(path) for path in annotation_paths),
        ]
    )

    assert result == 0
    assert "valid" in capsys.readouterr().out.lower()


def test_validate_session_reports_malformed_frame_line_in_human_output(
    tmp_path: Path, capsys: pytest.CaptureFixture[str]
) -> None:
    manifest_path = tmp_path / "manifest.json"
    frames_path = tmp_path / "frames.ndjson"
    _write_json(manifest_path, minimal_manifest())
    frames_path.write_text("not-json\n", encoding="utf-8")

    result = cli.main(["validate-session", str(manifest_path), str(frames_path)])

    captured = capsys.readouterr()
    assert result == 2
    assert f"{frames_path}:1" in captured.out
    assert "line is not valid JSON" in captured.out


def test_validate_session_rejects_non_json_nan_in_a_json_artifact(
    tmp_path: Path, capsys: pytest.CaptureFixture[str]
) -> None:
    manifest_path = tmp_path / "manifest.json"
    frames_path = tmp_path / "frames.ndjson"
    manifest = minimal_manifest()
    manifest["pose_pipeline"]["min_pose_detection_confidence"] = float("nan")
    _write_json(manifest_path, manifest)
    frames_path.write_text(json.dumps(minimal_pose_frame()) + "\n", encoding="utf-8")

    result = cli.main(
        ["--format", "json", "validate-session", str(manifest_path), str(frames_path)]
    )

    assert result == 2
    issues = json.loads(capsys.readouterr().out)
    assert [(issue["code"], issue["artifact_path"]) for issue in issues] == [
        ("SCHEMA.INVALID", str(manifest_path))
    ]


def test_validate_session_rejects_non_json_nan_in_an_ndjson_artifact(
    tmp_path: Path, capsys: pytest.CaptureFixture[str]
) -> None:
    manifest_path = tmp_path / "manifest.json"
    frames_path = tmp_path / "frames.ndjson"
    frame = minimal_pose_frame()
    frame["normalized_image_landmarks"][0]["x"] = float("nan")
    _write_json(manifest_path, minimal_manifest())
    frames_path.write_text(json.dumps(frame) + "\n", encoding="utf-8")

    result = cli.main(
        ["--format", "json", "validate-session", str(manifest_path), str(frames_path)]
    )

    assert result == 2
    issues = json.loads(capsys.readouterr().out)
    schema_issues = [issue for issue in issues if issue["code"] == "SCHEMA.INVALID"]
    assert [(issue["code"], issue["artifact_path"], issue["line"]) for issue in schema_issues] == [
        ("SCHEMA.INVALID", str(frames_path), 1)
    ]


def test_json_output_is_a_stable_issue_array_with_exact_keys(
    tmp_path: Path, capsys: pytest.CaptureFixture[str]
) -> None:
    manifest_path = tmp_path / "manifest.json"
    frames_path = tmp_path / "frames.ndjson"
    _write_json(
        manifest_path,
        minimal_manifest(
            camera_frames_received=0,
            inference_frames_submitted=0,
            inference_records_emitted=0,
            pose_detected_records=0,
        ),
    )
    frames_path.write_text("not-json\n", encoding="utf-8")

    result = cli.main(
        ["--format", "json", "validate-session", str(manifest_path), str(frames_path)]
    )

    assert result == 2
    assert json.loads(capsys.readouterr().out) == [
        {
            "code": "SCHEMA.INVALID",
            "artifact_path": str(frames_path),
            "artifact_kind": "raw_pose_frame",
            "message": "line is not valid JSON: Expecting value",
            "line": 1,
        }
    ]


def test_validate_session_skips_v1_semantics_for_an_unknown_manifest_major(
    tmp_path: Path, capsys: pytest.CaptureFixture[str]
) -> None:
    manifest_path = tmp_path / "manifest.json"
    frames_path = tmp_path / "frames.ndjson"
    _write_json(
        manifest_path,
        minimal_manifest(
            schema_version="2.0.0",
            camera_frames_received=2,
            inference_frames_submitted=1,
        ),
    )
    frames_path.write_text(json.dumps(minimal_pose_frame()) + "\n", encoding="utf-8")

    result = cli.main(
        ["--format", "json", "validate-session", str(manifest_path), str(frames_path)]
    )

    assert result == 2
    issues = json.loads(capsys.readouterr().out)
    assert [(issue["code"], issue["artifact_path"]) for issue in issues] == [
        ("VERSION.UNKNOWN_MAJOR", str(manifest_path))
    ]


def test_validate_session_skips_v1_semantics_for_an_unknown_frame_stream_major(
    tmp_path: Path, capsys: pytest.CaptureFixture[str]
) -> None:
    manifest_path = tmp_path / "manifest.json"
    frames_path = tmp_path / "frames.ndjson"
    _write_json(
        manifest_path,
        minimal_manifest(camera_frames_received=2, inference_frames_submitted=1),
    )
    frames_path.write_text(
        json.dumps(minimal_pose_frame(schema_version="2.0.0")) + "\n", encoding="utf-8"
    )

    result = cli.main(
        ["--format", "json", "validate-session", str(manifest_path), str(frames_path)]
    )

    assert result == 2
    issues = json.loads(capsys.readouterr().out)
    assert [(issue["code"], issue["artifact_path"]) for issue in issues] == [
        ("VERSION.UNKNOWN_MAJOR", str(frames_path))
    ]


def test_validate_session_skips_v1_semantics_for_an_unknown_annotation_major(
    tmp_path: Path, capsys: pytest.CaptureFixture[str]
) -> None:
    manifest_path = tmp_path / "manifest.json"
    frames_path = tmp_path / "frames.ndjson"
    annotation_path = tmp_path / "annotation.json"
    _write_json(manifest_path, minimal_manifest())
    frames_path.write_text(json.dumps(minimal_pose_frame()) + "\n", encoding="utf-8")
    _write_json(
        annotation_path,
        minimal_annotation(schema_version="2.0.0", session_id="different-session"),
    )

    result = cli.main(
        [
            "--format",
            "json",
            "validate-session",
            str(manifest_path),
            str(frames_path),
            str(annotation_path),
        ]
    )

    assert result == 2
    issues = json.loads(capsys.readouterr().out)
    assert [(issue["code"], issue["artifact_path"]) for issue in issues] == [
        ("VERSION.UNKNOWN_MAJOR", str(annotation_path))
    ]


def test_unknown_command_returns_usage_failure(
    tmp_path: Path, capsys: pytest.CaptureFixture[str]
) -> None:
    result = cli.main(["unknown-command"])

    captured = capsys.readouterr()
    assert result == 2
    assert "usage:" in captured.err.lower()
    assert "unknown-command" in captured.err


def test_unexpected_exception_is_internal_failure_not_invalid_data(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch, capsys: pytest.CaptureFixture[str]
) -> None:
    manifest_path = tmp_path / "manifest.json"
    frames_path = tmp_path / "frames.ndjson"
    _write_json(manifest_path, minimal_manifest())
    frames_path.write_text(json.dumps(minimal_pose_frame()) + "\n", encoding="utf-8")

    def explode(*args: object, **kwargs: object) -> list[object]:
        raise RuntimeError("injected failure")

    monkeypatch.setattr(cli, "validate_document", explode)

    result = cli.main(["validate-session", str(manifest_path), str(frames_path)])

    captured = capsys.readouterr()
    assert result == 3
    assert "internal failure" in captured.err.lower()
    assert "invalid data" not in captured.err.lower()
