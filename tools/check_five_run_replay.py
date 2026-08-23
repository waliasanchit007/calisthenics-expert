#!/usr/bin/env python3
"""Verify a five-run replay device-gate observation and print the final sanitized record.

`FiveRunReplayDeviceGateTest` runs the real replay-through-export path five complete times
on-device and emits one `FiveRunReplayEvidence=<json>` `am instrument` status line. Its
`validated_by_reference_cli` fields are always `false` on-device: the Python reference validator
cannot run there. This script pulls each run's export with `adb shell run-as`, validates all five
against `observation_contracts validate-session`, and — only if every one of the five passes —
prints the record again with `validated_by_reference_cli: true` on every run and
`satisfies_s25_device_gate` recomputed from that.

The recomputation mirrors the Kotlin renderer exactly, including its detection conjunct: frame
indices, backend timestamps, and canonical input digests are all input-side properties that a
pipeline detecting nothing reproduces perfectly, so five consistent runs of an empty pose pipeline
must never satisfy the gate on either side of the seam.

This script never writes the committed ledger. Per the project's established
`docs/evidence/device-probes/s25-ultra-process-kill-ledger.json` convention, a human copies the
printed record into `docs/evidence/replay-five-run/...json` and flips the ledger's `status` field
to `"recorded"` by hand.

Usage: check_five_run_replay.py <serial> <package> <transcript-file>   ("-" reads stdin)
"""

from __future__ import annotations

import hashlib
import json
import re
import subprocess
import sys
import tempfile
from pathlib import Path

_RECORD_PATTERN = re.compile(
    r"INSTRUMENTATION_STATUS: FiveRunReplayEvidence=(?P<json>\{.*\})"
)
_SUITE_MARKER = (
    "class=com.calisthenics.diagnostic.composition.FiveRunReplayDeviceGateTest"
)
_S25_MARKER = "S25 Ultra"
# Model number is the authoritative identifier. Retail S25 Ultra firmware does not necessarily
# populate ro.config.marketing_name, so a marketing-name-only rule rejects the very hardware this
# gate exists to authorize. Mirrors isAuthorizedFiveRunGateTarget in FiveRunReplayEvidence.kt.
_S25_MODEL_PREFIX = "SM-S938"
_AUTHORIZED_MANUFACTURER = "samsung"
_HARNESS_GIT_SHA_PATTERN = re.compile(r"^[0-9a-f]{40}$")
_LEDGER_STATUSES = {"pending", "recorded"}


def is_authorized_five_run_gate_target(environment: dict) -> bool:
    """Whether the observing device is the one authorized physical five-run gate target."""
    if environment["device_class"] != "physical_device":
        return False
    if environment.get("manufacturer", "").lower() != _AUTHORIZED_MANUFACTURER:
        return False
    model = environment.get("model", "")
    return model.upper().startswith(
        _S25_MODEL_PREFIX
    ) or _S25_MARKER in environment.get("marketing_name", "")


_EXPECTED_RUN_COUNT = 5
_REQUIRED_RUN_FIELDS = (
    "submitted_indices",
    "backend_timestamps_ms",
    "canonical_input_sha256",
    "pose_present_per_frame",
    "normalized_landmark_count_per_frame",
    "world_landmark_count_per_frame",
    "pose_detected_records",
)


class FiveRunReplayCheckError(Exception):
    pass


def _require_harness_git_sha(record: dict) -> None:
    harness_git_sha = record.get("harness_git_sha")
    if (
        not isinstance(harness_git_sha, str)
        or _HARNESS_GIT_SHA_PATTERN.fullmatch(harness_git_sha) is None
    ):
        raise FiveRunReplayCheckError(
            "FiveRunReplayEvidence harness_git_sha must be a lowercase 40-hex commit SHA"
        )


def require_exact_suite(transcript: str) -> None:
    if _SUITE_MARKER not in transcript:
        raise FiveRunReplayCheckError(
            "transcript does not show FiveRunReplayDeviceGateTest running; "
            "refusing to trust an unrelated or empty run"
        )
    if not re.search(r"^OK \(1 test\)$", transcript, re.MULTILINE):
        raise FiveRunReplayCheckError(
            'transcript does not report exactly one passing test ("OK (1 test)"); '
            "a failing or vacuous run is not evidence"
        )


def extract_record(transcript: str) -> dict:
    matches = _RECORD_PATTERN.findall(transcript)
    if len(matches) != 1:
        raise FiveRunReplayCheckError(
            f"expected exactly one FiveRunReplayEvidence record, found {len(matches)}"
        )
    record = json.loads(matches[0])
    _require_harness_git_sha(record)
    runs = record.get("runs")
    if not isinstance(runs, list) or len(runs) != _EXPECTED_RUN_COUNT:
        raise FiveRunReplayCheckError(
            f"expected exactly {_EXPECTED_RUN_COUNT} runs, found {runs!r}"
        )
    for run in runs:
        missing = [field for field in _REQUIRED_RUN_FIELDS if field not in run]
        if missing:
            raise FiveRunReplayCheckError(
                f"run {run.get('run_index')!r} is missing {missing}; a record that cannot show "
                "whether any pose was detected is not five-run gate evidence"
            )
    return record


def verify_recorded_ledger(root: Path, ledger_relative: str) -> dict:
    ledger = json.loads((root / ledger_relative).read_text(encoding="utf-8"))
    status = ledger.get("status")
    if status not in _LEDGER_STATUSES:
        raise FiveRunReplayCheckError(
            "replay ledger status must be pending or recorded"
        )
    if status == "pending":
        if not ledger.get("missing_precondition"):
            raise FiveRunReplayCheckError(
                "pending replay ledger must name its missing physical precondition"
            )
        return ledger

    evidence_file = ledger.get("evidence_file")
    if not isinstance(evidence_file, str) or not evidence_file:
        raise FiveRunReplayCheckError(
            "recorded replay ledger must reference evidence_file"
        )
    evidence_path = root / evidence_file
    if not evidence_path.is_file():
        raise FiveRunReplayCheckError(
            f"recorded replay evidence file is missing: {evidence_file}"
        )
    payload = evidence_path.read_bytes()
    actual_sha = hashlib.sha256(payload).hexdigest()
    if ledger.get("evidence_sha256") != actual_sha:
        raise FiveRunReplayCheckError(
            "recorded replay evidence_sha256 does not match evidence file sha256"
        )
    record = json.loads(payload)
    _require_harness_git_sha(record)
    recomputed = satisfies_gate(record)
    if record.get("satisfies_s25_device_gate") is not recomputed:
        raise FiveRunReplayCheckError(
            "recorded replay evidence verdict disagrees with recomputed observations"
        )
    return ledger


def _pull(
    serial: str, package: str, export_path: str, filename: str, destination: Path
) -> None:
    result = subprocess.run(
        [
            "adb",
            "-s",
            serial,
            "shell",
            "run-as",
            package,
            "cat",
            f"{export_path}/{filename}",
        ],
        capture_output=True,
        check=False,
    )
    if result.returncode != 0 or not result.stdout:
        raise FiveRunReplayCheckError(
            f"failed to pull {filename} from the device via run-as: {result.stderr.decode(errors='replace')}"
        )
    destination.write_bytes(result.stdout)


def _validate_one_run(
    serial: str, package: str, session_id: str, exports_root: str
) -> None:
    export_path = f"{exports_root}/{session_id}"
    with tempfile.TemporaryDirectory() as tmp:
        manifest_path = Path(tmp) / "manifest.json"
        frames_path = Path(tmp) / "frames.ndjson"
        _pull(serial, package, export_path, "manifest.json", manifest_path)
        _pull(serial, package, export_path, "frames.ndjson", frames_path)

        validation = subprocess.run(
            [
                sys.executable,
                "-m",
                "observation_contracts",
                "validate-session",
                str(manifest_path),
                str(frames_path),
            ],
            capture_output=True,
            check=False,
        )
        if validation.returncode != 0:
            raise FiveRunReplayCheckError(
                f"run {session_id} failed Python reference CLI validation:\n"
                f"{validation.stdout.decode(errors='replace')}{validation.stderr.decode(errors='replace')}"
            )


def detection_is_non_vacuous(run: dict) -> bool:
    """Whether one run's own records show a pose was detected, mirroring the Kotlin renderer.

    Each clause closes a way a run that detected nothing could read as one: one record per
    submitted frame, a nonzero detected-record count, that count supported by the per-frame pattern
    rather than merely asserted, and landmarks behind every detection. A record missing any of these
    fields fails closed rather than raising.
    """
    present = run.get("pose_present_per_frame")
    normalized = run.get("normalized_landmark_count_per_frame")
    world = run.get("world_landmark_count_per_frame")
    detected = run.get("pose_detected_records")
    submitted = run.get("submitted_indices")
    if not all(
        isinstance(value, list) for value in (present, normalized, world, submitted)
    ):
        return False
    if isinstance(detected, bool) or not isinstance(detected, int):
        return False
    if not len(present) == len(normalized) == len(world) == len(submitted):
        return False
    if detected <= 0 or sum(1 for value in present if value) != detected:
        return False
    return all(
        not value or (normalized[position] > 0 and world[position] > 0)
        for position, value in enumerate(present)
    )


def _pose_observation(run: dict) -> tuple:
    return (
        run.get("pose_present_per_frame"),
        run.get("normalized_landmark_count_per_frame"),
        run.get("world_landmark_count_per_frame"),
        run.get("pose_detected_records"),
    )


def satisfies_gate(record: dict) -> bool:
    runs = record["runs"]
    environment = record["environment"]
    all_consistent = all(
        run["submitted_indices"] == runs[0]["submitted_indices"]
        and run["backend_timestamps_ms"] == runs[0]["backend_timestamps_ms"]
        and run["canonical_input_sha256"] == runs[0]["canonical_input_sha256"]
        and _pose_observation(run) == _pose_observation(runs[0])
        for run in runs
    )
    all_detected = all(detection_is_non_vacuous(run) for run in runs)
    all_published_and_validated = all(
        run["published"] and run["validated_by_reference_cli"] for run in runs
    )
    is_authorized_s25 = is_authorized_five_run_gate_target(environment)
    return (
        record["runs_completed"] == _EXPECTED_RUN_COUNT
        and all_consistent
        and all_detected
        and all_published_and_validated
        and is_authorized_s25
    )


def main(argv: list[str]) -> int:
    if len(argv) != 4:
        print(
            f"usage: {Path(argv[0]).name} <serial> <package> <transcript-file>",
            file=sys.stderr,
        )
        return 2

    serial, package, transcript_arg = argv[1], argv[2], argv[3]
    transcript = (
        sys.stdin.read()
        if transcript_arg == "-"
        else Path(transcript_arg).read_text(encoding="utf-8")
    )

    try:
        require_exact_suite(transcript)
        record = extract_record(transcript)

        # Constructed here rather than echoed back from the device. `adb shell` joins its
        # arguments into one remote command string, so `sh -c "echo X"` arrives as
        # `sh -c echo X`: the script becomes `echo` and X becomes `$0`, printing an empty
        # line and failing this resolution on every physical device. The path is a constant,
        # so there is nothing to ask the device for.
        exports_root = f"/data/user/0/{package}/files/diagnostic-harness/exports"
        result = subprocess.run(
            ["adb", "-s", serial, "shell", "run-as", package, "ls", exports_root],
            capture_output=True,
            check=False,
        )
        if result.returncode != 0 or not result.stdout.decode().strip():
            raise FiveRunReplayCheckError(
                f"failed to list the on-device exports root via run-as: {exports_root}"
            )

        for run in record["runs"]:
            _validate_one_run(serial, package, run["session_id"], exports_root)
            run["validated_by_reference_cli"] = True

        record["satisfies_s25_device_gate"] = satisfies_gate(record)
    except FiveRunReplayCheckError as error:
        print(f"error: {error}", file=sys.stderr)
        return 1

    print(
        "five-run replay verified: all five exports validated by the Python reference CLI"
    )
    print(
        f"  satisfies_s25_device_gate={str(record['satisfies_s25_device_gate']).lower()}"
    )
    print(
        "human step required: copy the record below into docs/evidence/replay-five-run/, "
        'then set the ledger\'s status to "recorded" pointing at it'
    )
    print(json.dumps(record, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
