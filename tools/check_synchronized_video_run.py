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
LEDGER_PATH = "docs/evidence/synchronized-video/s25-ultra-synchronized-video-ledger.json"

_S25_MODEL_PREFIX = "SM-S938"
_S25_MARKER = "S25 Ultra"
_AUTHORIZED_MANUFACTURER = "samsung"

_SHA256 = re.compile(r"^[0-9a-f]{64}$")
_SESSION_ID = re.compile(
    r"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"
)
_ABSOLUTE_PATH = re.compile(r"^(?:file://)?(?:/|[A-Za-z]:[\\/])\S+")

EXIT_OK = 0
EXIT_BLOCKED = 1
EXIT_USAGE = 2


class SynchronizedVideoCheckError(Exception):
    """A malformed invocation or transcript. Distinct from a blocking observation."""


def remote_artifact_path(session_id: str, leaf_name: str) -> str:
    """Return one registry-derived committed-export path for ``run-as cat``.

    The app owns the absolute storage root. The host names only the fixed app-relative registry
    root, one validated session identifier, and one leaf artifact name.
    """
    if not _SESSION_ID.fullmatch(session_id):
        raise SynchronizedVideoCheckError("record has no valid committed session_id")
    if not leaf_name or Path(leaf_name).name != leaf_name or "/" in leaf_name or "\\" in leaf_name:
        raise SynchronizedVideoCheckError("committed artifact must be one leaf name")
    return f"files/diagnostic-harness/exports/{session_id}/{leaf_name}"


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


def _read_json(path: Path) -> dict:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise SynchronizedVideoCheckError(f"cannot read JSON from {path}: {error}") from error
    if not isinstance(value, dict):
        raise SynchronizedVideoCheckError(f"JSON root is not an object: {path}")
    return value


def _repository_path(root: Path, relative: object, field: str) -> Path:
    if not isinstance(relative, str) or not relative:
        raise SynchronizedVideoCheckError(f"recorded ledger must name {field}")
    path = Path(relative)
    if path.is_absolute():
        raise SynchronizedVideoCheckError(f"recorded {field} must be relative")
    root_resolved = root.resolve()
    candidate = (root / path).resolve()
    if candidate != root_resolved and root_resolved not in candidate.parents:
        raise SynchronizedVideoCheckError(f"recorded {field} escapes the repository root")
    return candidate


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


def verify_payload(
    path: Path,
    expected_sha256: str,
    expected_bytes: int,
    *,
    artifact_name: str = "video",
) -> list[str]:
    """Compare a pulled payload against what the manifest declares."""
    reasons: list[str] = []
    actual_bytes = path.stat().st_size
    if actual_bytes != expected_bytes:
        reasons.append(f"{artifact_name}_byte_length_mismatch")
    actual_sha = hashlib.sha256(path.read_bytes()).hexdigest()
    if not _SHA256.match(expected_sha256 or "") or actual_sha != expected_sha256:
        reasons.append(f"{artifact_name}_digest_mismatch")
    return reasons


_COMMITTED_ARTIFACTS = {
    "manifest": ("manifest.json", "manifest_path_ref"),
    "video": ("video.mp4", "video_path_ref"),
    "pose_stream": ("frames.ndjson", "pose_stream_path_ref"),
    "synchronization_evidence": (
        "synchronization-evidence.json",
        "synchronization_evidence_path_ref",
    ),
}


def _expected_record_path(session_id: str, leaf_name: str) -> str:
    if not _SESSION_ID.fullmatch(session_id):
        return ""
    return f"exports/{session_id}/{leaf_name}"


def validate_export(manifest_path: Path, frames_path: Path) -> bool:
    """Run the released validator against the exact pulled export."""
    result = subprocess.run(
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
    return result.returncode == 0


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

    session_id = record.get("session_id")
    artifacts = record.get("artifacts") or {}
    for leaf_name, path_key in _COMMITTED_ARTIFACTS.values():
        if artifacts.get(path_key) != _expected_record_path(session_id, leaf_name):
            reasons.append("committed_artifact_reference_mismatch")

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


def _required_artifact_measurement(artifacts: dict, key: str) -> tuple[str, int]:
    expected_sha = artifacts.get(f"{key}_sha256")
    expected_bytes = artifacts.get(f"{key}_byte_length")
    if not isinstance(expected_sha, str) or not _SHA256.fullmatch(expected_sha):
        raise SynchronizedVideoCheckError(f"record has no valid {key}_sha256")
    if not isinstance(expected_bytes, int) or isinstance(expected_bytes, bool) or expected_bytes < 0:
        raise SynchronizedVideoCheckError(f"record has no valid {key}_byte_length")
    return expected_sha, expected_bytes


def pull_and_verify_committed_export(
    serial: str,
    package: str,
    record: dict,
    destination: Path,
) -> list[str]:
    """Pull the four manifest-bound artifacts and validate them as one export."""
    session_id = record.get("session_id")
    if not isinstance(session_id, str) or not _SESSION_ID.fullmatch(session_id):
        raise SynchronizedVideoCheckError("record has no valid committed session_id")
    artifacts = record.get("artifacts")
    if not isinstance(artifacts, dict):
        raise SynchronizedVideoCheckError("record has no artifacts object")

    reasons: list[str] = []
    local_paths: dict[str, Path] = {}
    for artifact_name, (leaf_name, _) in _COMMITTED_ARTIFACTS.items():
        local_path = destination / leaf_name
        pull_binary(
            serial,
            package,
            remote_artifact_path(session_id, leaf_name),
            local_path,
        )
        expected_sha, expected_bytes = _required_artifact_measurement(
            artifacts, artifact_name
        )
        reasons.extend(
            verify_payload(
                local_path,
                expected_sha,
                expected_bytes,
                artifact_name=artifact_name,
            )
        )
        local_paths[artifact_name] = local_path

    if not validate_export(local_paths["manifest"], local_paths["pose_stream"]):
        reasons.append("session_contract_validation_failed")
    return sorted(set(reasons))


def verify_recorded_ledger(root: Path, ledger_relative: str = LEDGER_PATH) -> dict:
    """Verify the immutable host result named by the synchronized-video ledger.

    The live gate already pulled and rehashed all four app-private artifacts. The durable record
    pins that successful host result byte-for-byte, then replays every check that does not require
    a connected device or the ephemeral pulled payloads.
    """
    ledger = _read_json(root / ledger_relative)
    if ledger.get("status") not in {"pending", "recorded"}:
        raise SynchronizedVideoCheckError("ledger status must be pending or recorded")
    if ledger.get("status") == "pending":
        if not ledger.get("missing_precondition"):
            raise SynchronizedVideoCheckError("pending ledger must name its missing precondition")
        return ledger

    evidence_path = _repository_path(root, ledger.get("evidence_file"), "evidence_file")
    if not evidence_path.is_file():
        raise SynchronizedVideoCheckError("recorded synchronized-video evidence is missing")
    payload = evidence_path.read_bytes()
    actual_digest = hashlib.sha256(payload).hexdigest()
    if ledger.get("evidence_sha256") != actual_digest:
        raise SynchronizedVideoCheckError("recorded evidence_sha256 does not match evidence")

    result = _read_json(evidence_path)
    record = result.get("record")
    inherited_probe = result.get("inherited_probe")
    if not isinstance(record, dict) or not isinstance(inherited_probe, dict):
        raise SynchronizedVideoCheckError("recorded evidence has no host result objects")
    assert_sanitized(record)

    probe_path = _repository_path(root, inherited_probe.get("path_ref"), "probe path_ref")
    probe = load_probe(probe_path)
    if inherited_probe.get("sha256") != probe_digest(probe_path):
        raise SynchronizedVideoCheckError("recorded inherited probe sha256 disagrees")
    probe_provenance = probe.get("provenance") or {}
    if inherited_probe.get("codec") != probe_provenance.get("codec"):
        raise SynchronizedVideoCheckError("recorded inherited probe codec disagrees")
    if inherited_probe.get("container") != probe_provenance.get("container"):
        raise SynchronizedVideoCheckError("recorded inherited probe container disagrees")

    reasons = check_record(record, probe, probe_path)
    if result.get("blocking_reasons") != reasons:
        raise SynchronizedVideoCheckError("recorded host blocking reasons disagree")
    artifacts = record.get("artifacts")
    if not isinstance(artifacts, dict):
        raise SynchronizedVideoCheckError("recorded evidence has no artifacts object")
    for artifact_name in _COMMITTED_ARTIFACTS:
        _required_artifact_measurement(artifacts, artifact_name)

    environment = record.get("environment") or {}
    for field in ("device_identity", "api_level", "android_release"):
        if ledger.get(field) != environment.get(field):
            raise SynchronizedVideoCheckError(f"ledger {field} disagrees with evidence")
    admission = "admitted" if not reasons else "blocked"
    if ledger.get("synchronized_video_admission") != admission:
        raise SynchronizedVideoCheckError("ledger admission disagrees with host result")
    return ledger


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
            with tempfile.TemporaryDirectory() as staging:
                reasons.extend(
                    pull_and_verify_committed_export(
                        serial,
                        args.package,
                        record,
                        Path(staging),
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
