# Lunge candidate: reject straight-opposite-leg confusions

September8 local offline experiment; not an app update or release. Consumes both
September7 movement-reuse research documents and the reuse-led plan. MediaPipe,
pull-ups and push-ups are unchanged.

## Implemented decision

`LungeConfig.admissionProfile` offers opt-in `CONTRALATERAL_NOT_EXTENDED_V1`.
The original `SINGLE_LEG_BASELINE` remains the default, including old JSON configs.
The opt-in independently validates both knee chains. Missing/invalid opposite-leg
geometry resets the candidate with `CONTRALATERAL_UNAVAILABLE`. At the selected
knee's flexed endpoint, the opposite angle must be strictly below the existing
`extendedDegrees`; otherwise `CONTRALATERAL_EXTENDED` resets the candidate. A
straight opposite knee while standing is expected, not rejected. Existing timing,
endpoint-sample and fresh-cycle rules remain. Unknown/mistyped profiles are rejected.

No newly fitted number: frozen replay configs reuse160degrees, adding only the
profile field. This rejects one observed confusion, not generic lunge identity,
anatomical depth, grounding, physical support or form validity. The consultant's
camera-dependent ankle-row threshold is deferred; squat confusion remains.

## Frozen replay results

| Development input | Baseline | Opt-in | Result |
| --- | ---: | ---: | --- |
| Real leg lifts, RIGHT | 1 | 0 | One false lunge count removed |
| Same control, LEFT | 2 | 0 | Two false lunge counts removed |
| w01 lunges | 9 | 9 | Same9matches/1miss/0extras and exact event times |
| w00 lunges | 6 | 6 | Same6matches/3misses/0extras under existing return labels |
| w01 squats | 8 | 8 | Known confusion retained; report UNLABELLED |
| w00 mixed squat/leg-lift | 11 | 10 | Only leg-lift tail removed;10squat candidates retained |

The mixed clip duplicates the RIGHT control's physical leg lift: **three unique
false counts removed**, not four independent successes. All15previously detected
lunge events retain their exact timestamps. These are development results only.

The older w00 lunge clip has30unusable selected-leg frames, plus five newly blocked
opposite-leg frames under the opt-in, with no additional event lost. Report field
`unusable_frames` counts the selected knee; the five additional unavailable frames
appear separately in `reason_counts`. Another recording could lose valid events.

### Annotation-version correction

The worker reused w00's historical bottom-centred v1 labels, which score a different
event from the counter's return-to-standing output:0matches/9misses/6extras for both
profiles. The controller additionally projected identical predictions against the
**already-existing** return-centred v2 labels:6matches/3misses/0extras for both.
No video, prediction or label changed. Those v2 labels disclose their original
non-blind correction; they remain development evidence, not fresh blind gold.
Both report versions are preserved. The v1 mismatch is not a new timing regression.

## Verification and scope

- Test-first:8named behavioral failures; focused GREEN37tests. All20original test
  bodies are unchanged,17tests added. The implementer's18original-tests claim was
  corrected against before-images and current files.
- Bypassing the new gate caused the intended leg-lift, boundary and late-completion
  assertions to fail. Byte-exact restoration followed by focused GREEN.
- Full shared JVM run620tests/74classes,0failures/errors/skips; controller checked XML.
- Controller `make verify` exit0:1,215Python tests, Ruff check/format clean81files,
  strict mypy clean21files, repository/fixture checks. Formal participant-capture
  readiness remains blocked as expected; no new capture authority is implied.
- Controller checked12fresh replay reports: current engine hashes, config-only
  profile difference, cache/video/model/prediction bindings, all frame timestamps,
  event comparisons and reason counts. Six baseline prediction files are byte-
  identical to historical outputs. Both v2 label projections independently verified.

Only four existing experimental Lunge Kotlin source/test files changed. No new
dependency, extraction, app deployment, backend change or Git integration.
Private scorecard-v6 preserves earlier versions:34rows,18comparisons,13unlabelled,
3unmeasured. Repeated inputs under different profiles are not independent recordings
or a pooled accuracy estimate. Raw media/poses/labels stay outside Git.

## Next

Keep the profile offline. Test an unused raw-labelled leg-lift recording and a
non-frontal lunge before generalizing; do not fit a stance threshold merely to
erase the squat confusion. Static skills continue independently, reusing existing
body-line geometry. No new workout is needed for those offline experiments.
