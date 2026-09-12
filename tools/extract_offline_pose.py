"""Extract an explicitly sourced local clip using desktop MediaPipe VIDEO.

Install optional dependencies in a separate venv: mediapipe==0.10.21 av==18.1.0.
Does not record camera input or upload anything. PTS is preserved, never synthesized.
"""

from __future__ import annotations

import argparse
import platform
from collections.abc import Iterator
from pathlib import Path
from typing import Any

from offline_movement_feedback import load_json, pts_ns, sha256, write_pose_cache


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--video", type=Path, required=True)
    parser.add_argument("--model", type=Path, required=True)
    parser.add_argument("--model-provenance", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--source-record", type=Path, required=True)
    args = parser.parse_args()
    provenance = load_json(args.model_provenance)
    if sha256(args.model) != provenance["sha256"]:
        raise ValueError("model hash does not match pinned provenance")
    source = load_json(args.source_record)
    if source.get("video_sha256") != sha256(args.video) or not all(
        source.get(key) for key in ("source_url", "license_url", "group", "split")
    ):
        raise ValueError(
            "source record requires verified file hash, source/license, group and split"
        )

    import av  # type: ignore[import-not-found]  # optional isolated media environment
    import mediapipe as mp  # type: ignore[import-not-found]

    options = mp.tasks.vision.PoseLandmarkerOptions(
        base_options=mp.tasks.BaseOptions(
            model_asset_path=str(args.model), delegate=mp.tasks.BaseOptions.Delegate.CPU
        ),
        running_mode=mp.tasks.vision.RunningMode.VIDEO,
        num_poses=1,
        min_pose_detection_confidence=0.5,
        min_pose_presence_confidence=0.5,
        min_tracking_confidence=0.5,
    )
    with av.open(str(args.video)) as video:
        if len(video.streams.video) != 1:
            raise ValueError("exactly one video stream is required")
        stream = video.streams.video[0]
        if int(stream.metadata.get("rotate", "0")) != 0:
            raise ValueError(
                "rotation metadata unsupported; create an explicitly upright derivative"
            )
        if stream.sample_aspect_ratio not in (None, 1):
            raise ValueError("non-square sample aspect ratio unsupported")
        with mp.tasks.vision.PoseLandmarker.create_from_options(options) as detector:

            def frames() -> Iterator[dict[str, Any]]:
                previous_ms = -1
                for frame in video.decode(stream):
                    if getattr(frame, "rotation", 0) != 0:
                        raise ValueError("display matrix rotation unsupported")
                    timestamp = pts_ns(frame.pts, frame.time_base)
                    if timestamp // 1_000_000 <= previous_ms:
                        raise ValueError(
                            "non-increasing MediaPipe millisecond timestamp"
                        )
                    previous_ms = timestamp // 1_000_000
                    pixels = frame.to_ndarray(format="rgb24")
                    # Decoder row padding is not accepted by MediaPipe's Image binding.
                    if not pixels.flags.c_contiguous:
                        pixels = pixels.copy(order="C")
                    result = detector.detect_for_video(
                        mp.Image(image_format=mp.ImageFormat.SRGB, data=pixels),
                        previous_ms,
                    )
                    landmarks = (
                        []
                        if not result.pose_landmarks
                        else [
                            {
                                "index": i,
                                "x": p.x,
                                "y": p.y,
                                "z": p.z,
                                "visibility": p.visibility,
                                "presence": p.presence,
                            }
                            for i, p in enumerate(result.pose_landmarks[0])
                        ]
                    )
                    yield {
                        "timestamp_ns": timestamp,
                        "width": frame.width,
                        "height": frame.height,
                        "landmarks": landmarks,
                    }

            write_pose_cache(
                args.output,
                frames(),
                {
                    "version": 1,
                    "extractor_sha256": sha256(Path(__file__)),
                    "video_sha256": sha256(args.video),
                    "model_sha256": sha256(args.model),
                    "model_provenance_sha256": sha256(args.model_provenance),
                    "source_record_sha256": sha256(args.source_record),
                    "source": source,
                    "runtime": "desktop_reference_not_android",
                    "mediapipe_version": mp.__version__,
                    "decoder_version": av.__version__,
                    "platform": platform.platform(),
                    "mode": "VIDEO",
                    "delegate": "CPU",
                    "num_poses": 1,
                    "detection_confidence": 0.5,
                    "presence_confidence": 0.5,
                    "tracking_confidence": 0.5,
                    "preprocessing": "PyAV RGB24 -> MediaPipe internal preprocessing; no manual crop/mirror",
                    "time_base": str(stream.time_base),
                    "timestamps": "original source PTS; ms floor for inference only",
                },
            )
    print(f"Complete cache: {args.output}")


if __name__ == "__main__":
    main()
