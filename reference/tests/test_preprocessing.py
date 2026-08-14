from __future__ import annotations

import json
from pathlib import Path

import pytest

from observation_contracts.preprocessing import scale_pad_rgb24, verify_preprocessing_vector


def test_scale_pad_scales_to_target_when_no_padding_is_needed() -> None:
    source = bytes(
        [
            10,
            20,
            30,
            10,
            20,
            30,
            10,
            20,
            30,
            10,
            20,
            30,
            10,
            20,
            30,
            10,
            20,
            30,
        ]
    )

    assert scale_pad_rgb24(source, 3, 2, 5, 3) == bytes(
        [
            10,
            20,
            30,
            10,
            20,
            30,
            10,
            20,
            30,
            10,
            20,
            30,
            10,
            20,
            30,
            10,
            20,
            30,
            10,
            20,
            30,
            10,
            20,
            30,
            10,
            20,
            30,
            10,
            20,
            30,
            10,
            20,
            30,
            10,
            20,
            30,
            10,
            20,
            30,
            10,
            20,
            30,
            10,
            20,
            30,
        ]
    )


def test_scale_pad_rounds_exact_half_up_and_assigns_odd_bottom_padding() -> None:
    source = bytes(
        [
            10,
            20,
            30,
            10,
            20,
            30,
            10,
            20,
            30,
            10,
            20,
            30,
            10,
            20,
            30,
            10,
            20,
            30,
            10,
            20,
            30,
            10,
            20,
            30,
        ]
    )

    # Scale is min(3/4, 3/2) = 3/4, so scaled height is
    # floor(2 * 3/4 + 1/2) = 2. The remaining row is bottom padding.
    assert scale_pad_rgb24(source, 4, 2, 3, 3) == bytes(
        [
            10,
            20,
            30,
            10,
            20,
            30,
            10,
            20,
            30,
            10,
            20,
            30,
            10,
            20,
            30,
            10,
            20,
            30,
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            0,
        ]
    )


def test_scale_pad_uses_centre_pixel_bilinear_interpolation_and_odd_black_padding() -> None:
    source = bytes(
        [
            255,
            0,
            0,
            0,
            255,
            0,
            0,
            0,
            255,
            255,
            255,
            255,
        ]
    )
    # Scale is min(5/2, 4/2) = 2, so the image is 4 by 4 and the one-column
    # remainder is right padding. For destination x/y 0..3, the clamped source
    # coordinates are 0, 1/4, 3/4, 1. For example, output byte 3 is
    # floor((3/4 * 255) + 1/2) = 191. Every channel below is hand-derived from
    # those fractions; the final RGB triplet in each row is black padding.
    expected = bytes(
        [
            255,
            0,
            0,
            191,
            64,
            0,
            64,
            191,
            0,
            0,
            255,
            0,
            0,
            0,
            0,
            191,
            0,
            64,
            159,
            64,
            64,
            96,
            191,
            64,
            64,
            255,
            64,
            0,
            0,
            0,
            64,
            0,
            191,
            96,
            64,
            191,
            159,
            191,
            191,
            191,
            255,
            191,
            0,
            0,
            0,
            0,
            0,
            255,
            64,
            64,
            255,
            191,
            191,
            255,
            255,
            255,
            255,
            0,
            0,
            0,
        ]
    )

    assert scale_pad_rgb24(source, 2, 2, 5, 4) == expected


@pytest.mark.parametrize(
    ("source", "source_width", "source_height", "message"),
    [
        (b"\x00\x01", 1, 1, "source byte length"),
        (b"\x00\x01\x02", 0, 1, "dimensions"),
    ],
)
def test_scale_pad_rejects_invalid_source_or_dimensions(
    source: bytes,
    source_width: int,
    source_height: int,
    message: str,
) -> None:
    with pytest.raises(ValueError, match=message):
        scale_pad_rgb24(source, source_width, source_height, 1, 1)


def test_verify_preprocessing_vector_accepts_matching_descriptor_hashes(tmp_path: Path) -> None:
    input_bytes = b"\x01\x02\x03"
    # A 1 by 1 RGB24 image scaled to 1 by 1 must preserve its sole pixel.
    output_bytes = b"\x01\x02\x03"
    (tmp_path / "input.rgb24").write_bytes(input_bytes)
    (tmp_path / "output.rgb24").write_bytes(output_bytes)
    descriptor = {
        "tooling_schema_version": "1.0.0",
        "synthetic": True,
        "input_width": 1,
        "input_height": 1,
        "output_width": 1,
        "output_height": 1,
        "input_path": "input.rgb24",
        "input_byte_length": 3,
        "input_sha256": "039058c6f2c0cb492c533b0a4d14ef77cc0f78abccced5287d84a1a2011cfb81",
        "expected_output_path": "output.rgb24",
        "expected_output_byte_length": 3,
        "expected_output_sha256": "039058c6f2c0cb492c533b0a4d14ef77cc0f78abccced5287d84a1a2011cfb81",
    }
    (tmp_path / "vector.json").write_text(json.dumps(descriptor), encoding="utf-8")

    issues = verify_preprocessing_vector(tmp_path / "vector.json")

    assert issues == []


def test_verify_preprocessing_vector_rejects_path_traversal(tmp_path: Path) -> None:
    descriptor = {
        "tooling_schema_version": "1.0.0",
        "synthetic": True,
        "input_width": 1,
        "input_height": 1,
        "output_width": 1,
        "output_height": 1,
        "input_path": "../outside.rgb24",
        "input_byte_length": 3,
        "input_sha256": "039058c6f2c0cb492c533b0a4d14ef77cc0f78abccced5287d84a1a2011cfb81",
        "expected_output_path": "../outside.rgb24",
        "expected_output_byte_length": 3,
        "expected_output_sha256": "039058c6f2c0cb492c533b0a4d14ef77cc0f78abccced5287d84a1a2011cfb81",
    }
    descriptor_path = tmp_path / "vector.json"
    descriptor_path.write_text(json.dumps(descriptor), encoding="utf-8")

    issues = verify_preprocessing_vector(descriptor_path)

    assert [issue.code for issue in issues] == ["PREPROCESSING.VECTOR_INVALID"]
