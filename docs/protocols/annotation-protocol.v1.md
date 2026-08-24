# M0.5 annotation protocol v1

Status: interface definition only. No annotation tooling, participant media, consent template, or
participant capture is created by this document.

## Blind primary view

The primary labeling component receives the source video and this protocol only. Its input surface
must structurally exclude pose overlays, skeletons, predictions, confidence values, and
machine-derived events; hiding those values behind a toggle is not sufficient. An overlay-visible
diagnostic view, if later built, is a separate failure-analysis artifact and never writes ground
truth.

## Independent labels and adjudication

Definitions are written before labeling and are visually checkable: `rep_start`, `top`, `bottom`,
`rep_end`, `tracking_failure`, and the labels `valid`, `invalid`, and `uncertain`. `uncertain` is a
first-class reported label. Invalid observations use reason codes such as partial range of motion,
kipping or leg drive, assisted movement, and non-continuous movement; numeric angle thresholds are
not introduced here.

Independent labels are separate immutable artifacts. Adjudication is a distinct artifact that names
the independent inputs it consumed. No annotation-dependent claim, Gold label, or split freeze is
admitted until two independent annotators are available; exploratory conditions may remain
unannotated until that human gate is satisfied.

## Canonical artifact cell slugs

The annotation artifact vocabulary includes the shared capture cells and the annotation layers:

- `session_manifest`
- `raw_pose_stream`
- `synchronization_evidence`
- `device_metadata`
- `camera_capability`
- `failure_log`
- `thermal_battery_trace`
- `cadence_trace`
- `synthetic_input_attestation`
- `independent_annotation`
- `adjudicated_annotation`

## Canonical metric cell slugs

- `detection_rate_curve`
- `dropout_runs`
- `landmark_jitter`
- `cadence_latency_thermal_battery`
- `synchronization_offset_drift`
- `annotation_agreement`

The candidate matrix uses only these declared slugs. Agreement is reported after two independent
passes and adjudication; it is not a signal-quality threshold and cannot authorize participant
capture.
