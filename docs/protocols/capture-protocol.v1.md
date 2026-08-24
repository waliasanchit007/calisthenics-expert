# M0.5 capture protocol v1

Status: synthetic-only policy interface. This document does not authorize a live study or
participant capture.

## Scope and admission

M0.5 is limited to the flagship Android target `SM-S938B` and the two live non-participant input
classes `empty_scene` and `displayed_synthetic_figure`. `synthetic_fixture` is reserved for offline
replay and is not a live input class. Every live run is admitted only with
`session_kind=live_conformance`, a reserved `synthetic-m05-*` identity, and an operator attestation
that no person is visible in the captured frame before camera start. No participant identity,
consent record, identity mapping, or human fixture is created by this protocol.

The operator stops immediately and marks the run inadmissible if a person enters the frame, required
metadata is absent, or the admission result disagrees with the selected matrix condition. The stop
does not convert the observation into participant evidence and does not authorize a retry under a
different condition.

The empty-scene input must report a detection-active frame fraction of exactly zero. A displayed
synthetic figure is detection-bearing only when positive detection evidence is actually observed;
zero frames, `UNMEASURED`, and zero detection remain pending for detection-dependent cells. These
are truthfulness rules, not signal-quality thresholds.

## Fixed setup observations

Before a future synthetic run, record the camera placement and measured setup observations in the
session manifest: tripod/bar geometry, full-body framing, lighting class and measured lux when
available, clothing class, camera-to-bar distance, camera height, and azimuth angle. Record nominal
and measured values separately. Lock focus and exposure after framing. Use the static reference and
visible/audible slate interfaces when media is introduced; record monotonic clock offset and drift
without treating either as a quality claim.

The device and runtime metadata fields are mandatory: platform, manufacturer/model, OS build,
application version, MediaPipe model digest, runtime version, delegate, requested cadence,
preprocessing contract version, observation-contract version, thermal API/status observations,
battery start/end and charging state. Missing metadata is a stop condition, not an inferred value.

## Canonical artifact cell slugs

The following slugs are the closed artifact-cell vocabulary. Matrix rows must declare their cells
before any result exists; a row may not infer a cell from an observed artifact.

- `session_manifest`
- `raw_pose_stream`
- `synchronization_evidence`
- `device_metadata`
- `camera_capability`
- `failure_log`
- `thermal_battery_trace`
- `cadence_trace`
- `synthetic_input_attestation`

## Canonical metric cell slugs

- `detection_rate_curve`
- `dropout_runs`
- `landmark_jitter`
- `cadence_latency_thermal_battery`
- `synchronization_offset_drift`

## Operating-condition and stop rules

The candidate matrix is immutable by digest and remains `UNLOCKED` until a separate,
digest-bound resource-availability confirmation exists. Athlete, venue, and repeated-session
availability are not confirmed by this protocol. A pre-lock correction creates a new matrix digest,
records the prior digest, and records a non-empty reason. A post-lock removal or required-to-
exploratory demotion remains a failed required condition.

No numeric signal threshold, production-readiness claim, mid-range claim, or cross-platform claim is
authorized. The current S25 evidence is not generalized to another Android device or platform.
