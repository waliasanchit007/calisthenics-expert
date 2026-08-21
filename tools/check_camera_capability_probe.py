#!/usr/bin/env python3
"""Verify an S25 camera-capability probe transcript and print the sanitized record.

`CameraCapabilityProbeTest` runs the real design section 8 probe on-device — timestamp source,
CameraX's nullable underlying-image bridge, and the raw `android.media.Image.dataSpace` behind it —
and emits exactly one `CameraCapabilityEvidence=<json>` `am instrument` status line. This script
decides whether that transcript can be trusted and, separately, whether what it observed admits
live capture.

Those are two different questions and they get two different exit statuses:

* ``EXIT_UNTRUSTED`` — the transcript proves nothing. A missing or duplicated record, a failing or
  zero-test run, an unrelated suite, a foreign record kind, a missing required field, or a declared
  verdict that contradicts the observations all land here. `am instrument` exits 0 when tests fail,
  so this status, not the shell's, is the wrapper's verdict.
* ``EXIT_BLOCKING`` — the probe succeeded and live capture is blocked. `UNKNOWN` timestamps, an
  unknown DataSpace, or an unavailable underlying-image bridge are all real observations; design
  section 8 stops the milestone on them rather than silently weakening admission. Their record is
  still printed for recording, because a blocking result is the finding.
* ``EXIT_OK`` — the observation admits live capture on the one authorized physical device.

The verdict is recomputed here from the observed fields alone, mirroring
`cameraLiveAdmissionBlockingReasons` in `shared/commonMain`. The record's own
`satisfies_s25_camera_live_admission` field is never an input; a record whose flag disagrees with
its observations is rejected as untrusted rather than believed.

Like the process-kill and five-run checkers, this never writes the committed ledger. A human copies
the printed record into `docs/evidence/device-probes/` and flips the ledger's `status` by hand;
`verify_recorded_ledger` below is what then holds that hand-written entry to the record it names.

Usage: check_camera_capability_probe.py <transcript-file>   ("-" reads stdin)
"""

from __future__ import annotations

import hashlib
import json
import re
import sys
from pathlib import Path
from typing import Any

EXIT_OK = 0
EXIT_UNTRUSTED = 1
EXIT_USAGE = 2
EXIT_BLOCKING = 3

RECORD_KIND = "camera_capability_probe_device_gate_observation"
LEDGER_PATH = "docs/evidence/device-probes/s25-ultra-camera-capability-ledger.json"

_RECORD_PATTERN = re.compile(r"CameraCapabilityEvidence=(?P<json>\{.*\})")
_SUITE_MARKER = "class=com.calisthenics.diagnostic.probe.CameraCapabilityProbeTest"
_PASSING_RUN = re.compile(r"^OK \(1 test\)$", re.MULTILINE)
_COMMIT_SHA = re.compile(r"^[0-9a-f]{40}$")

# Mirrors android.hardware.DataSpace.DATASPACE_UNKNOWN and CAMERA_DATASPACE_UNKNOWN in
# shared/commonMain. A device reporting it has made a real observation, never an admissible one.
DATASPACE_UNKNOWN = 0

BLOCK_TIMESTAMP_SOURCE = "timestamp_source_not_realtime"
BLOCK_UNDERLYING_IMAGE = "underlying_image_bridge_unavailable"
BLOCK_RAW_DATASPACE = "raw_dataspace_unknown"
BLOCK_COLOUR_STANDARD = "colour_standard_undecoded"
BLOCK_COLOUR_RANGE = "colour_range_undecoded"
BLOCK_UNAUTHORIZED_TARGET = "unauthorized_device_target"

_REALTIME = "REALTIME"

# Model number is the authoritative identifier. Retail S25 Ultra firmware does not necessarily
# populate ro.config.marketing_name, so a marketing-name-only rule rejects the very hardware this
# gate exists to authorize. Mirrors isAuthorizedS25Target in shared/commonMain.
_S25_MARKER = "S25 Ultra"
_S25_MODEL_PREFIX = "SM-S938"
_AUTHORIZED_MANUFACTURER = "samsung"

_REQUIRED_ENVIRONMENT_FIELDS = (
    "device_class",
    "device_identity",
    "device_fingerprint",
    "api_level",
    "android_release",
    "abi",
    "manufacturer",
    "model",
    "marketing_name",
)
_REQUIRED_PROVENANCE_FIELDS = ("os_build", "camerax_version", "probe_git_sha")
_REQUIRED_OBSERVATION_FIELDS = (
    "camera_id",
    "timestamp_source",
    "source_dataspace_raw",
    "source_color_standard",
    "source_color_range",
    "underlying_image_available",
    "pixel_format",
)
# Nothing participant-derived may ever appear in a probe record. The probe runs before any
# participant exists, so any of these keys means the record is not the record it claims to be.
_FORBIDDEN_KEYS = (
    "normalized_image_landmarks",
    "world_landmarks",
    "pose_present",
    "landmark_x",
    "landmark_y",
    "landmark_z",
)
# The identity fields a hand-written recorded ledger entry must agree with the record about.
_LEDGER_IDENTITY_FIELDS = (
    "device_class",
    "device_identity",
    "api_level",
    "android_release",
)


class CameraProbeCheckError(Exception):
    """Raised when a transcript or a recorded ledger entry cannot be trusted."""


def is_authorized_camera_probe_target(environment: dict[str, Any]) -> bool:
    """Whether the observing device is the one authorized physical camera-probe gate target."""
    if environment.get("device_class") != "physical_device":
        return False
    manufacturer = environment.get("manufacturer")
    if (
        not isinstance(manufacturer, str)
        or manufacturer.lower() != _AUTHORIZED_MANUFACTURER
    ):
        return False
    model = environment.get("model")
    marketing_name = environment.get("marketing_name")
    model = model if isinstance(model, str) else ""
    marketing_name = marketing_name if isinstance(marketing_name, str) else ""
    return model.upper().startswith(_S25_MODEL_PREFIX) or _S25_MARKER in marketing_name


def live_admission_blocking_reasons(record: dict[str, Any]) -> list[str]:
    """Every reason this observation blocks live admission, in the Kotlin renderer's order.

    Fails closed: a field of the wrong type is treated as unobserved rather than as satisfying its
    conjunct, so a malformed record can only ever block. Every clause is load-bearing and is
    covered by a named case in `reference/tests/test_camera_capability_probe_check.py`.
    """
    observation = record.get("observation")
    environment = record.get("environment")
    observation = observation if isinstance(observation, dict) else {}
    environment = environment if isinstance(environment, dict) else {}

    reasons: list[str] = []
    if observation.get("timestamp_source") != _REALTIME:
        reasons.append(BLOCK_TIMESTAMP_SOURCE)
    if observation.get("underlying_image_available") is not True:
        reasons.append(BLOCK_UNDERLYING_IMAGE)
    raw = observation.get("source_dataspace_raw")
    if isinstance(raw, bool) or not isinstance(raw, int) or raw == DATASPACE_UNKNOWN:
        reasons.append(BLOCK_RAW_DATASPACE)
    if not isinstance(observation.get("source_color_standard"), str):
        reasons.append(BLOCK_COLOUR_STANDARD)
    if not isinstance(observation.get("source_color_range"), str):
        reasons.append(BLOCK_COLOUR_RANGE)
    if not is_authorized_camera_probe_target(environment):
        reasons.append(BLOCK_UNAUTHORIZED_TARGET)
    return reasons


def satisfies_live_admission(record: dict[str, Any]) -> bool:
    """Whether the observation admits live capture. Derived, never read off the record."""
    return not live_admission_blocking_reasons(record)


def require_exact_suite(transcript: str) -> None:
    """Require the transcript to show exactly the probe suite, with its one test passing.

    A generic success-line match would accept `OK (0 tests)` — precisely what a mistyped class name
    produces — and would accept a run of any other suite.
    """
    if _SUITE_MARKER not in transcript:
        raise CameraProbeCheckError(
            "transcript does not show CameraCapabilityProbeTest running; refusing to trust an "
            "unrelated or empty run"
        )
    if not _PASSING_RUN.search(transcript):
        raise CameraProbeCheckError(
            'transcript does not report exactly one passing test ("OK (1 test)"); a failing or '
            "vacuous run is not evidence"
        )


def _require(condition: bool, detail: str) -> None:
    if not condition:
        raise CameraProbeCheckError(detail)


def validate_record(record: dict[str, Any]) -> None:
    """Require every field the gate and the human recording step depend on."""
    _require(
        record.get("record_kind") == RECORD_KIND,
        f"record_kind is {record.get('record_kind')!r}, not {RECORD_KIND!r}; this is not a camera "
        "capability probe observation",
    )
    _require(bool(record.get("emitted_by")), "record does not say what emitted it")
    _require(record.get("sanitized") is True, "record is not marked sanitized")
    _require(
        record.get("contains_landmark_coordinates") is False,
        "record does not declare itself free of landmark coordinates",
    )
    _require(
        record.get("non_participant_observation") is True,
        "record does not carry the explicit non-participant classification",
    )

    serialized = json.dumps(record)
    for key in _FORBIDDEN_KEYS:
        _require(
            f'"{key}"' not in serialized,
            f"record carries the participant-derived key {key!r}; a probe observation never may",
        )

    for section, fields in (
        ("environment", _REQUIRED_ENVIRONMENT_FIELDS),
        ("provenance", _REQUIRED_PROVENANCE_FIELDS),
        ("observation", _REQUIRED_OBSERVATION_FIELDS),
    ):
        block = record.get(section)
        _require(isinstance(block, dict), f"record has no {section} block")
        assert isinstance(block, dict)
        missing = [field for field in fields if field not in block]
        _require(not missing, f"record {section} block is missing {missing}")

    environment = record["environment"]
    api_level = environment["api_level"]
    _require(
        not isinstance(api_level, bool)
        and isinstance(api_level, int)
        and api_level > 0,
        f"environment api_level {api_level!r} is not a positive integer",
    )
    for field in (
        "device_class",
        "device_identity",
        "device_fingerprint",
        "android_release",
        "abi",
        "manufacturer",
        "model",
    ):
        _require(bool(environment[field]), f"environment {field} is empty")

    provenance = record["provenance"]
    _require(bool(provenance["os_build"]), "provenance os_build is empty")
    _require(bool(provenance["camerax_version"]), "provenance camerax_version is empty")
    sha = provenance["probe_git_sha"]
    _require(
        isinstance(sha, str) and bool(_COMMIT_SHA.match(sha)),
        f"provenance probe_git_sha {sha!r} is not a 40-hex commit SHA, so the record is not bound "
        "to the probe source that produced it",
    )

    observation = record["observation"]
    _require(bool(observation["camera_id"]), "observation camera_id is empty")
    _require(
        isinstance(observation["underlying_image_available"], bool),
        "observation underlying_image_available is not a boolean",
    )

    reasons = live_admission_blocking_reasons(record)
    declared = record.get("satisfies_s25_camera_live_admission")
    _require(
        isinstance(declared, bool) and declared == (not reasons),
        "record's satisfies_s25_camera_live_admission disagrees with its own observations "
        f"(declared {declared!r}, observed blocking reasons {reasons}); the verdict is derived, "
        "never declared, so this record has been edited",
    )
    declared_reasons = record.get("live_admission_blocking_reasons")
    _require(
        declared_reasons == reasons,
        f"record's live_admission_blocking_reasons {declared_reasons!r} disagree with the "
        f"observed {reasons!r}",
    )
    _require(
        record.get("authorized_s25_target")
        == is_authorized_camera_probe_target(record["environment"]),
        "record's authorized_s25_target disagrees with its own environment",
    )


def extract_record(transcript: str) -> dict[str, Any]:
    """The one sanitized record this run emitted, validated."""
    matches = _RECORD_PATTERN.findall(transcript)
    if len(matches) != 1:
        raise CameraProbeCheckError(
            f"expected exactly one CameraCapabilityEvidence record, found {len(matches)}"
        )
    try:
        record = json.loads(matches[0])
    except json.JSONDecodeError as error:
        raise CameraProbeCheckError(
            f"emitted record is not parseable JSON: {error}"
        ) from error
    if not isinstance(record, dict):
        raise CameraProbeCheckError("emitted record is not a JSON object")
    validate_record(record)
    return record


def _read_json(path: Path) -> dict[str, Any]:
    try:
        text = path.read_text(encoding="utf-8")
    except OSError as error:
        raise CameraProbeCheckError(f"cannot read {path}: {error}") from error
    try:
        loaded = json.loads(text)
    except json.JSONDecodeError as error:
        raise CameraProbeCheckError(f"{path} is not parseable JSON: {error}") from error
    if not isinstance(loaded, dict):
        raise CameraProbeCheckError(f"{path} is not a JSON object")
    return loaded


def verify_recorded_ledger(
    root: Path, ledger_relative: str = LEDGER_PATH
) -> dict[str, Any]:
    """Hold the hand-written ledger entry at `ledger_relative` to the record it names.

    A `pending` entry must say which physical observation is missing, so an absent device surfaces
    as an honest gap rather than a silent omission. A `recorded` entry is checked all the way
    through: the referenced record must exist, hash to the digest the ledger pinned, carry every
    required observation, have been observed on the authorized physical device, and — the point of
    the exercise — its computed admission result must match the `admitted`/`blocked` classification
    the ledger claims. Recording a blocking observation as admitted is exactly the failure this
    catches.

    The ledger is only ever read here. Nothing in this module writes it.
    """
    ledger = _read_json(root / ledger_relative)
    status = ledger.get("status")
    _require(
        status in {"pending", "recorded"},
        f"ledger status {status!r} is neither 'pending' nor 'recorded'",
    )
    if status == "pending":
        _require(
            bool(ledger.get("missing_precondition")),
            "a pending ledger must name the missing physical observation",
        )
        return ledger

    evidence_relative = ledger.get("evidence_file")
    _require(
        isinstance(evidence_relative, str) and bool(evidence_relative),
        "a recorded ledger must reference the evidence file it was recorded from",
    )
    assert isinstance(evidence_relative, str)
    evidence_path = root / evidence_relative
    _require(
        evidence_path.is_file(),
        f"recorded evidence file {evidence_relative} does not exist",
    )

    payload = evidence_path.read_bytes()
    actual_digest = hashlib.sha256(payload).hexdigest()
    _require(
        ledger.get("evidence_sha256") == actual_digest,
        f"recorded evidence_sha256 {ledger.get('evidence_sha256')!r} does not match the referenced "
        f"record's actual digest {actual_digest!r}",
    )

    record = _read_json(evidence_path)
    validate_record(record)
    _require(
        is_authorized_camera_probe_target(record["environment"]),
        "recorded evidence was not observed on the authorized physical Samsung Galaxy S25 Ultra",
    )

    computed = "admitted" if satisfies_live_admission(record) else "blocked"
    _require(
        ledger.get("live_admission") == computed,
        f"ledger classifies the observation as {ledger.get('live_admission')!r} but the record's "
        f"own observations compute {computed!r}",
    )

    environment = record["environment"]
    for field in _LEDGER_IDENTITY_FIELDS:
        _require(
            ledger.get(field) == environment[field],
            f"ledger {field} {ledger.get(field)!r} disagrees with the record's "
            f"{environment[field]!r}",
        )
    return ledger


def main(argv: list[str]) -> int:
    if len(argv) != 2:
        print(
            f"usage: {Path(argv[0]).name} <transcript-file>",
            file=sys.stderr,
        )
        return EXIT_USAGE

    transcript = (
        sys.stdin.read()
        if argv[1] == "-"
        else Path(argv[1]).read_text(encoding="utf-8")
    )

    try:
        require_exact_suite(transcript)
        record = extract_record(transcript)
    except CameraProbeCheckError as error:
        print(f"error: {error}", file=sys.stderr)
        return EXIT_UNTRUSTED

    reasons = live_admission_blocking_reasons(record)
    classification = "blocked" if reasons else "admitted"
    print(
        f"camera capability probe observed on camera {record['observation']['camera_id']}"
    )
    print(f"  timestamp_source={record['observation']['timestamp_source']}")
    print(f"  source_dataspace_raw={record['observation']['source_dataspace_raw']}")
    print(
        f"  underlying_image_available={str(record['observation']['underlying_image_available']).lower()}"
    )
    print(f"  satisfies_s25_camera_live_admission={str(not reasons).lower()}")
    print(
        f"human step required: copy the record below into docs/evidence/device-probes/, then set "
        f'the ledger\'s status to "recorded" with live_admission "{classification}" pointing at it'
    )
    print(json.dumps(record, sort_keys=True))

    if reasons:
        # Design section 8: the harness stays fail-closed and the milestone stops for a material
        # scope/contract decision. A distinct status so a caller can tell "the probe told us live
        # capture is not admissible here" from "the transcript could not be trusted at all".
        print(
            "error: the probe succeeded but live capture is NOT admissible: "
            + ", ".join(reasons),
            file=sys.stderr,
        )
        print(
            "the milestone stops here for a material scope/contract decision; record the "
            "observation above and do not weaken admission",
            file=sys.stderr,
        )
        return EXIT_BLOCKING
    return EXIT_OK


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
