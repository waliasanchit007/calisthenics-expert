"""Bind the detection-tier admission evidence to the pinned inputs and to a real native run.

Two claims are checked here, and neither may be self-declared.

The first is a claim about a run that is happening now: `make verify-harness-detection-replay`
executes the instrumented replay suite on an attached device. `am instrument` exits 0 even when
every test fails, so the transcript is the load-bearing signal — but a generic `OK (` match accepts
`OK (0 tests)` from a mistyped class name, accepts a run of a different suite entirely, and accepts
a run that observed nothing. The wrapper's verdict comes from :func:`verify_instrumented_run`,
which requires the exact expected suite, every one of its tests, and the sanitized observation
record the run emits.

The second is a claim already committed to the repository: `detection_evidence.status = confirmed`
in the detection fixture's metadata says the pinned model fired on the committed pixels. Non-empty
fields do not establish that. :func:`verify_committed_evidence` binds the claim to the exact model
SHA-256 the repository pins, the exact Tasks Vision pin, the exact fixture frame digests, and a
committed sanitized artifact of the run itself.

What this is not: a signing or attestation scheme. The artifact is an auditable sanitized
observation of an emulator run, transcribed from what the device emitted. It is deliberately not
proof of the Samsung Galaxy S25 Ultra device gate, and not a claim of cross-device numeric
stability.
"""

from __future__ import annotations

import hashlib
import json
import re
from collections import Counter
from dataclasses import dataclass
from pathlib import Path
from typing import Any

EXPECTED_SUITE = "com.calisthenics.diagnostic.mediapipe.DetectionTierReplayTopologyTest"
"""The one suite where `PoseLandmarker.createFromOptions` and `detectForVideo` actually execute."""

EXPECTED_TEST_NAMES = frozenset(
    {
        "theStagedFixturePlanesAreTheDeclaredBytes",
        "thePinnedModelFiresOnEveryFrameWithIdenticalTopologyAcrossRuns",
        "theRecordedDeviceClassIsDerivedFromBuildRatherThanAsserted",
    }
)
"""Pinned by name: a silently dropped or renamed case must fail the wrapper, not shrink the run."""

EVIDENCE_STATUS_KEY = "ReplayDetectionEvidence"
"""The `am instrument` status key the instrumented run publishes its one record under."""

RECORD_KIND = "detection_tier_replay_observation"
EVIDENCE_ROOT = "docs/evidence"
FIXTURE_METADATA_PATH = "fixtures/synthetic/valid/fixture_detection/fixture_metadata.json"
MODEL_PROVENANCE_PATH = "apps/diagnostic-harness/model-assets/model_provenance.json"
LIBS_VERSIONS_PATH = "apps/diagnostic-harness/gradle/libs.versions.toml"

_FAILURE_MARKERS = (
    "FAILURES!!!",
    "Process crashed",
    "INSTRUMENTATION_ABORTED",
    "INSTRUMENTATION_FAILED",
    "shortMsg=",
)
_OK_LINE = re.compile(r"^OK \((\d+) tests?\)$", re.MULTILINE)
_STATUS_LINE = re.compile(r"^INSTRUMENTATION_STATUS: ([^=]+)=(.*)$")
_STATUS_CODE_LINE = re.compile(r"^INSTRUMENTATION_STATUS_CODE: (-?\d+)$")
_MEDIAPIPE_PIN = re.compile(r'^mediapipe\s*=\s*"([^"]+)"', re.MULTILINE)


class DetectionEvidenceError(RuntimeError):
    """A detection-tier admission claim is not backed by the run or the pins it names."""


@dataclass(frozen=True)
class PinnedInputs:
    """The repository's own pins, read from the files that own them."""

    model_asset_name: str
    model_sha256: str
    model_byte_count: int
    tasks_vision_version: str
    running_mode: str
    delegate: str
    num_poses: int
    fixture_id: str
    frame_width: int
    frame_height: int
    frame_indices: tuple[int, ...]
    frame_sha256: tuple[str, ...]


def _read_json(path: Path) -> Any:
    if not path.is_file():
        raise DetectionEvidenceError(f"{path} is missing")
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as error:
        raise DetectionEvidenceError(f"{path} is not valid JSON: {error}") from error


def load_pinned_inputs(root: Path) -> PinnedInputs:
    """Read the model, Tasks Vision, and fixture pins from their owning files.

    The Tasks Vision version is pinned twice — in the Gradle version catalogue, which decides what
    the device actually links against, and in the model provenance manifest, which is what recorded
    evidence is compared to. A split between them would let evidence be bound to a version no build
    produces, so the split itself is rejected here rather than silently preferring one side.
    """
    provenance = _read_json(root / MODEL_PROVENANCE_PATH)
    metadata = _read_json(root / FIXTURE_METADATA_PATH)

    catalogue = (root / LIBS_VERSIONS_PATH).read_text(encoding="utf-8")
    pinned_in_catalogue = _MEDIAPIPE_PIN.search(catalogue)
    if pinned_in_catalogue is None:
        raise DetectionEvidenceError(f"{LIBS_VERSIONS_PATH} declares no mediapipe version")
    if pinned_in_catalogue.group(1) != provenance["tasks_vision_version"]:
        raise DetectionEvidenceError(
            "tasks_vision_version is split between the pins: "
            f"{LIBS_VERSIONS_PATH} says {pinned_in_catalogue.group(1)}, "
            f"{MODEL_PROVENANCE_PATH} says {provenance['tasks_vision_version']}"
        )

    frames = metadata["frames"]
    return PinnedInputs(
        model_asset_name=provenance["asset_name"],
        model_sha256=provenance["sha256"],
        model_byte_count=provenance["byte_count"],
        tasks_vision_version=provenance["tasks_vision_version"],
        running_mode=provenance["running_mode"],
        delegate=provenance["delegate"],
        num_poses=provenance["task_options"]["num_poses"],
        fixture_id=metadata["fixture_id"],
        frame_width=metadata["frame_width"],
        frame_height=metadata["frame_height"],
        frame_indices=tuple(frame["frame_index"] for frame in frames),
        frame_sha256=tuple(frame["sha256"] for frame in frames),
    )


def _require(condition: bool, message: str) -> None:
    if not condition:
        raise DetectionEvidenceError(message)


def _equal(label: str, observed: object, pinned: object, where: str) -> None:
    _require(
        observed == pinned,
        f"{where}: {label} is {observed!r}, the repository pins {pinned!r}",
    )


def verify_record_against_pins(record: Any, pinned: PinnedInputs, where: str) -> None:
    """Bind one sanitized observation record to the exact inputs the repository pins.

    A record that names a different model, a different Tasks Vision release, or a different frame
    set is evidence about something else. It is not weaker evidence about this fixture.
    """
    _require(isinstance(record, dict), f"{where}: the observation record is not a JSON object")
    _equal("record_kind", record.get("record_kind"), RECORD_KIND, where)
    _equal("emitted_by", record.get("emitted_by"), EXPECTED_SUITE, where)
    _equal("sanitized", record.get("sanitized"), True, where)
    _equal(
        "contains_landmark_coordinates", record.get("contains_landmark_coordinates"), False, where
    )
    _equal("satisfies_s25_device_gate", record.get("satisfies_s25_device_gate"), False, where)

    provenance = record.get("provenance")
    _require(isinstance(provenance, dict), f"{where}: the record carries no provenance block")
    assert isinstance(provenance, dict)
    _equal("model_asset_name", provenance.get("model_asset_name"), pinned.model_asset_name, where)
    _equal("model_sha256", provenance.get("model_sha256"), pinned.model_sha256, where)
    _equal(
        "tasks_vision_version",
        provenance.get("tasks_vision_version"),
        pinned.tasks_vision_version,
        where,
    )
    _equal("running_mode", provenance.get("running_mode"), pinned.running_mode, where)
    _equal("delegate", provenance.get("delegate"), pinned.delegate, where)
    _equal("num_poses", provenance.get("num_poses"), pinned.num_poses, where)

    fixture = record.get("fixture")
    _require(isinstance(fixture, dict), f"{where}: the record carries no fixture block")
    assert isinstance(fixture, dict)
    _equal("fixture_id", fixture.get("fixture_id"), pinned.fixture_id, where)
    _equal("frame_width", fixture.get("frame_width"), pinned.frame_width, where)
    _equal("frame_height", fixture.get("frame_height"), pinned.frame_height, where)
    _equal("frame_count", fixture.get("frame_count"), len(pinned.frame_indices), where)
    _equal("frame_indices", fixture.get("frame_indices"), list(pinned.frame_indices), where)
    _equal("frame_sha256", fixture.get("frame_sha256"), list(pinned.frame_sha256), where)

    environment = record.get("environment")
    _require(isinstance(environment, dict), f"{where}: the record carries no environment block")
    assert isinstance(environment, dict)
    _require(
        environment.get("device_class") in {"emulator", "physical_device"},
        f"{where}: device_class is {environment.get('device_class')!r}",
    )
    for field in ("device_identity", "device_fingerprint", "android_release", "abi"):
        _require(bool(environment.get(field)), f"{where}: environment.{field} is empty")
    _require(
        isinstance(environment.get("api_level"), int) and environment["api_level"] >= 1,
        f"{where}: environment.api_level is not a positive integer",
    )

    _verify_detection_fired(record, pinned, where)


def _verify_detection_fired(record: Any, pinned: PinnedInputs, where: str) -> None:
    """The admission criterion itself: a pose on every declared frame, and not a frozen one."""
    frame_count = len(pinned.frame_indices)
    topology = record.get("topology")
    _require(isinstance(topology, dict), f"{where}: the record carries no topology block")
    assert isinstance(topology, dict)
    _equal(
        "pose_present_frames",
        topology.get("pose_present_frames"),
        list(pinned.frame_indices),
        where,
    )
    _equal("pose_count_per_frame", topology.get("pose_count_per_frame"), [1] * frame_count, where)
    for field in ("normalized_landmark_count_per_frame", "world_landmark_count_per_frame"):
        counts = topology.get(field)
        _require(
            isinstance(counts, list) and len(counts) == frame_count,
            f"{where}: topology.{field} does not cover the {frame_count} declared frames",
        )
        assert isinstance(counts, list)
        _require(
            all(isinstance(count, int) and count >= 1 for count in counts),
            f"{where}: topology.{field} reports a frame with no landmarks: {counts}",
        )
    _equal(
        "presence_pattern_fully_populated",
        topology.get("presence_pattern_fully_populated"),
        True,
        where,
    )

    digests = record.get("landmark_observation_sha256")
    _require(
        isinstance(digests, list) and len(digests) == frame_count,
        f"{where}: landmark_observation_sha256 does not cover the {frame_count} declared frames",
    )
    assert isinstance(digests, list)
    # A frozen or reused result satisfies every cardinality claim above while observing nothing.
    _require(
        len(set(digests)) == len(digests),
        f"{where}: the {frame_count} landmark observation digests are not distinct, so a frozen "
        "or reused backend result would satisfy every cardinality claim above",
    )

    run = record.get("run")
    _require(isinstance(run, dict), f"{where}: the record carries no run block")
    assert isinstance(run, dict)
    _equal("fresh_pose_landmarker_per_run", run.get("fresh_pose_landmarker_per_run"), True, where)
    _require(
        isinstance(run.get("runs_per_invocation"), int) and run["runs_per_invocation"] >= 2,
        f"{where}: cross-run topology equality needs at least two runs, "
        f"run.runs_per_invocation is {run.get('runs_per_invocation')!r}",
    )
    for field in ("backend_timestamps_ms", "canonical_input_sha256"):
        values = run.get(field)
        _require(
            isinstance(values, list) and len(values) == frame_count,
            f"{where}: run.{field} does not cover the {frame_count} declared frames",
        )


def verify_committed_evidence(root: Path) -> list[str]:
    """Verify every committed `confirmed` admission against its native-run artifact.

    Returns the artifact paths that were verified, so a caller can prove the check was not
    vacuous. A `pending` status is an honest state and binds nothing.
    """
    pinned = load_pinned_inputs(root)
    metadata = _read_json(root / FIXTURE_METADATA_PATH)
    evidence = metadata["detection_evidence"]
    if evidence["status"] != "confirmed":
        return []

    _equal(
        "confirmed_by",
        evidence.get("confirmed_by", "").split("/")[-1].removesuffix(".kt"),
        EXPECTED_SUITE.split(".")[-1],
        "detection_evidence",
    )
    observations = evidence.get("observations")
    _require(
        isinstance(observations, list) and len(observations) >= 1,
        "detection_evidence: a confirmed status carries no observations",
    )
    assert isinstance(observations, list)

    verified: list[str] = []
    for position, observation in enumerate(observations):
        where = f"detection_evidence.observations[{position}]"
        binding = observation.get("evidence_artifact")
        _require(
            isinstance(binding, dict) and bool(binding.get("path")) and bool(binding.get("sha256")),
            f"{where}: a confirmed observation must name an evidence_artifact with a path and a "
            "sha256; confirmed metadata that points at no artifact is a declaration, not evidence",
        )
        assert isinstance(binding, dict)
        relative = binding["path"]
        _require(
            relative.startswith(f"{EVIDENCE_ROOT}/") and relative.endswith(".json"),
            f"{where}: evidence_artifact.path {relative!r} is not a JSON artifact under "
            f"{EVIDENCE_ROOT}/",
        )
        artifact = root / relative
        _require(artifact.is_file(), f"{where}: evidence_artifact {relative} is missing")

        raw = artifact.read_bytes()
        actual = hashlib.sha256(raw).hexdigest()
        _require(
            actual == binding["sha256"],
            f"{where}: evidence_artifact {relative} content sha256 is {actual}, the recorded "
            f"binding says {binding['sha256']}",
        )

        record = json.loads(raw.decode("utf-8"))
        verify_record_against_pins(record, pinned, relative)
        _verify_observation_mirrors_artifact(observation, record, where)
        verified.append(relative)
    return verified


_MIRRORED_ENVIRONMENT_FIELDS = (
    "device_class",
    "device_identity",
    "device_fingerprint",
    "api_level",
    "android_release",
    "abi",
)
_MIRRORED_PROVENANCE_FIELDS = (
    "model_asset_name",
    "model_sha256",
    "tasks_vision_version",
    "running_mode",
    "delegate",
    "num_poses",
)
_MIRRORED_TOPOLOGY_FIELDS = (
    "pose_present_frames",
    "pose_count_per_frame",
    "normalized_landmark_count_per_frame",
    "world_landmark_count_per_frame",
    "presence_pattern_fully_populated",
)


def _verify_observation_mirrors_artifact(observation: Any, record: Any, where: str) -> None:
    """The human-readable observation must not drift from the artifact it cites.

    The fixture metadata entry is transcribed for readers; the artifact is what the device emitted.
    Any divergence means one of the two is describing a run that did not happen.
    """
    for field in _MIRRORED_ENVIRONMENT_FIELDS:
        _equal(field, observation.get(field), record["environment"][field], where)
    for field in _MIRRORED_PROVENANCE_FIELDS:
        _equal(field, observation.get(field), record["provenance"][field], where)
    for field in _MIRRORED_TOPOLOGY_FIELDS:
        _equal(field, observation.get(field), record["topology"][field], where)
    _equal(
        "landmark_observation_sha256",
        observation.get("landmark_observation_sha256"),
        record["landmark_observation_sha256"],
        where,
    )
    _equal(
        "runs_per_invocation",
        observation.get("runs_per_invocation"),
        record["run"]["runs_per_invocation"],
        where,
    )
    _equal("satisfies_s25_device_gate", observation.get("satisfies_s25_device_gate"), False, where)
    _require(
        "S25" in str(observation.get("note", "")),
        f"{where}: the observation must state in the artifact that it is not the S25 device gate",
    )


@dataclass(frozen=True)
class _TestOutcome:
    suite: str
    name: str
    status_code: int


def _parse_transcript(output: str) -> tuple[list[_TestOutcome], list[str]]:
    """Walk an `am instrument -w` transcript into completed test outcomes and emitted records.

    `stream=` values span continuation lines that are not `INSTRUMENTATION_`-prefixed, so only
    prefixed lines are read as keys and everything else is ignored. A status-code line closes the
    block it belongs to.
    """
    outcomes: list[_TestOutcome] = []
    records: list[str] = []
    block: dict[str, str] = {}
    for line in output.splitlines():
        status = _STATUS_LINE.match(line)
        if status is not None:
            block[status.group(1)] = status.group(2)
            continue
        code = _STATUS_CODE_LINE.match(line)
        if code is None:
            continue
        if EVIDENCE_STATUS_KEY in block:
            records.append(block[EVIDENCE_STATUS_KEY])
        # Status code 1 is "test started"; a terminal code is what makes an outcome.
        if "test" in block and "class" in block and int(code.group(1)) != 1:
            outcomes.append(
                _TestOutcome(
                    suite=block["class"], name=block["test"], status_code=int(code.group(1))
                )
            )
        block = {}
    return outcomes, records


def verify_instrumented_run(root: Path, output: str) -> Any:
    """Decide whether an `am instrument` transcript is an actual passing detection-tier run.

    Returns the sanitized observation record the run emitted. Raises otherwise — the caller's exit
    status is the wrapper's verdict, so nothing here may be advisory.
    """
    for marker in _FAILURE_MARKERS:
        _require(marker not in output, f"the instrumented run reported {marker!r}")

    outcomes, records = _parse_transcript(output)
    _require(
        bool(outcomes),
        "the instrumented run completed no tests at all; an exit status of 0 from am instrument "
        "means the command ran, not that the suite did",
    )
    foreign = sorted({outcome.suite for outcome in outcomes} - {EXPECTED_SUITE})
    _require(
        not foreign,
        f"the instrumented run executed a different suite {foreign}; the expected suite is "
        f"{EXPECTED_SUITE}",
    )
    failed = sorted(outcome.name for outcome in outcomes if outcome.status_code != 0)
    _require(not failed, f"the instrumented run did not pass every test: {failed}")

    # Cardinality, not membership: comparing names as a set accepts the three expected methods
    # plus one of them a second time, which is a transcript whose terminal outcomes and whose
    # `OK (n tests)` line disagree about what ran. Exactly one terminal outcome per expected
    # method is the admission criterion.
    executed = Counter(outcome.name for outcome in outcomes)
    _require(
        executed == Counter(EXPECTED_TEST_NAMES),
        "the instrumented run did not execute each expected test exactly once: missing test(s) "
        f"{sorted(EXPECTED_TEST_NAMES - executed.keys())}, unexpected test(s) "
        f"{sorted(executed.keys() - EXPECTED_TEST_NAMES)}, test(s) reporting more than one "
        f"terminal outcome {sorted(name for name, count in executed.items() if count > 1)}",
    )

    ok = _OK_LINE.search(output)
    _require(ok is not None, "the instrumented run printed no JUnit result line")
    assert ok is not None
    _require(
        int(ok.group(1)) == len(EXPECTED_TEST_NAMES),
        f"the instrumented run reported OK ({ok.group(1)} tests); the expected suite is "
        f"{len(EXPECTED_TEST_NAMES)} tests",
    )

    _require(
        len(records) == 1,
        f"the instrumented run emitted {len(records)} {EVIDENCE_STATUS_KEY} records; exactly one "
        "sanitized observation record is required",
    )
    try:
        record = json.loads(records[0])
    except json.JSONDecodeError as error:
        raise DetectionEvidenceError(
            f"the emitted {EVIDENCE_STATUS_KEY} record is not valid JSON: {error}"
        ) from error

    pinned = load_pinned_inputs(root)
    verify_record_against_pins(record, pinned, "the emitted observation record")
    committed = verify_committed_evidence(root)
    _require(
        bool(committed),
        "the fixture's detection_evidence is not confirmed against any committed artifact, so a "
        "passing run has nothing to be checked against",
    )
    _compare_against_committed(root, record, committed)
    return record


def _compare_against_committed(root: Path, record: Any, committed: list[str]) -> None:
    """Same device, same pins — then the observation must not have drifted.

    A run on a *different* device is a different observation, not a failure: design section 7 keeps
    emulator and physical-device results separate and treats a divergence between them as a finding
    to record. So the strict comparison is scoped to the fingerprint that produced the committed
    artifact, and anything else is reported rather than rejected.
    """
    fingerprint = record["environment"]["device_fingerprint"]
    for relative in committed:
        artifact = json.loads((root / relative).read_text(encoding="utf-8"))
        if artifact["environment"]["device_fingerprint"] != fingerprint:
            continue
        _require(
            artifact == record,
            f"this run diverges from the committed artifact {relative} recorded on the same "
            f"device fingerprint {fingerprint}; the committed artifact is stale or the "
            "observation changed, and either way it is a finding to record rather than a "
            "difference to overwrite silently",
        )
        return
    print(
        f"note: no committed artifact was recorded on device fingerprint {fingerprint}; this run "
        "is a separate observation and was not compared against "
        f"{', '.join(committed)}"
    )
