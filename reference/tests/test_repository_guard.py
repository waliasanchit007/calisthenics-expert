"""Regression tests for the repository data guard.

These tests create real temporary Git repositories and populate their indexes so the
guard is exercised at the same boundary as the tracked-tree and pre-commit callers.
"""

from __future__ import annotations

import hashlib
import json
import subprocess
from pathlib import Path
from typing import Any

import pytest

from observation_contracts.repository_guard import guard_repository


def _git(root: Path, *arguments: str) -> None:
    subprocess.run(
        ["git", "-C", str(root), *arguments],
        check=True,
        capture_output=True,
        text=True,
    )


def _repository(tmp_path: Path) -> Path:
    root = tmp_path / "repository"
    root.mkdir()
    _git(root, "init", "--quiet")
    _git(root, "config", "user.email", "tests@example.invalid")
    _git(root, "config", "user.name", "Repository Guard Tests")
    registry = (
        Path(__file__).resolve().parents[2] / "contracts/tooling/validator_error_codes.v1.json"
    )
    _write(root, "contracts/tooling/validator_error_codes.v1.json", registry.read_bytes())
    return root


def _write(root: Path, relative_path: str, content: str | bytes) -> None:
    path = root / relative_path
    path.parent.mkdir(parents=True, exist_ok=True)
    if isinstance(content, str):
        path.write_text(content, encoding="utf-8")
    else:
        path.write_bytes(content)


def _stage(root: Path) -> None:
    _git(root, "add", "--all")


def _codes(root: Path, mode: str = "tracked") -> list[tuple[str, str]]:
    return [(issue.artifact_path, issue.code) for issue in guard_repository(root, mode=mode)]


def _sha256(content: bytes) -> str:
    return hashlib.sha256(content).hexdigest()


def _private_key_marker() -> str:
    return "-----BEGIN " + "PRIVATE KEY-----"


def _json(document: dict[str, Any]) -> str:
    return json.dumps(document, indent=2, sort_keys=True) + "\n"


def _fixture_metadata(frame_path: str, frame: bytes) -> dict[str, Any]:
    return {
        "fixture_metadata_schema_version": "1.0.0",
        "fixture_id": "4d5e6f7a-8b9c-4d0e-9f1a-2b3c4d5e6f7a",
        "synthetic": True,
        "preprocessing_contract_version": "1.0.0",
        "frame_width": 1,
        "frame_height": 1,
        "pixel_format": "rgb24",
        "color_space": "srgb",
        "color_range": "full",
        "channel_order": "rgb",
        "orientation": "upright",
        "frames": [
            {
                "frame_index": 0,
                "timestamp_ns": 0,
                "path": frame_path,
                "byte_length": len(frame),
                "sha256": _sha256(frame),
            }
        ],
    }


def test_staged_mode_rejects_video_and_audio_paths_from_real_index(tmp_path: Path) -> None:
    root = _repository(tmp_path)
    _write(root, "captures/workout.mp4", b"synthetic video placeholder")
    _write(root, "captures/microphone.wav", b"synthetic audio placeholder")
    _stage(root)

    assert _codes(root, mode="staged") == [
        ("captures/microphone.wav", "REPOSITORY.PROHIBITED_PATH"),
        ("captures/workout.mp4", "REPOSITORY.PROHIBITED_PATH"),
    ]


def test_rejects_pose_dump_outside_synthetic_fixture_roots(tmp_path: Path) -> None:
    root = _repository(tmp_path)
    _write(
        root,
        "exports/pose.ndjson",
        '{"pose_present":true,"normalized_image_landmarks":[],"world_landmarks":[]}\n',
    )
    _stage(root)

    issues = guard_repository(root, mode="staged")

    assert [(issue.code, issue.artifact_path) for issue in issues] == [
        ("REPOSITORY.PROHIBITED_PATH", "exports/pose.ndjson")
    ]


@pytest.mark.parametrize(
    "relative_path",
    [
        ".env",
        "auth.json",
        "credentials.yaml",
        "consent_form.pdf",
        "participant_identity.csv",
        "identity_mapping.json",
        "keys/private.pem",
    ],
)
def test_rejects_sensitive_filenames(tmp_path: Path, relative_path: str) -> None:
    root = _repository(tmp_path)
    _write(root, relative_path, b"synthetic sensitive placeholder")
    _stage(root)

    assert _codes(root, mode="staged") == [(relative_path, "REPOSITORY.PROHIBITED_PATH")]


def test_rejects_pem_private_key_content_even_with_benign_filename(tmp_path: Path) -> None:
    root = _repository(tmp_path)
    _write(
        root,
        "notes/example.txt",
        _private_key_marker() + "\nsynthetic\n-----END " + "PRIVATE KEY-----\n",
    )
    _stage(root)

    assert _codes(root, mode="staged") == [("notes/example.txt", "REPOSITORY.PROHIBITED_CONTENT")]


@pytest.mark.parametrize("mode", ["staged", "tracked"])
@pytest.mark.parametrize("relative_path", ["identity_keys/key.jwk", "identity-keys/key.jwk"])
def test_rejects_private_jwk_below_identity_key_directories(
    tmp_path: Path, mode: str, relative_path: str
) -> None:
    root = _repository(tmp_path)
    _write(root, relative_path, b'{"kty":"RSA","n":"public","e":"AQAB","d":"private"}\n')
    _stage(root)

    assert _codes(root, mode=mode) == [(relative_path, "REPOSITORY.PROHIBITED_PATH")]


@pytest.mark.parametrize("mode", ["staged", "tracked"])
def test_accepts_documentation_mentioning_identities(tmp_path: Path, mode: str) -> None:
    root = _repository(tmp_path)
    _write(
        root,
        "docs/identity-policy.md",
        "This documentation explains how identities are handled outside the repository.\n",
    )
    _stage(root)

    assert guard_repository(root, mode=mode) == []


def test_accepts_documentation_containing_benign_consent_word(tmp_path: Path) -> None:
    root = _repository(tmp_path)
    _write(
        root,
        "docs/data-consent-policy.md",
        "This documentation explains consent, retention, and deletion.\n",
    )
    _write(root, "contracts/example.schema.json", '{"type":"object"}\n')
    _stage(root)

    assert guard_repository(root, mode="staged") == []


def test_admits_all_three_declared_synthetic_fixture_classes(tmp_path: Path) -> None:
    root = _repository(tmp_path)

    positive_frame = b"\x01\x02\x03"
    _write(root, "fixtures/synthetic/valid/positive/frame.rgb24", positive_frame)
    _write(
        root,
        "fixtures/synthetic/valid/positive/fixture_metadata.json",
        _json(_fixture_metadata("frame.rgb24", positive_frame)),
    )

    _write(
        root,
        "fixtures/synthetic/negative/malformed/expected_failure.json",
        _json(
            {
                "tooling_schema_version": "1.0.0",
                "command": "validate-session",
                "expected_error_code": "SCHEMA.INVALID",
                "artifact_path": "fixtures/synthetic/negative/malformed/bad.ndjson",
            }
        ),
    )
    _write(root, "fixtures/synthetic/negative/malformed/bad.ndjson", b"not a valid frame\n")

    vector_input = b"\x00\x01\x02"
    vector_output = b"\x03\x04\x05"
    _write(root, "fixtures/synthetic/preprocessing/input.rgb24", vector_input)
    _write(root, "fixtures/synthetic/preprocessing/output.rgb24", vector_output)
    _write(
        root,
        "fixtures/synthetic/preprocessing/preprocessing_vector.v1.json",
        _json(
            {
                "tooling_schema_version": "1.0.0",
                "synthetic": True,
                "input_width": 1,
                "input_height": 1,
                "output_width": 1,
                "output_height": 1,
                "input_path": "input.rgb24",
                "input_byte_length": len(vector_input),
                "input_sha256": _sha256(vector_input),
                "expected_output_path": "output.rgb24",
                "expected_output_byte_length": len(vector_output),
                "expected_output_sha256": _sha256(vector_output),
            }
        ),
    )
    _stage(root)

    assert guard_repository(root, mode="staged") == []


@pytest.mark.parametrize("mode", ["staged", "tracked"])
def test_rejects_undeclared_positive_fixture_sibling_in_both_index_modes(
    tmp_path: Path, mode: str
) -> None:
    root = _repository(tmp_path)
    frame = b"\x01\x02\x03"
    session_root = "fixtures/synthetic/valid/session"
    _write(root, f"{session_root}/frame.rgb24", frame)
    _write(
        root,
        f"{session_root}/fixture_metadata.json",
        _json(_fixture_metadata("frame.rgb24", frame)),
    )
    sibling_path = "fixtures/synthetic/valid/undeclared/pose.ndjson"
    _write(root, sibling_path, b"undeclared pose data\n")
    _stage(root)

    assert _codes(root, mode=mode) == [(sibling_path, "REPOSITORY.INVALID_FIXTURE_DECLARATION")]


@pytest.mark.parametrize("mode", ["staged", "tracked"])
def test_rejects_undeclared_artifact_inside_admitted_positive_fixture(
    tmp_path: Path, mode: str
) -> None:
    root = _repository(tmp_path)
    frame = b"\x01\x02\x03"
    session_root = "fixtures/synthetic/valid/session"
    _write(root, f"{session_root}/decoded/frame.rgb24", frame)
    _write(
        root,
        f"{session_root}/fixture_metadata.json",
        _json(_fixture_metadata("decoded/frame.rgb24", frame)),
    )
    undeclared_path = f"{session_root}/undeclared/pose.ndjson"
    _write(root, undeclared_path, b"undeclared pose data\n")
    _stage(root)

    assert _codes(root, mode=mode) == [(undeclared_path, "REPOSITORY.INVALID_FIXTURE_DECLARATION")]


@pytest.mark.parametrize("mode", ["staged", "tracked"])
@pytest.mark.parametrize("constant", ["NaN", "Infinity", "-Infinity"])
def test_rejects_non_json_constant_in_positive_fixture_declaration(
    tmp_path: Path, mode: str, constant: str
) -> None:
    root = _repository(tmp_path)
    frame = b"\x01\x02\x03"
    declaration = _fixture_metadata("frame.rgb24", frame)
    declaration["ignored_constant"] = float(constant)
    declaration_path = "fixtures/synthetic/valid/session/fixture_metadata.json"
    _write(root, "fixtures/synthetic/valid/session/frame.rgb24", frame)
    _write(root, declaration_path, _json(declaration))
    _stage(root)

    assert _codes(root, mode=mode) == [(declaration_path, "REPOSITORY.INVALID_FIXTURE_DECLARATION")]


def test_admits_committed_nested_preprocessing_vector_layout(tmp_path: Path) -> None:
    root = _repository(tmp_path)
    input_bytes = bytes.fromhex("ff00 0000 ff00 0000 ffff ffff")
    expected_bytes = bytes.fromhex(
        "ff00 00bf 4000 40bf 0000 ff00 0000 00bf "
        "0040 9f40 4060 bf40 40ff 4000 0000 4000 "
        "bf60 40bf 9fbf bfbf ffbf 0000 0000 00ff "
        "4040 ffbf bfff ffff ff00 0000"
    )
    vector_root = "fixtures/synthetic/preprocessing/scale_pad_golden"
    _write(root, f"{vector_root}/input.rgb24", input_bytes)
    _write(root, f"{vector_root}/expected.rgb24", expected_bytes)
    _write(
        root,
        f"{vector_root}/vector.json",
        _json(
            {
                "tooling_schema_version": "1.0.0",
                "synthetic": True,
                "vector_id": "scale_pad_golden_2x2_to_5x4_v1",
                "description": "Distinct 2x2 RGB24 corners scale to 4x4 with exact centre-of-pixel bilinear interpolation and one black right-padding column in a 5x4 target.",
                "input_width": 2,
                "input_height": 2,
                "output_width": 5,
                "output_height": 4,
                "input_path": "input.rgb24",
                "input_byte_length": len(input_bytes),
                "input_sha256": _sha256(input_bytes),
                "expected_output_path": "expected.rgb24",
                "expected_output_byte_length": len(expected_bytes),
                "expected_output_sha256": _sha256(expected_bytes),
            }
        ),
    )
    _stage(root)

    assert guard_repository(root, mode="staged") == []


def test_rejects_self_contained_yuv420_vector_missing_plane_integrity(tmp_path: Path) -> None:
    root = _repository(tmp_path)
    vector_root = "fixtures/synthetic/preprocessing/yuv420_bt601_full"
    _write(
        root,
        f"{vector_root}/vector.json",
        _json(
            {
                "tooling_schema_version": "1.0.0",
                "synthetic": True,
                "vector_id": "yuv420_bt601_full_v1",
                "conversion": {
                    "color_standard": "BT601",
                    "color_range": "FULL",
                    "fixed_point_scale": 1000000,
                    "rounding": "half_up",
                    "clamp": "0..255",
                },
                "cases": [
                    {
                        "case_id": "one_pixel",
                        "width": 1,
                        "height": 1,
                        "y": {"bytes": [16], "row_stride": 1, "pixel_stride": 1},
                        "u": {"bytes": [128], "row_stride": 1, "pixel_stride": 1},
                        "v": {"bytes": [128], "row_stride": 1, "pixel_stride": 1},
                        "color_standard": "BT601",
                        "color_range": "FULL",
                        "rotation": "R0",
                        "mirrored": False,
                        "expected_rgb24": [16, 16, 16],
                        "expected_byte_length": 3,
                        "expected_sha256": _sha256(bytes([16, 16, 16])),
                    }
                ],
            }
        ),
    )
    _stage(root)

    assert _codes(root, mode="staged") == [
        (f"{vector_root}/vector.json", "REPOSITORY.INVALID_FIXTURE_DECLARATION")
    ]


def test_does_not_admit_preprocessing_bytes_from_a_sibling_vector_directory(
    tmp_path: Path,
) -> None:
    root = _repository(tmp_path)
    vector_input = b"input"
    vector_output = b"out"
    vector_root = "fixtures/synthetic/preprocessing/declared_vector"
    _write(root, f"{vector_root}/input.rgb24", vector_input)
    _write(root, f"{vector_root}/output.rgb24", vector_output)
    _write(
        root,
        f"{vector_root}/preprocessing_vector.v1.json",
        _json(
            {
                "tooling_schema_version": "1.0.0",
                "synthetic": True,
                "input_width": 1,
                "input_height": 1,
                "output_width": 1,
                "output_height": 1,
                "input_path": "input.rgb24",
                "input_byte_length": len(vector_input),
                "input_sha256": _sha256(vector_input),
                "expected_output_path": "output.rgb24",
                "expected_output_byte_length": len(vector_output),
                "expected_output_sha256": _sha256(vector_output),
            }
        ),
    )
    sibling_path = "fixtures/synthetic/preprocessing/undeclared_vector/stray.rgb24"
    _write(root, sibling_path, b"undeclared")
    _stage(root)

    assert _codes(root, mode="staged") == [(sibling_path, "REPOSITORY.INVALID_FIXTURE_DECLARATION")]


@pytest.mark.parametrize(
    ("relative_path", "document"),
    [
        (
            "fixtures/synthetic/valid/bad/fixture_metadata.json",
            {
                "fixture_metadata_schema_version": "1.0.0",
                "synthetic": False,
            },
        ),
        (
            "fixtures/synthetic/negative/bad/expected_failure.json",
            {"tooling_schema_version": "1.0.0", "command": "validate-session"},
        ),
    ],
)
def test_rejects_malformed_fixture_declarations(
    tmp_path: Path, relative_path: str, document: dict[str, Any]
) -> None:
    root = _repository(tmp_path)
    _write(root, relative_path, _json(document))
    _stage(root)

    assert _codes(root, mode="staged") == [
        (relative_path, "REPOSITORY.INVALID_FIXTURE_DECLARATION")
    ]


@pytest.mark.parametrize("mode", ["staged", "tracked"])
def test_rejects_unknown_expected_failure_code_from_released_registry(
    tmp_path: Path, mode: str
) -> None:
    root = _repository(tmp_path)
    declaration_path = "fixtures/synthetic/negative/unknown_code/expected_failure.json"
    artifact_path = "fixtures/synthetic/negative/unknown_code/manifest.json"
    _write(
        root,
        declaration_path,
        _json(
            {
                "tooling_schema_version": "1.0.0",
                "command": "validate-session",
                "expected_error_code": "FAKE.NOT_REAL",
                "artifact_path": artifact_path,
            }
        ),
    )
    _write(root, artifact_path, b"not a valid manifest\n")
    _stage(root)

    assert _codes(root, mode=mode) == [(declaration_path, "REPOSITORY.INVALID_FIXTURE_DECLARATION")]


def test_rejects_fixture_declaration_path_traversal(tmp_path: Path) -> None:
    root = _repository(tmp_path)
    _write(root, "fixtures/synthetic/preprocessing/outside.rgb24", b"outside")
    _write(
        root,
        "fixtures/synthetic/preprocessing/preprocessing_vector.v1.json",
        _json(
            {
                "tooling_schema_version": "1.0.0",
                "synthetic": True,
                "input_width": 1,
                "input_height": 1,
                "output_width": 1,
                "output_height": 1,
                "input_path": "../outside.rgb24",
                "input_byte_length": 7,
                "input_sha256": _sha256(b"outside"),
                "expected_output_path": "output.rgb24",
                "expected_output_byte_length": 3,
                "expected_output_sha256": _sha256(b"out"),
            }
        ),
    )
    _write(root, "fixtures/synthetic/preprocessing/output.rgb24", b"out")
    _stage(root)

    assert _codes(root, mode="staged") == [
        (
            "fixtures/synthetic/preprocessing/preprocessing_vector.v1.json",
            "REPOSITORY.INVALID_FIXTURE_DECLARATION",
        )
    ]


def test_does_not_follow_symlink_outside_repository(tmp_path: Path) -> None:
    root = _repository(tmp_path)
    outside = tmp_path / "outside.txt"
    outside.write_text(_private_key_marker() + "\n", encoding="utf-8")
    link = root / "docs" / "linked.txt"
    link.parent.mkdir(parents=True)
    link.symlink_to(outside)
    _stage(root)

    assert _codes(root, mode="tracked") == [("docs/linked.txt", "REPOSITORY.PROHIBITED_PATH")]


def test_signature_scan_is_bounded_to_64_kibibytes(tmp_path: Path) -> None:
    root = _repository(tmp_path)
    _write(
        root,
        "docs/large.txt",
        b"a" * (64 * 1024) + b"\n-----BEGIN " + b"PRIVATE KEY-----\n",
    )
    _stage(root)

    assert guard_repository(root, mode="staged") == []


def test_returns_issues_in_deterministic_path_then_code_order(tmp_path: Path) -> None:
    root = _repository(tmp_path)
    _write(root, "z/secret.txt", _private_key_marker() + "\n")
    _write(root, "a/video.mov", b"synthetic video")
    _write(root, "m/audio.mp3", b"synthetic audio")
    _stage(root)

    assert _codes(root, mode="staged") == [
        ("a/video.mov", "REPOSITORY.PROHIBITED_PATH"),
        ("m/audio.mp3", "REPOSITORY.PROHIBITED_PATH"),
        ("z/secret.txt", "REPOSITORY.PROHIBITED_CONTENT"),
    ]


def test_staged_mode_ignores_unstaged_untracked_files(tmp_path: Path) -> None:
    root = _repository(tmp_path)
    _write(root, "safe.md", "safe\n")
    _stage(root)
    _git(root, "commit", "--quiet", "-m", "baseline")
    _write(root, "not-staged.mp4", b"synthetic video")

    assert guard_repository(root, mode="staged") == []


def test_rejects_a_tracked_model_binary_anywhere_in_the_tree(tmp_path: Path) -> None:
    """`.gitignore` is advisory: it is directory-scoped, and `git add -f` bypasses it entirely.

    A model binary is fetched into an ignored cache and pinned by SHA-256 in a tracked provenance
    manifest, per design section 13, so a tracked blob is a defect wherever it is placed.
    """
    for suffix in (".task", ".tflite", ".onnx"):
        parent = tmp_path / ("repo" + suffix.replace(".", "_"))
        parent.mkdir()
        root = _repository(parent)
        _write(root, f"model-assets/pose_landmarker_full{suffix}", b"\x00" * 2048)
        _stage(root)

        codes = _codes(root, mode="staged")

        assert (
            f"model-assets/pose_landmarker_full{suffix}",
            "REPOSITORY.PROHIBITED_PATH",
        ) in codes, f"{suffix} was not rejected: {codes}"


def test_admits_the_tracked_model_provenance_manifest(tmp_path: Path) -> None:
    """The provenance manifest is the tracked artefact and must remain admissible.

    It is deliberately not named after the pose model it pins: a `pose_*.json` outside synthetic
    fixtures trips the raw-pose-dump rule, which is that rule working correctly.
    """
    root = _repository(tmp_path)
    _write(
        root,
        "model-assets/model_provenance.json",
        '{"asset_name": "pose_landmarker_full.task"}\n',
    )
    _stage(root)

    prohibited = [
        entry for entry in _codes(root, mode="staged") if entry[1] == "REPOSITORY.PROHIBITED_PATH"
    ]

    assert prohibited == []
