"""Optional media-environment regression; run with python -m unittest discover.

Exercises the real decoder and MediaPipe Image boundary. Only expensive pose
inference is replaced. The ordinary reference environment may omit media extras.
"""

from __future__ import annotations

import hashlib
import importlib.util
import json
import sys
import tempfile
import unittest
from fractions import Fraction
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

MEDIA_AVAILABLE = all(importlib.util.find_spec(name) for name in ("av", "mediapipe", "numpy"))


@unittest.skipUnless(MEDIA_AVAILABLE, "run in the isolated offline media environment")
class ExtractorMediaTest(unittest.TestCase):
    def test_padded_decoder_rows_preserve_pixels_and_pts_at_mediapipe_boundary(self) -> None:
        import av
        import mediapipe as mp
        import numpy as np

        tools_path = str(Path(__file__).parents[2] / "tools")
        with patch.object(sys, "path", [tools_path, *sys.path]):
            import extract_offline_pose as extractor

        for width in (1080, 1920):
            with self.subTest(width=width), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                video = root / "synthetic.mkv"
                expected = np.zeros((8, width, 3), dtype=np.uint8)
                expected[:, : width // 2] = [17, 83, 219]
                expected[:, width // 2 :] = [231, 11, 45]
                with av.open(str(video), "w") as output:
                    stream = output.add_stream("ffv1", rate=30)
                    stream.width, stream.height, stream.pix_fmt = width, 8, "bgr0"
                    for pts in (30, 32):
                        frame = av.VideoFrame.from_ndarray(expected, format="rgb24")
                        frame.pts, frame.time_base = pts, Fraction(1, 30)
                        output.mux(stream.encode(frame))
                    output.mux(stream.encode())
                with av.open(str(video)) as decoded:
                    decoded_frames = list(decoded.decode(video=0))
                if width == 1080:
                    self.assertFalse(
                        decoded_frames[0].to_ndarray(format="rgb24").flags.c_contiguous
                    )
                expected_ms = [int(f.pts * f.time_base * 1000) for f in decoded_frames]
                model = root / "model.task"
                model.write_bytes(b"not used by mocked inference")
                provenance, source = root / "provenance.json", root / "source.json"
                provenance.write_text(
                    json.dumps({"sha256": hashlib.sha256(model.read_bytes()).hexdigest()})
                )
                source.write_text(
                    json.dumps(
                        {
                            "id": "synthetic",
                            "video_sha256": hashlib.sha256(video.read_bytes()).hexdigest(),
                            "source_url": "synthetic:test",
                            "license_url": "synthetic:test",
                            "group": "synthetic",
                            "split": "development",
                        }
                    )
                )
                observed_ms = []

                def observe(
                    image: mp.Image,
                    timestamp: int,
                    expected_pixels=expected,
                    timestamps=observed_ms,
                ) -> SimpleNamespace:
                    np.testing.assert_array_equal(image.numpy_view(), expected_pixels)
                    timestamps.append(timestamp)
                    return SimpleNamespace(pose_landmarks=[])

                args = [
                    "extract",
                    "--video",
                    str(video),
                    "--model",
                    str(model),
                    "--model-provenance",
                    str(provenance),
                    "--source-record",
                    str(source),
                    "--output",
                    str(root / "cache"),
                ]
                with (
                    patch.object(sys, "argv", args),
                    patch.object(mp.tasks.vision.PoseLandmarker, "create_from_options") as factory,
                ):
                    factory.return_value.__enter__.return_value.detect_for_video.side_effect = (
                        observe
                    )
                    try:
                        extractor.main()
                    except TypeError as error:
                        self.fail(f"decoder RGB rows were not admitted by MediaPipe: {error}")
                self.assertEqual(observed_ms, expected_ms)
                self.assertEqual(len(observed_ms), 2)
                self.assertEqual(
                    json.loads((root / "cache/metadata.json").read_text())["frame_count"], 2
                )
