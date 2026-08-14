from __future__ import annotations

import hashlib
import json
from pathlib import Path

from observation_contracts.fixture_integrity import sha256_file, verify_fixture
from observation_contracts.preprocessing import verify_preprocessing_vector


def _write_metadata(
    directory: Path,
    frames: list[dict[str, object]],
) -> Path:
    metadata_path = directory / "fixture.json"
    metadata_path.write_text(
        json.dumps(
            {
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
                "frames": frames,
            }
        ),
        encoding="utf-8",
    )
    return metadata_path


def _frame(path: str, content: bytes, index: int = 0, timestamp_ns: int = 100) -> dict[str, object]:
    return {
        "frame_index": index,
        "timestamp_ns": timestamp_ns,
        "path": path,
        "byte_length": len(content),
        "sha256": hashlib.sha256(content).hexdigest(),
    }


def test_sha256_file_returns_hash_for_known_bytes(tmp_path: Path) -> None:
    path = tmp_path / "frame.rgb24"
    path.write_bytes(b"synthetic-rgb24")

    assert sha256_file(path) == hashlib.sha256(b"synthetic-rgb24").hexdigest()


def test_verify_fixture_accepts_matching_lengths_hashes_and_order(tmp_path: Path) -> None:
    first = b"\x01\x02\x03"
    second = b"\x04\x05\x06"
    (tmp_path / "frame-0.rgb24").write_bytes(first)
    (tmp_path / "frame-1.rgb24").write_bytes(second)
    metadata_path = _write_metadata(
        tmp_path,
        [
            _frame("frame-0.rgb24", first, index=0, timestamp_ns=100),
            _frame("frame-1.rgb24", second, index=1, timestamp_ns=200),
        ],
    )

    assert verify_fixture(metadata_path) == []


def test_verify_fixture_reports_altered_bytes_as_checksum_mismatch(tmp_path: Path) -> None:
    declared = b"\x01\x02\x03"
    (tmp_path / "frame.rgb24").write_bytes(b"\x01\x02\x04")
    metadata_path = _write_metadata(tmp_path, [_frame("frame.rgb24", declared)])

    issues = verify_fixture(metadata_path)

    assert [issue.code for issue in issues] == ["FIXTURE.CHECKSUM_MISMATCH"]


def test_verify_fixture_reports_byte_length_mismatch(tmp_path: Path) -> None:
    content = b"\x01\x02\x03"
    (tmp_path / "frame.rgb24").write_bytes(content)
    frame = _frame("frame.rgb24", content)
    frame["byte_length"] = 4
    metadata_path = _write_metadata(tmp_path, [frame])

    issues = verify_fixture(metadata_path)

    assert [issue.code for issue in issues] == ["FIXTURE.BYTE_LENGTH"]


def test_verify_fixture_reports_missing_frame_indexes_without_timestamp_regression(
    tmp_path: Path,
) -> None:
    first = b"\x01\x02\x03"
    third = b"\x03\x02\x01"
    (tmp_path / "frame-0.rgb24").write_bytes(first)
    (tmp_path / "frame-2.rgb24").write_bytes(third)
    metadata_path = _write_metadata(
        tmp_path,
        [
            _frame("frame-0.rgb24", first, index=0, timestamp_ns=100),
            _frame("frame-2.rgb24", third, index=2, timestamp_ns=200),
        ],
    )

    issues = verify_fixture(metadata_path)

    assert [issue.code for issue in issues] == ["FIXTURE.FRAME_INDEX"]


def test_verify_fixture_reports_duplicate_frame_indexes_without_timestamp_regression(
    tmp_path: Path,
) -> None:
    first = b"\x01\x02\x03"
    second = b"\x04\x05\x06"
    (tmp_path / "frame-a.rgb24").write_bytes(first)
    (tmp_path / "frame-b.rgb24").write_bytes(second)
    metadata_path = _write_metadata(
        tmp_path,
        [
            _frame("frame-a.rgb24", first, index=0, timestamp_ns=100),
            _frame("frame-b.rgb24", second, index=0, timestamp_ns=200),
        ],
    )

    issues = verify_fixture(metadata_path)

    assert [issue.code for issue in issues] == ["FIXTURE.FRAME_INDEX"]


def test_verify_fixture_reports_timestamp_regression(tmp_path: Path) -> None:
    first = b"\x01\x02\x03"
    second = b"\x04\x05\x06"
    (tmp_path / "frame-0.rgb24").write_bytes(first)
    (tmp_path / "frame-1.rgb24").write_bytes(second)
    metadata_path = _write_metadata(
        tmp_path,
        [
            _frame("frame-0.rgb24", first, index=0, timestamp_ns=200),
            _frame("frame-1.rgb24", second, index=1, timestamp_ns=100),
        ],
    )

    issues = verify_fixture(metadata_path)

    assert [issue.code for issue in issues] == ["FIXTURE.TIMESTAMP_ORDER"]


def test_verify_fixture_rejects_path_traversal_outside_metadata_directory(tmp_path: Path) -> None:
    content = b"\x01\x02\x03"
    outside = tmp_path.parent / "outside.rgb24"
    outside.write_bytes(content)
    metadata_path = _write_metadata(tmp_path, [_frame("../outside.rgb24", content)])

    issues = verify_fixture(metadata_path)

    assert [issue.code for issue in issues] == ["PREPROCESSING.VECTOR_INVALID"]


def test_verify_committed_preprocessing_vector() -> None:
    descriptor = Path("fixtures/synthetic/preprocessing/scale_pad_golden/vector.json")

    assert verify_preprocessing_vector(descriptor) == []
