#!/usr/bin/env python3
"""Check one short synchronized-video instrumentation run on the authorized S25.

Binary payloads are pulled with ``adb exec-out``, never ``adb shell``: ``adb shell`` performs
LF/CRLF translation on some hosts, which silently corrupts an MP4 while leaving a plausible file
behind. Every pulled payload is written to a temporary host file, verified against the byte count
and SHA-256 the manifest declares, and only then renamed into place.

This command prints a typed, sanitized record for an explicit recording step. It never writes an
evidence ledger, and its exit status -- not its output -- is the result.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import subprocess
import sys
import tempfile
from pathlib import Path

_SUITE_MARKER = (
    "class=com.calisthenics.diagnostic.composition.SynchronizedVideoDeviceGateTest"
)
_RECORD_PATTERN = re.compile(
    r"^INSTRUMENTATION_STATUS: SynchronizedVideoEvidence=(?P<json>\{.*\})$", re.MULTILINE
)
_EXPECTED_RECORD_KIND = "synchronized_video_device_gate_observation"

_S25_MODEL_PREFIX = "SM-S938"
_S25_MARKER = "S25 Ultra"
_AUTHORIZED_MANUFACTURER = "samsung"

_SHA256 = re.compile(r"^[0-9a-f]{64}$")
_ABSOLUTE_PATH = re.compile(r"(?:file://)?(?:/|[A-Za-z]:[\\/])\S+")

EXIT_OK = 0
EXIT_BLOCKED = 1
EXIT_USAGE = 2


class SynchronizedVideoCheckError(Exception):
    """A malformed invocation or transcript. Distinct from a blocking observation."""


def is_authorized_target(environment: dict) -> bool:
    """The single authorization rule for the S25 target, mirroring the shared definition."""
    return (
        environment.get("device_class") == "physical_device"
        and environment.get("manufacturer", "").lower() == _AUTHORIZED_MANUFACTURER
        and (
            environment.get("model", "").upper().startswith(_S25_MODEL_PREFIX)
            or _S25_MARKER in environment.get("marketing_name", "")
        )
    )


def require_exactly_one_authorized_device() -> str:
    """Return the serial of the one attached physical device, or refuse."""
    result = subprocess.run(
        ["adb", "devices", "-l"], capture_output=True, text=True, check=False
    )
    if result.returncode != 0:
        raise SynchronizedVideoCheckError("adb devices failed")
    serials = []
    for line in result.stdout.splitlines()[1:]:
        parts = line.split()
        if len(parts) >= 2 and parts[1] == "device":
            serial = parts[0]
            if serial.startswith("emulator-"):
                raise SynchronizedVideoCheckError(
                    "an emulator is attached; emulator results are never physical evidence"
                )
            serials.append(serial)
    if len(serials) != 1:
        raise SynchronizedVideoCheckError(
            f"require exactly one authorized attached device, found {len(serials)}"
        )
    return serials[0]


def load_probe(probe_path: Path) -> dict:
    """Load and validate the admitted Task 3 container-timestamp probe."""
    if not probe_path.is_file():
        raise SynchronizedVideoCheckError(f"probe record not found: {probe_path.name}")
    try:
        probe = json.loads(probe_path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as error:
        raise SynchronizedVideoCheckError(f"probe record is not valid JSON: {error}") from error
    if probe.get("record_kind") != "container_timestamp_probe_device_gate_observation":
        raise SynchronizedVideoCheckError("probe record has the wrong record_kind")
    if probe.get("blocking_result") is not False:
        raise SynchronizedVideoCheckError("probe record is itself blocking; it cannot be inherited")
    provenance = probe.get("provenance") or {}
    if not provenance.get("codec") or not provenance.get("container"):
        raise SynchronizedVideoCheckError("probe record does not name its codec and container")
    return probe


def probe_digest(probe_path: Path) -> str:
    return hashlib.sha256(probe_path.read_bytes()).hexdigest()


_RUN_AS_ERROR = re.compile(rb"^(?:run-as|cat):\s")


def pull_binary(serial: str, package: str, remote: str, destination: Path) -> None:
    """Pull one payload byte-exactly, verifying before it takes its final name.

    `run-as` writes its own diagnostics to stdout and can still exit zero, so a failed pull
    otherwise arrives as a small, plausible file that is then hashed and compared as if it were
    the payload. Observed: "run-as: unknown package: ..." landed as a 53-byte MP4. Reject anything
    that starts like a tool error rather than a container.
    """
    result = subprocess.run(
        ["adb", "-s", serial, "exec-out", "run-as", package, "cat", remote],
        capture_output=True,
        check=False,
    )
    if result.returncode != 0 or not result.stdout:
        detail = result.stderr.decode(errors="replace").strip()
        raise SynchronizedVideoCheckError(f"failed to pull {remote}: {detail}")
    if _RUN_AS_ERROR.match(result.stdout):
        detail = result.stdout[:200].decode(errors="replace").strip()
        raise SynchronizedVideoCheckError(f"pull of {remote} returned a tool error: {detail}")
    temporary = destination.with_suffix(destination.suffix + ".partial")
    with open(temporary, "wb") as handle:
        handle.write(result.stdout)
        handle.flush()
        os.fsync(handle.fileno())
    os.replace(temporary, destination)


def verify_payload(path: Path, expected_sha256: str, expected_bytes: int) -> list[str]:
    """Compare a pulled payload against what the manifest declares."""
    reasons: list[str] = []
    actual_bytes = path.stat().st_size
    if actual_bytes != expected_bytes:
        reasons.append("video_byte_length_mismatch")
    actual_sha = hashlib.sha256(path.read_bytes()).hexdigest()
    if not _SHA256.match(expected_sha256 or "") or actual_sha != expected_sha256:
        reasons.append("video_digest_mismatch")
    return reasons


def check_record(record: dict, probe: dict, probe_path: Path) -> list[str]:
    """Blocking reasons for this observation. Empty means admissible."""
    reasons: list[str] = list(record.get("blocking_reasons") or [])

    if record.get("record_kind") != _EXPECTED_RECORD_KIND:
        reasons.append("unexpected_record_kind")
    if record.get("sanitized") is not True:
        reasons.append("record_not_sanitized")
    if record.get("non_participant_observation") is not True:
        reasons.append("record_not_marked_non_participant")
    if record.get("contains_landmark_coordinates") is not False:
        reasons.append("record_contains_landmark_coordinates")

    environment = record.get("environment") or {}
    if not is_authorized_target(environment):
        reasons.append("unauthorized_device_target")

    # Provenance: the probe measured one constant transform on one codec in one container.
    #
    # The device reports only what it observed. The host owns the comparison and the digest
    # binding, because the device cannot know the digest of a file that lives in the repository.
    observed = record.get("provenance") or {}
    probe_provenance = probe.get("provenance") or {}
    if observed.get("codec") != probe_provenance.get("codec"):
        reasons.append("probe_codec_mismatch")
    if observed.get("container") != probe_provenance.get("container"):
        reasons.append("probe_container_mismatch")

    return sorted(set(reasons))


def assert_sanitized(record: dict) -> None:
    """No absolute host or device path may appear in the fields that could carry one.

    The scan is deliberately scoped rather than applied to the whole record. `Build.FINGERPRINT`
    legitimately contains slash-led substrings (`samsung/pa3qxins/pa3q:16/...`) and is device
    identity, not a path; scanning the whole record rejects every honest observation.
    """
    suspect: list[str] = []
    artifacts = record.get("artifacts") or {}
    suspect.extend(str(value) for key, value in artifacts.items() if key.endswith("path_ref"))
    suspect.extend(str(reason) for reason in (record.get("blocking_reasons") or []))
    for value in suspect:
        if _ABSOLUTE_PATH.search(value):
            raise SynchronizedVideoCheckError(
                "record contains an absolute path and is not sanitized"
            )


def extract_record(transcript: str) -> dict:
    if _SUITE_MARKER not in transcript:
        raise SynchronizedVideoCheckError(
            "transcript does not show SynchronizedVideoDeviceGateTest"
        )
    matches = _RECORD_PATTERN.findall(transcript)
    if len(matches) != 1:
        raise SynchronizedVideoCheckError(
            f"expected exactly one emitted record, found {len(matches)}"
        )
    return json.loads(matches[0])


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("transcript", type=Path, help="instrumentation transcript")
    parser.add_argument("--probe", type=Path, required=True, help="admitted Task 3 probe record")
    parser.add_argument("--package", default="com.calisthenics.diagnostic.android")
    parser.add_argument("--pull", action="store_true", help="also pull and verify the payloads")
    try:
        args = parser.parse_args(argv)
    except SystemExit:
        return EXIT_USAGE

    try:
        transcript = args.transcript.read_text(encoding="utf-8", errors="replace")
        record = extract_record(transcript)
        assert_sanitized(record)
        probe = load_probe(args.probe)
        reasons = check_record(record, probe, args.probe)

        if args.pull:
            serial = require_exactly_one_authorized_device()
            artifacts = record.get("artifacts") or {}
            # The gate writes its container app-private, outside every registered export root:
            # it observes the encoder rather than publishing a study artifact.
            path_ref = artifacts.get("video_path_ref")
            if not path_ref or "/" in path_ref:
                raise SynchronizedVideoCheckError("record has no usable video_path_ref")
            remote = f"/data/user/0/{args.package}/files/{path_ref}"
            with tempfile.TemporaryDirectory() as staging:
                video = Path(staging) / "video.mp4"
                pull_binary(serial, args.package, remote, video)
                reasons.extend(
                    verify_payload(
                        video,
                        artifacts.get("video_sha256", ""),
                        int(artifacts.get("video_byte_length", -1)),
                    )
                )
            reasons = sorted(set(reasons))
    except SynchronizedVideoCheckError as error:
        print(f"error: {error}", file=sys.stderr)
        return EXIT_USAGE

    # The probe path and digest are bound into the gate result here, by the host, so the recorded
    # evidence names exactly which admitted probe this run inherited its transform class from.
    print(
        json.dumps(
            {
                "record": record,
                "inherited_probe": {
                    "path_ref": args.probe.as_posix(),
                    "sha256": probe_digest(args.probe),
                    "codec": (probe.get("provenance") or {}).get("codec"),
                    "container": (probe.get("provenance") or {}).get("container"),
                },
                "blocking_reasons": reasons,
            },
            indent=2,
            sort_keys=True,
        )
    )
    if reasons:
        print(
            "blocked: this observation is recorded as blocking and is not admissible",
            file=sys.stderr,
        )
        return EXIT_BLOCKED
    return EXIT_OK


if __name__ == "__main__":
    raise SystemExit(main())
