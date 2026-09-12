# Pike push-up offline development result

Pike push-ups now have a selected-mode offline experiment and executable JVM replay. This extends repetition breadth; it is not automatic movement recognition, validated form assessment, or an app deployment.

## Evidence before implementation

The rights-cleared Danielflefil Commons GIF and raw endpoint observations are recorded in `2026-09-09-next-repetition-source.md`. One traversal contains 77 frames and one visible cycle. Replaying its loop would not create independent examples. The canonical desktop pose cache has all six LEFT joints usable in all 77 frames; RIGHT is not usable. LEFT was selected before counter predictions.

Settings were frozen before predictions: elbow extension 155 degrees, flexion 100 degrees, confidence 0.5, maximum gap 250 ms, two endpoint observations, maximum hip angle 130 degrees, minimum knee angle 150 degrees, hip above shoulder by at least 0.03 image height. These are developmental image-plane hypotheses, not anatomical or technique standards.

## Observed replay

| Input | Result | Qualification |
| --- | --- | --- |
| Pike GIF, 77 frames | One candidate at 2.32 seconds; all frames admitted | One development example, not held-out validation. The raw return interval was 2.68–3.04 seconds; the candidate is 360 ms early relative to its start. Matching the total is not matching endpoint timing. |
| Existing dip clip, 379 frames | Zero pike candidates | 362 frames lack usable evidence; 17 are rejected on hip position. Weak discrimination evidence, not 379 negative examples. |
| Existing pull-up clip, 168 frames | Zero pike candidates | 142 frames lack usable evidence; 26 rejected on hip position. Same limitation. |

No settings were changed to fit these results. Standard push-up and dip geometry confusions also have synthetic behavioral tests; no real standard-push-up replay was performed in this batch.

Private source, cache, frozen configuration, predictions and hash-bound receipts are under `~/.buzz-multiagent/calisthenics-expert/offline-corpus/coverage-first-20260909/dynamic/pike-push-up-v1/`. Results are in `predictions-v1/`. Media is not committed. Runtime is desktop MediaPipe reference, not Android equivalence evidence.

## Implementation and verification

Pike geometry supplies a small shared elbow-cycle timing machine, also used by the existing dip experiment. Missing tracking and timestamp discontinuities reset unfinished cycles. There is no change to app UI, camera, installed binary, or existing movement settings.

- Controller observed missing-type RED before production implementation.
- First implementation build failed on nullable JSON output typing; corrected before replay.
- Ten new Pike tests passed, including real static JVM entrypoint, synthetic positive, stationary negative, geometry confusions, and interrupted-cycle checks.
- Full shared JVM integration: **772 tests, 84 suites, zero failures/errors/skips**, process exit zero.
- Five cached dip replays after the timing extraction were **byte-identical** to their previous prediction files, not merely equal final counts.
- Tracked diff whitespace check passed. Python verification was not rerun for this Kotlin-only production change; prior Python results are not presented as fresh evidence.

## Next breadth decision

Keep this as developmental pike coverage, with early endpoint timing explicitly unresolved. Prefer a second usable source or a different source-ready movement over repeated tuning to this single GIF. Existing hanging-raise crop failures and unavailable transition-capable hold footage remain blockers on those specific sources, not evidence of supported movements. Both September 7 reuse reports remain planning inputs; their comparator and dataset decisions are unchanged.

Evening self-testing uses the existing installed app. This new offline experiment has not been added to its movement selector.
