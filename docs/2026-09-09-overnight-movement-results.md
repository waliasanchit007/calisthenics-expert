# Overnight movement evidence — September 9

**Superseded direction:** the owner replaced this refinement queue with the [coverage-first sprint](2026-09-09-coverage-first-results.md). The old overnight automation is paused. The historical results below remain evidence, not the current next-work plan.

Interim checkpoint; the scheduled run continues until 09:30 IST. This is offline
development work, not a release, a new on-device feature, or an all-movement claim.
The September 7 reuse research and September 9 framing/lunge findings remain
planning inputs. No new workout, deployment or phone access was required.

### Latest verified checkpoint — 23:43 UTC / 05:13 IST

- **Jumping-jack calibration:** a fresh, pre-labelled set improved from 1/9 to 9/9 matched post-practice cycles, with no extras, using an automatic first-supported-return rule instead of a hand-timed practice end. Three complete unrelated-movement controls stayed at zero. Older timing errors remain.
- **Sit-up observability:** setup-only side selection recovered nine candidates where a fixed side produced none. Only four match the frozen timing windows; do not call this nine accurate event detections.
- **Automated feedback:** both changes were evaluated without another workout, using separately frozen raw labels, caches, configurations and prediction receipts. This is the useful product outcome, not a count of newly supported exercises.
- **Limits:** same-recording/person development evidence; no deployment, new movement-family release or form-quality claim. Static-skill source/access and suitable second handstand footage remain blocked after the bounded search. More quota alone is not a reason to invent another task or repeat those searches.

The chronological sections below retain failures and superseded next-step proposals;
the latest completed experiment is described at the end.

## What the fresh recording actually showed

All runs used the already admitted MM-Fit w02 original, unchanged MediaPipe model,
fresh per-excerpt VIDEO state and frozen Kotlin configurations. Controller raw
review froze ten completion windows for each target before extraction. Labels are
assistant development observations, not expert form labels. Athlete independence
is unestablished. The sit-up selected side stayed RIGHT; it was not switched to
obtain a better score.

| Input / selected experiment | Matched | Missed | Extra | Interpretation |
| --- | ---: | ---: | ---: | --- |
| Ten sit-ups / frozen RIGHT v2 | 0 | 10 | 0 | Selected right knee usually fails visibility gate |
| Ten jumping jacks / frozen baseline | 4 | 6 | 0 | Closing-arm predicate has insufficient support |
| Squats / sit-up control | 0 | 0 | 0 | No sit-up candidates on this control |
| Lunges / sit-up control | 0 | 0 | 0 | No sit-up candidates on this control |
| Squats / jumping-jack control | 0 | 0 | 0 | No jack candidates on this control |
| Lunges / jumping-jack control | 0 | 0 | 0 | No jack candidates; many unusable frames limit the evidence |

These are selected-exercise experiments, not automatic exercise recognition.
Four quiet controls do not erase the positive-case misses.

### Sit-ups: observability before counting

Of 1,116 frames, 1,033 are rejected for the selected right knee's visibility;
the right shoulder and hip are not the first failing joints. During the active
1329–1356s interval, 795 of 811 frames fail. The opposite arm/leg-side triple
passes existing numeric/confidence validity on all 1,116 frames, but that is
diagnostic coverage, not independent proof that every joint is located correctly.

This exposes a protocol weakness: copying a fixed side from another view is not
a reliable observability decision. It does not establish that the cycle logic
fails on a clearly visible, correctly tracked sit-up. No retrospective LEFT
result is substituted for the failed frozen RIGHT result. A next experiment
should predeclare a tracking-only side-admission rule before any count outputs;
it must not select whichever side gives the expected number of repetitions.

### Jumping jacks: explain the misses without retuning

All 600 replay rows pass existing geometry validity, which says nothing by itself
about landmark accuracy. The frozen closing rule needs arms at or below 25 degrees
and leg-spread ratio at or below 1.35 for two consecutive samples.

Two missed windows contain one qualifying frame each, not two. The other four
never cross the arm predicate: minima are approximately 27.02, 31.05, 30.96 and
27.86 degrees. Legs satisfy their predicate at those minima. The observed rows
and counter phase explain the misses; thresholds and annotations were not changed.

The additive offline report diagnostic is now implemented and verified. It shows
window-local predicate support and continuity. It is not a second implementation
of the counter, an assertion about internal endpoint state, or an anatomical
quality score. Applying it to the saved predictions gives longest closing-support
runs of 1, 1, 0, 0, 0, 0 in the six missed windows. The four matched windows have
5, 3, 2 and 3 qualifying consecutive frames. All pre-existing report fields remain
identical, including labels, counts, timestamps and provenance bindings.

## Evidence checks and agent reliability

The sit-up excerpt has 1,116 frames and the jack excerpt 600. Every decoded RGB
frame and rational timestamp was independently compared with the original;
hashes, native timestamps, pose caches, model, frozen configuration, predictions,
labels, Kotlin source snapshots and JAR bindings were independently checked.
All six replay runs exited successfully. Scores and reason counts were recomputed
from the durable output, not trusted from an agent summary.

The existing historical scorecard was extended additively to version 7: 40 saved
cases, comprising 24 exploratory comparisons, 13 unlabelled and 3 unmeasured.
The six new reports carry hashes; rebuilding the summary produced the identical
content. These counts are report coverage, not independent subjects or an aggregate
accuracy score. Older scorecard versions and unresolved cases remain intact.

Claude-work's initial sit-up labels were rejected: their seven-cycle count and
claimed long seated spans contradicted the raw contact sheets. The original draft
remains private for audit; only the controller's raw-first replacement was scored.
Spark's inventory contained inaccurate paths and was also corrected before use.
Delegation accelerated preparation, but neither output was treated as authority.

## Other movement coverage: do not count research as a feature

Current evidence inventory (source files and the latest superseding reports checked
again tonight; historical device observations were not repeated):

| Family | Implemented surface | Offline evidence and remaining limit | Device evidence |
| --- | --- | --- | --- |
| Pull-up / push-up | Shared selected-movement experiment in `PullUpExperiment.kt` | Owner debug loop exists; not re-scored as tonight's benchmark | Prior owner-reported successful counts; no new verification tonight |
| Squat | Experimental shared counter and replay | Saved positives/controls exist; knee flexion is not exercise identity | Not established by this overnight batch |
| Lunge | Experimental counter, opt-in contralateral guard and replay | Fresh w02 LEFT extra removed while retaining all ten matched cycles; earlier squat confusion remains | Not established by this overnight batch |
| Sit-up | Experimental counter and replay, opt-in setup-only side selection | Earlier w16 totals 9/9 but only four exact-window matches; fresh w02 fixed RIGHT fails tracking. New setup selection recovers nine candidates with four exact-window matches, detailed below | Candidate not deployed |
| Jumping jack | Experimental counter, replay and opt-in practice band | First-set improvement below; second calibration abort and older event-timing mismatches remain | Candidate not deployed |
| Plank / body-line hold | Projected body-line geometry experiment | Support/elevation is not inferred; invalid historical supported-plank labels withdrawn, not accepted as false-duration evidence | Reliable supported-hold timing not established |
| Handstand | Extractor/framing experiments, no handstand analyzer admitted | One crop comparison improved availability and selected raw alignment; second suitable clip missing | No mobile framing change |
| Dips / inverted rows / pike push-ups / hanging raises | No new analyzer admitted tonight | Suitable licensed positive/control data not admitted; floor leg-lift controls are not hanging-raise coverage | Unmeasured |
| Front/back lever / planche | Research and data-admission work | Access, representation and trustworthy full hold labels remain open | Unmeasured |

This inventory separates code existence, observed replay behavior and live behavior.
It must not be turned into a supported-exercise count. The newer plank-label withdrawal
supersedes the older 47.09-second comparison; that old number is not a current accuracy
claim. The fresh lunge result likewise does not erase older squat confusion.

- Squat/lunge, sit-up, jack and plank experimental code already exists. Tonight's
  fresh failures qualify their evidence; they are not five newly shipped moves.
- Pull-up/push-up owner-reported live success remains recognized. Those sessions
  were not rerun or relabeled as tonight's offline benchmark.
- Prior fixed-framing handstand evidence remains promising but unreplicated on
  a second suitable clip. Existing part 4 footage was not a handstand example;
  a newly admitted small historical clip crops the feet in the original, so it
  was rejected for full-body replication before inference.
- Dips, inverted rows, pike push-ups, hanging raises, front/back lever and planche
  still lack suitable admitted evaluation footage for this batch. A public-domain
  inverted-row source was located, but its public access returned 403. No access
  bypass, signup, or unlicensed substitute was used.
- The static BODY25B dataset still has unresolved license/access/representation
  requirements. No fabricated mapping to MediaPipe 33 was made. RepNet remains
  unexecuted for the separate rights/admission reason; Google/PoseRAC comparisons
  remain completed but not adopted, not proof that their model families cannot work.

## Artifact locations and next direction

Fresh batch verification: `make verify` exited 0 with 1,226 tests passed and one
optional media-regression skip in 41.89s. Reference Ruff formatting/checks passed
across 83 files, strict mypy passed across 21 source files, and fixture/repository
guards passed. The changed reporter separately passed strict mypy and targeted
Ruff check/format. The participant-study gate remains blocked as expected; this
work did not admit a participant study. No fresh Android build or full JVM suite
is claimed, and the skipped media test was not represented as executed tonight.

Eleven new report regressions passed. Before implementation, nine cases failed
for the absent helper; the initial integration test also exposed an unrelated
test-data row-format mistake, which was corrected. Two hand-calculated expected
values were corrected before acceptance. The final tests exercise numeric and
confidence validity, both predicates, inclusive windows, gaps, interrupted runs,
window isolation, input preservation, no-label status and actual report emission.
The controller inspected the exact delta against the saved original untracked
file and independently verified the real saved-video report's unchanged fields.

Claude-work reached its session limit before editing this diagnostic. Codex Luna
completed the two-file implementation; no other Claude account, paid credits or
overage was used. Both substantive Claude preparations and their corrections are
retained in the private record. No commit, push, PR, merge, tag or cleanup occurred.

Private root: `~/.buzz-multiagent/calisthenics-expert/offline-corpus/overnight-20260909/`.
`dynamic/` contains admitted native excerpts, raw label versions, caches, six
replays, independent check scripts, sit-up observability and jack trace diagnosis.
`admission/` records source-rights and suitability results. Media and landmarks
remain outside Git. The ignored overnight SDD ledger records exact commands,
process identities, timestamps and continuation state.

The useful next step is making the same saved-video failures explain themselves,
then testing a predeclared improvement on fresh footage. More review prose,
repeating inaccessible downloads, or inventing another movement class without
usable inputs would not increase real movement coverage.

## Follow-on jack candidate: improvement with a calibration limitation

Eight exact raw/overlay closing samples were checked against native frames and
cached landmarks. Placement looked broadly plausible; some missed endpoints held
the hands farther from the torso. The worker's conclusion about the whole movement
being only a small sway was rejected: closing-only samples cannot characterize the
overhead phase. Color-legend and sample-count errors were corrected separately.

A second w02 jack set was selected and raw-reviewed before predictions: ten
complete cycles, with setup/rest retained. Its 600-frame native excerpt has exact
pixel/PTS correspondence to the parent; pose extraction completed. This is another
set from the same recording/person, not a population-generalization test.

After one Claude-work design challenge and one scoped correction, implementation
of an opt-in offline practice-derived closing band was tested. The opening
requirement, leg requirements and continuity protections stay fixed. Practice
must contain a sustained full opening; a shallow sway cannot define its range.
Practice is excluded explicitly, and controls will use the learned numeric band
without requiring their own practice. Original baseline output stays byte-identical
on the complete saved first-set replay, independently compared after implementation.
The frozen experimental fraction/range parameters are not technique standards.

| Comparison | Fixed baseline | Candidate | Interpretation |
| --- | --- | --- | --- |
| First set, same nine post-practice cycles | 3 matched, 6 missed, 0 extra | 9 matched, 0 missed, 0 extra | Improvement on development footage |
| Second set, ten cycles | 6 matched, 4 missed, 0 extra | Calibration aborted | Not a zero-count result and not a successful replication |
| Whole squat/lunge/leg-lift controls, first learned band | Previously zero | Zero on all three | No new extras on these controls |
| Older w01 positive, first learned band | 8 matched, 2 missed, 2 extra | 8 matched, 2 missed, 2 extra | Existing timing/matching failures remain |
| Retrospective transfer of the unchanged first band to second set | 6 matched, 4 missed, 0 extra | 10 matched, 0 missed, 0 extra | Useful transfer diagnosis, NOT second-set self-calibration success |

The learned first-set closing band is42.67472942273977 degrees; opening remains110.
This value was derived from practice, not hand-picked or swept against the count.
Both original ten-window labels remain unchanged. A separate nine-window projection
excludes practice. The fair comparison also explicitly removes the same prefix
through2396s from both event streams: one baseline event, no candidate events.
Native reports and common-domain comparisons are both retained.

The second practice window ended at2460.8s. Its final two observations measured
33.75 and44.56 degrees, while its derived closing band would be40.03. The last
observation therefore fails the required closed endpoint; both leg predicates
pass. The predetermined window was not shortened after this result. No prediction
artifact or report was emitted from the failed calibration, and no second learned
config was admitted. Applying the first set's already-frozen numeric band to this
second set is explicitly a retrospective, same-recording transfer experiment.
It separates a practice-boundary limitation from counting performance; it cannot
establish automatic calibration reliability or generalization to another athlete.

All successful reports, cache/model/source/config/label hashes, Kotlin snapshots,
JAR bindings, frame alignment and event metrics were independently recomputed.
Private evidence is in`dynamic/jack-endpoint-design/runs-v1/`, especially
`summary.json` and`controller-evidence-verification.json`. No app/default change
or deployment occurred. Do not promote the candidate on these numbers alone.

### Implementation checks and process correction

Claude-work implemented the six-file offline candidate but did not follow the
requested initial test-first ordering or pre-edit backup rule. Its initial failures
were fixture mistakes, not missing-behavior RED evidence. This is recorded as a
process failure, not retroactively called TDD. The controller reconstructed exact
before-images from successful edit records; Kotlin originals match the earlier
replay's source hashes and Python originals match the prior checkpoint.

Two behavior-removal mutations then failed by the intended named assertions:
discarding the learned band broke two tests, and removing the required arm-opening
condition broke the shallow-practice rejection test. Both were restored byte-for-byte.
The unchanged counter body and every prior Python test statement were preserved;
two new baseline-absence assertions were appended to the existing integration test.

Controller inspection also found the reporter trusted inconsistent calibration and
evaluation metadata. One scoped correction added boundary/threshold/flag validation:
17 newly failing cases were observed before the fix, then all34 reporter tests passed.
The original event matcher and its overlap rejection were reused, not reimplemented.

Fresh final batch verification exited0: **1,249 Python tests passed, one optional
media-regression skip**, in53.41s; Ruff formatting/checks passed across83 reference
files; strict mypy passed21 reference source files and separately the changed
reporter. The first full attempt correctly failed on the new test file's formatting;
only that file was formatted and its AST verified unchanged before the passing rerun.
Focused Kotlin JVM execution produced35 tests, zero failures/errors/skips.
No full Android/device build or full JVM suite is claimed. Participant-study admission
remains blocked as expected. No paid credits, other Claude account, commit, push,
PR, merge, tag or cleanup was used.

The next practical questions are how to detect practice completion without a
hand-timed final frame, and how to choose a visible sit-up side before counting.
Neither should be solved by selecting the side/window that happens to produce the
expected repetitions. Existing side/practice machinery should be reused first.

## Sit-up setup-only side selection: counting recovered, timing still qualified

A new second w02 set was inspected in all eight raw contact sheets before model
inference or candidate outputs. It contains nine complete return-to-recline cycles
and one final ascent that remains seated. Those labels were frozen independently
of the counter, along with a 1372.75–1373.75s setup window before the first ascent.
The 1,140-frame excerpt preserves every decoded RGB frame and rational timestamp
against the original. A failed empty first excerpt was retained and corrected
before inference; event windows and setup parameters were not changed.

The opt-in replay now uses existing measurement validity to select one side from
that setup window. Both sides eligible preserves the configured preference; neither
eligible aborts. The selected side stays fixed afterward, and no setup frame is
fed retrospectively into the counter. Existing measurement/counter bodies and the
default fixed-side path are preserved. This is not automatic exercise recognition.

| Comparison | Result |
| --- | --- |
| Fresh set, frozen RIGHT baseline | 0 candidates; all 1,140 frames rejected for low confidence |
| Setup-only admission | LEFT selected: 30/30 usable setup samples, RIGHT 0/30; preference was RIGHT |
| Fresh set, admitted fixed LEFT | 9 candidates; 4 exact-window matches, 5 missed windows and 5 unmatched early events |
| Full squat control with the admitted side | 0 candidates; geometry available on all 906 frames |
| Full lunge control with the admitted side | 0 candidates; geometry available on 1,124/1,146 frames |
| Full floor leg-lift control with the admitted side | 0 candidates; geometry available on all 180 frames |

All 1,027 post-setup frames have usable geometry. There are no excluded prefix
events in either comparison. The five unmatched events precede the corresponding
frozen windows by about 217, 150, 150, 117 and 17ms; the other four fall inside.
These are timing mismatches, not evidence of five additional physical repetitions.
The raw labels were sampled at quarter-second intervals and are not coach-validated
frame-exact truth. Do not retune the counter or widen these labels post hoc merely
to obtain nine matches. The result supports the observability correction while
leaving event-definition/timing precision unresolved. The original first-set
RIGHT failure remains unchanged, not replaced by a retrospective LEFT score.

Five separately selected raw/overlay samples show broadly plausible shoulder/hip/
knee placement without an obvious limb-class swap. They do not establish dense
joint accuracy. This remains same-person/source development evidence; no phone
behavior, default side, practice UX or supported-exercise claim was promoted.

### Verification and bounded process corrections

The tests-only phase was verified before production changes. Initial Kotlin RED
was the missing selector symbol; after correcting test-fixture issues, Python RED
was 17 intended failures with 11 passing cases. A scoped controller inspection
then found missing window-coverage and strict metadata-type guards. Four new Kotlin
cases failed before that correction; five Python cases reproduced absent guards
or wrong exception types. The other malformed-input cases already rejected through
existing checks and are not claimed as newly failing regressions.

Final focused results: **54 Kotlin tests, zero failures/errors/skips; 38 reporter
tests passed**. Fresh full `make verify` exited0: **1,277 Python tests passed, one
optional media skip**, in52.76s; Ruff passed83 reference files, strict mypy21 source
files, and separate reporter/test Ruff plus strict reporter mypy passed. The full
gate includes hermetic non-delivery Android build checks; this is not a delivery
APK or device validation. Participant-study admission remains blocked as expected.

The controller initially overlapped that full suite with replay. The suite's
missing-model tests temporarily move the local model and invoke Gradle, so those
operations are not disjoint: the second completed baseline's post-run verification
hit the temporarily absent file. The model was restored with the pinned hash; the
controller serialized the remaining work and revalidated the two existing baseline
artifacts without repeating their replays. This was a scheduling error, not a model
or movement failure. Future full-suite runs must own both Gradle and the local asset.

All six final replay receipts, model/cache/config/label/source/JAR hashes and event
metrics were rechecked from durable files. The original first-set baseline JSONL
is byte-identical. Existing measurement/counter code and all18 original Python
test/helper function bodies were preserved. Worker prose overstated some test
counts; the actual XML and test logs govern the figures above.

Private results: `dynamic/situp-set2/runs-v1/summary.json` and
`controller-verification.json`; original and corrected preparation records remain.
Claude-work's older consultation-only repository default was explicitly reconciled
with tonight's newer user delegation before implementation. No other Claude account,
paid credits, commit, push, PR, merge, tag, upload, cleanup or phone action occurred.

Next useful experiment: causal practice completion for the jack candidate, without
a hand-timed final sample. Keep the strict fixed-window profile unchanged, define a
separate opt-in rule before a fresh raw-labelled set, and preserve failures. Further
sit-up threshold changes are not justified solely by sub-quarter-second label offsets.

## Causal jumping-jack practice completion — verified fresh third set

The separately named offline `practice_search` profile ends at the first prefix
accepted by the existing, unchanged practice calibrator. It still requires a
sustained joint arm/leg opening at the original base thresholds, a supported
closed start/return, usable tracking and continuity. It never uses later counts
or a future final range to decide when practice ended. This is an experimental
observable condition, not a claim about a technically correct repetition.

The third w02 source was raw-reviewed before inference: ten complete cycles.
Original ten-event labels were retained; a separately frozen projection excludes
only the first practice cycle. Search start2516s, deadline2520s and learning
parameters20degrees/.15 were fixed before outputs. Every decoded RGB pixel and
rational timestamp in the630-frame native excerpt matched the admitted parent.
No video/model/label fitting followed the results.

Calibration completed at2517.6s, before the next retained event window, and learned
an exact closing threshold40.048637260766384degrees. The opening threshold remained
110degrees; leg thresholds, confidence, sample support and gap rules were unchanged.

| Comparable input / mode | Matched | Missed | Extra |
| --- | ---: | ---: | ---: |
| Third set, full ten-event baseline | 1 | 9 | 0 |
| Same nine post-practice cycles, baseline | 1 | 8 | 0 |
| Same nine post-practice cycles, causal calibration | 9 | 0 | 0 |
| Full squat / exact learned configuration | 0 | 0 | 0 |
| Full lunge / exact learned configuration | 0 | 0 | 0 |
| Full floor leg-lift / exact learned configuration | 0 | 0 | 0 |
| Older w01 positive / exact learned configuration | 8 | 2 | 2 |

Neither side of the common-domain comparison had excluded prefix count events.
The old w01 timing mismatch remains; no clip-specific refitting or label widening
was used. This same-source result does not establish athlete-independent accuracy.

The earlier first-set baseline and strict-calibration JSONL remain byte-identical.
The second set's strict fixed-window invocation still aborts for its original
closed-endpoint failure and writes no predictions/report. It was not silently
converted to a successful causal result or scored as zero.

### Verification and design judgment

The tests-only worker produced intended RED before production changed. Controller
inspection corrected two faulty test fixtures and an invented report-field
expectation before granting implementation. Fresh results: **40 Kotlin tests,
zero failures/errors/skips; 36 reporter tests passed**. All20 prior Python test/
helper function bodies and the complete old Kotlin measurement/calibrator/counter
source were preserved around the additive helper.

The serial matrix exited0; all eight successful run receipts, exact source/cache/
model/config/label/JAR hashes and event comparisons were independently rechecked,
alongside the expected strict abort. Full verification then ran separately and
exited0: **1,279 Python tests passed, one optional media skip**,53.34s;83 reference
files formatted/linted and21 reference source files passed strict typing. Separate
reporter lint and strict typing passed. Its formatting check still reports
pre-existing wrapping differences, recorded rather than described as clean.
The model asset was restored with its pinned hash after the hermetic build gates.
No delivery APK or device behavior is claimed.

Claude-work supplied one bounded design challenge. The controller accepted its
invalid-input/label-boundary concerns but rejected a proposed comparison with a
future final range: that would defeat causality. The rule may produce a different
band from a longer practice window; that limitation is explicit. Terra implemented
the scoped change because it crosses Kotlin replay and Python receipts; no second
Claude account or routine independent implementation-review loop was used.

Private evidence: `dynamic/jack-set3/runs-v1/summary.json`,
`controller-verification.json`, frozen inputs and original logs. No commit, push,
PR, merge, tag, deployment, upload, cleanup or new workout occurred.

### Big-picture direction after this batch

The payoff is a repeatable way to reproduce misses, test one explanation and reject
unsupported success without another owner workout. Keep selected-movement counting
distinct from automatic exercise recognition and technique assessment. Next meaningful
progress needs genuinely new usable footage/conditions or a separately authorized app
integration of these experiments—not additional thresholds fitted to these same sets.
Do not repeat the exhausted source search or rename non-target footage to fill coverage.
