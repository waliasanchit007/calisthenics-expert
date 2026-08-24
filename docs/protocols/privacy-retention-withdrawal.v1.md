# M0.5 privacy, retention, and withdrawal protocol v1

Status: undecided policy interface. This document does not assign a human owner, storage role,
retention period, withdrawal channel, access role, face policy, or participant-capture authority.

## Human decisions

Each decision below is represented in the readiness manifest as `UNDECIDED`, `ACCEPTED`, or
`REJECTED`, with a digest-bound decision record required for either non-`UNDECIDED` state. The
owner is intentionally `UNDECIDED`; the required evidence is the named decision record and the
effect is mechanical. No decision is accepted in the committed M0.5 manifest.

| Decision | Owner | Required evidence | `UNDECIDED` effect | `ACCEPTED` effect | `REJECTED` effect |
|---|---|---|---|---|---|
| `storage_administrator` | UNDECIDED | digest-bound storage-administrator decision record | participant capture blocked | records the human decision for later gate evaluation | participant capture blocked |
| `storage_location` | UNDECIDED | digest-bound storage-location decision record | participant capture blocked | records the human decision for later gate evaluation | participant capture blocked |
| `access_matrix` | UNDECIDED | digest-bound access-matrix decision record | participant capture blocked | records the human decision for later gate evaluation | participant capture blocked |
| `retention_period` | UNDECIDED | digest-bound retention-period decision record | participant capture blocked | records the human decision for later gate evaluation | participant capture blocked |
| `withdrawal_and_deletion` | UNDECIDED | digest-bound withdrawal-and-deletion decision record | participant capture blocked | records the human decision for later gate evaluation | participant capture blocked |
| `backup_handling` | UNDECIDED | digest-bound backup-handling decision record | participant capture blocked | records the human decision for later gate evaluation | participant capture blocked |
| `incident_response` | UNDECIDED | digest-bound incident-response decision record | participant capture blocked | records the human decision for later gate evaluation | participant capture blocked |
| `face_handling` | UNDECIDED | digest-bound face-handling decision record | participant capture blocked | records the human decision for later gate evaluation | participant capture blocked |

No retention duration, access role, storage administrator, withdrawal channel, or face-blur default
is specified. A later human decision must state the selected value and its deletion propagation,
backup, incident, and audit consequences. Pose landmarks remain potentially sensitive body/movement
data; conversion from media does not become anonymisation by default.

## Registered artifact locations

The readiness manifest enumerates the complete location registry, without declaring that any
participant artifact exists:

- `device_export`
- `device_staging`
- `device_transfer_staging`
- `study_store_source`
- `study_store_derived`
- `study_store_report`
- `tombstone`

The synthetic rehearsal uses the same location shape without participant data. Withdrawal, when a
later human gate permits participant capture, must enumerate eligible copies, delete them, verify
absence, and retain only the minimal tombstone permitted by the later decision record. It must not
rewrite an admitted artifact.

## Mechanical participant gate

Participant capture can open only when all eight decisions are `ACCEPTED`, every decision record is
digest-valid, the candidate matrix is `LOCKED`, the locked matrix is digest-valid, and a separate
digest-bound resource-availability confirmation is valid. The committed candidate is `UNLOCKED`,
resource availability is not confirmed, and all eight decisions remain `UNDECIDED`; this protocol
therefore authorizes no participant capture.
