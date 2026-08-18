"""Render the synthetic detection-tier replay fixture.

The contract-tier fixture (5x1) cannot fire the pose detector, so topology and landmark
observation checks would be vacuous against it. This renders an upright synthetic figure at
model-input scale so those checks exercise MediaPipe for real.

The figure is drawn from primitives. It is never a photograph of a person, and no participant
data is involved. Pure standard library: fixtures are generated once and committed, so the
generator must not add dependencies to the reference package.
"""

from __future__ import annotations

import hashlib
import json
import math
from pathlib import Path

WIDTH = 256
HEIGHT = 256
BACKGROUND = (198, 203, 209)
SKIN = (226, 188, 152)
SHIRT = (58, 74, 122)
TROUSERS = (44, 48, 58)
HAIR = (58, 42, 34)
EYE = (32, 30, 30)

# Frame timeline. Frames 1 and 2 share millisecond 33, exercising the backend-timestamp
# `prev + 1` collision branch at detection scale, mirroring the contract-tier fixture.
TIMESTAMPS_NS = [0, 33_333_333, 33_333_500, 66_666_666, 100_000_000, 133_333_333]


def _capsule(
    px: float, py: float, ax: float, ay: float, bx: float, by: float, radius: float
) -> bool:
    dx, dy = bx - ax, by - ay
    span = dx * dx + dy * dy
    t = (
        0.0
        if span == 0
        else max(0.0, min(1.0, ((px - ax) * dx + (py - ay) * dy) / span))
    )
    cx, cy = ax + t * dx, ay + t * dy
    return (px - cx) ** 2 + (py - cy) ** 2 <= radius * radius


def _disc(px: float, py: float, cx: float, cy: float, radius: float) -> bool:
    return (px - cx) ** 2 + (py - cy) ** 2 <= radius * radius


def render(frame_index: int) -> bytes:
    """Render one upright, front-facing figure with a small per-frame limb swing."""
    swing = math.sin(frame_index * 0.5) * 14.0
    lift = math.cos(frame_index * 0.5) * 6.0

    head_cx, head_cy, head_r = 128.0, 40.0, 19.0
    neck = (128.0, 60.0)
    shoulder_l, shoulder_r = (106.0, 80.0), (150.0, 80.0)
    hip = (128.0, 150.0)
    # A-pose: arms held clear of the torso so limb landmarks are separable.
    elbow_l = (82.0 - swing * 0.4, 116.0 + lift)
    elbow_r = (174.0 + swing * 0.4, 116.0 - lift)
    hand_l = (62.0 - swing, 152.0 + lift)
    hand_r = (194.0 + swing, 152.0 - lift)
    knee_l, knee_r = (114.0, 196.0), (142.0, 196.0)
    foot_l = (110.0 - swing * 0.3, 240.0)
    foot_r = (146.0 + swing * 0.3, 240.0)

    pixels = bytearray()
    for y in range(HEIGHT):
        py = y + 0.5
        for x in range(WIDTH):
            px = x + 0.5
            if _disc(px, py, head_cx - 6.5, head_cy - 2.0, 2.6) or _disc(
                px, py, head_cx + 6.5, head_cy - 2.0, 2.6
            ):
                colour = EYE
            elif (
                _disc(px, py, head_cx, head_cy - 7.0, head_r * 0.92)
                and py < head_cy - 3.0
            ):
                colour = HAIR
            elif _disc(px, py, head_cx, head_cy, head_r) or _capsule(
                px, py, *neck, 128.0, 74.0, 7.0
            ):
                colour = SKIN
            elif (
                _capsule(px, py, 128.0, 82.0, *hip, 19.0)
                or _capsule(px, py, *shoulder_l, *shoulder_r, 12.0)
                or _capsule(px, py, *shoulder_l, *elbow_l, 8.5)
                or _capsule(px, py, *shoulder_r, *elbow_r, 8.5)
            ):
                colour = SHIRT
            elif _capsule(px, py, *elbow_l, *hand_l, 7.0) or _capsule(
                px, py, *elbow_r, *hand_r, 7.0
            ):
                colour = SKIN
            elif (
                _capsule(px, py, *hip, *knee_l, 11.5)
                or _capsule(px, py, *hip, *knee_r, 11.5)
                or _capsule(px, py, *knee_l, *foot_l, 9.5)
                or _capsule(px, py, *knee_r, *foot_r, 9.5)
            ):
                colour = TROUSERS
            else:
                colour = BACKGROUND
            pixels.extend(colour)
    return bytes(pixels)


def main() -> None:
    root = Path(__file__).resolve().parents[1]
    fixture = root / "fixtures" / "synthetic" / "valid" / "fixture_detection"
    decoded = fixture / "decoded"
    decoded.mkdir(parents=True, exist_ok=True)

    frames = []
    for index, timestamp_ns in enumerate(TIMESTAMPS_NS):
        plane = render(index)
        assert len(plane) == WIDTH * HEIGHT * 3
        name = f"frame_{index:06d}.rgb24"
        (decoded / name).write_bytes(plane)
        frames.append(
            {
                "frame_index": index,
                "timestamp_ns": timestamp_ns,
                "path": f"decoded/{name}",
                "byte_length": len(plane),
                "sha256": hashlib.sha256(plane).hexdigest(),
            }
        )

    metadata = {
        "fixture_metadata_schema_version": "1.0.0",
        "fixture_id": "b2c3d4e5-6f70-4a81-9b2c-3d4e5f607182",
        "synthetic": True,
        "preprocessing_contract_version": "1.0.0",
        "frame_width": WIDTH,
        "frame_height": HEIGHT,
        "pixel_format": "rgb24",
        "color_space": "srgb",
        "color_range": "full",
        "channel_order": "rgb",
        "orientation": "upright",
        "generator": "tools/generate_detection_fixture.py",
        "generator_license": "same as repository",
        "figure_provenance": "rendered from geometric primitives; not a photograph of a person",
        "detection_evidence": {
            "status": "pending",
            "missing_precondition": "not yet executed against the pinned model on a device",
            "admissible_for_topology_checks": False,
        },
        "frames": frames,
    }
    (fixture / "fixture_metadata.json").write_text(
        json.dumps(metadata, indent=2, sort_keys=False) + "\n", encoding="utf-8"
    )
    total = sum(f["byte_length"] for f in frames)
    print(f"frames={len(frames)} bytes={total} ({total / 1_048_576:.2f} MiB)")
    for f in frames:
        print(
            f"  {f['path']} ts={f['timestamp_ns']} floor_ms={f['timestamp_ns'] // 1_000_000} sha={f['sha256'][:16]}"
        )


if __name__ == "__main__":
    main()
