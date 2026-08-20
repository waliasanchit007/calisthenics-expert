#!/usr/bin/env python3
"""Verify that the real replay UI path actually published a session and that the export
pulled off the device validates against the Python reference CLI.

`RealReplayCompositionExportTest` runs the real MediaPipe VIDEO replay adapter, the real
`SessionExporter`, and the real `AndroidStoragePort` on-device, then emits one
`RealReplayExport=session_id=... export_path=...` `am instrument` status line so this script can
locate the export. The Python reference validator cannot run on-device (design spec Task 5 brief:
"every export validates through the Python reference CLI"), so this script pulls the two exported
files with `adb shell run-as` (the app is a debug build) and invokes the same
`observation_contracts validate-session` CLI `make verify-kotlin-export` already trusts.

Usage: check_real_replay_export.py <serial> <package> <transcript-file>   ("-" reads stdin)
"""

from __future__ import annotations

import re
import subprocess
import sys
import tempfile
from pathlib import Path

_RECORD_PATTERN = re.compile(
    r"INSTRUMENTATION_STATUS: RealReplayExport=session_id=(?P<session_id>\S+) export_path=(?P<export_path>\S+)"
)
_SUITE_MARKER = "class=com.calisthenics.diagnostic.composition.RealReplayCompositionExportTest"


class RealReplayExportCheckError(Exception):
    pass


def require_exact_suite(transcript: str) -> None:
    if _SUITE_MARKER not in transcript:
        raise RealReplayExportCheckError(
            "transcript does not show RealReplayCompositionExportTest running; "
            "refusing to trust an unrelated or empty run"
        )
    if not re.search(r"^OK \(1 test\)$", transcript, re.MULTILINE):
        raise RealReplayExportCheckError(
            "transcript does not report exactly one passing test (\"OK (1 test)\"); "
            "a failing or vacuous run is not evidence"
        )


def extract_record(transcript: str) -> tuple[str, str]:
    matches = _RECORD_PATTERN.findall(transcript)
    if len(matches) != 1:
        raise RealReplayExportCheckError(
            f"expected exactly one RealReplayExport record, found {len(matches)}"
        )
    session_id, export_path = matches[0]
    return session_id, export_path


def _pull(serial: str, package: str, export_path: str, filename: str, destination: Path) -> None:
    result = subprocess.run(
        ["adb", "-s", serial, "shell", "run-as", package, "cat", f"{export_path}/{filename}"],
        capture_output=True,
        check=False,
    )
    if result.returncode != 0 or not result.stdout:
        raise RealReplayExportCheckError(
            f"failed to pull {filename} from the device via run-as: {result.stderr.decode(errors='replace')}"
        )
    destination.write_bytes(result.stdout)


def main(argv: list[str]) -> int:
    if len(argv) != 4:
        print(f"usage: {Path(argv[0]).name} <serial> <package> <transcript-file>", file=sys.stderr)
        return 2

    serial, package, transcript_arg = argv[1], argv[2], argv[3]
    transcript = sys.stdin.read() if transcript_arg == "-" else Path(transcript_arg).read_text(encoding="utf-8")

    try:
        require_exact_suite(transcript)
        session_id, export_path = extract_record(transcript)

        with tempfile.TemporaryDirectory() as tmp:
            manifest_path = Path(tmp) / "manifest.json"
            frames_path = Path(tmp) / "frames.ndjson"
            _pull(serial, package, export_path, "manifest.json", manifest_path)
            _pull(serial, package, export_path, "frames.ndjson", frames_path)

            validation = subprocess.run(
                [sys.executable, "-m", "observation_contracts", "validate-session", str(manifest_path), str(frames_path)],
                capture_output=True,
                check=False,
            )
            if validation.returncode != 0:
                raise RealReplayExportCheckError(
                    "pulled export failed Python reference CLI validation:\n"
                    f"{validation.stdout.decode(errors='replace')}{validation.stderr.decode(errors='replace')}"
                )
    except RealReplayExportCheckError as error:
        print(f"error: {error}", file=sys.stderr)
        return 1

    print(f"real replay export verified: session={session_id} validated by the Python reference CLI")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
