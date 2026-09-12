# Handstand observability and plank evidence correction

Date: 2026-09-08. Exploratory offline continuation on codex/movement-viewer, HEAD a690a351569cb8c76aaaece47e1398c9d0e7836c. No application behavior changed.

## What advanced

We acquired a licensed handstand/hand-walking sequence, froze provisional raw observations before inference, and tested the existing MediaPipe pipeline on the original and an exactly rotated lossless derivative. We also withdrew an invalid supported-plank comparison. No new owner workout was needed.

### Handstand: a concrete saved failure case

[Solodkyi Serhii part 1](https://commons.wikimedia.org/wiki/File:Solodkyi_Serhii_part_1.webm), attributed to Fenix1000 under CC BY-SA4.0, contains preparation, sustained hand-supported inversion with stepping and leg motion, and return to feet. This is useful supplementary handstand evidence, not stationary-hold or technique validation. It does not replace the front-lever/back-lever/planche priorities in the two September7 research documents.

Controller independently retrieved the per-file Commons metadata and verified byte count, published SHA1 and local SHA256. The original has1,499 decoded video frames. All original native PTS matched the canonical cache. Audio was not processed.

The raw clearly inverted interior was frozen at9.0–44.5s with separate uncertain entry/exit brackets, before any pose extraction. The full clip was inspected at approximately0.5s intervals; labels remain provisional, not frame-exact or coach gold.

| Availability during inverted interior (1,065 frames) | Original | Fixed180-degree rotation |
| --- | ---: | ---: |
| Any pose returned | 901 (84.6%) | 974 (91.5%) |
| Both arm chains pass existing confidence/coordinate checks | 692 (65.0%) | 766 (71.9%) |
| Twelve shoulder/elbow/wrist/hip/knee/ankle landmarks pass | 644 (60.5%) | 738 (69.3%) |

The rotation comparison used the same model, settings and full temporal sequence. Before rotated inference, every one of1,499 decoded RGB frames was verified exactly equal to the original rotated180degrees; all PTS were equal. It is not a codec-loss or timestamp-shift comparison.

This is one paired development experiment, not repeated-run statistics or anatomical-accuracy measurement. Better confidence/completeness does not prove better joint locations. Upright context remained pose-present in both variants, but arm completeness worsened by one frame in each upright window. No per-frame best-orientation selection, threshold tuning or mobile preprocessing change occurred.

**Decision:** retain the new source/cache and investigate inversion handling with paired replay, raw landmark-location checks and transition cases before implementing a handstand analyzer. Do not simply rotate the camera feed or claim reliable hold timing: substantial gaps remain.

### Plank: withdraw the comparison, not the original artifacts

The historical Core side-plank labels described forearm support with elevated hips. Controller and Codex Terra independently inspected the raw frames: both labelled interiors instead show lying on the side on the mat, with leg movement. The labels contradict their own definition.

The reported47.087s outside labels is mechanically correct interval arithmetic, but is **not an accepted false-positive-duration score** against those invalid labels. The current code explicitly measures near-horizontal body-line geometry; it does not establish support/elevation.

The existing hash-bound predictions were reprojected through the existing reporter without labels. The new current scorecard has38rows:17 exploratory comparisons,18 unlabelled,3 unmeasured. The affected row is now UNLABELLED. Original labels, predictions and historical reports remain unchanged, with a hash-bound rejection record. No post-prediction replacement labels were manufactured.

**Decision:** no threshold correction from this case. A future support-sensitive test needs separately frozen elevated-positive and flat-negative evidence; geometry straightness alone does not resolve the distinction.

## Acquisition and research continuity

The existing MM-Fit distribution became reachable but remains intermittent. Its w02 original is2,503,286,677bytes. A resource-only pre-content amendment tried the smallest unused source w17 (742,972,491bytes); three bounded file requests returned504. The recoverable w02 download then resumed with HTTP206, reached1,888,735,192bytes, hit its600s bound, and its next resume returned504. The partial file is retained as .download, not admitted or decoded;614,551,485bytes remain. No fresh dynamic evaluation is claimed.

Claude-work inspected two additional explicitly licensed Commons clips. Part1 yielded the useful handstand sequence; part4 showed juggling. Bounded sampled inspection did not establish the three requested lever/planche holds. Absence of apparatus alone cannot exclude a floor planche, and sampled frames are not exhaustive native-frame inspection. No blanket claim about Commons availability follows.

Both September7 research documents remain active inputs. Completed Google/PoseRAC comparisons remain completed, not adopted as app replacements. RepNet admission and original BODY25B static-dataset rights/representation remain unresolved. Handstand evidence supplements rather than silently replaces those tracks.

## Verification and next sequence

- Existing canonical extractor completed both clips with exit0; source/model/cache hashes and original PTS were verified.
- Fresh reporting tests:42passed in0.33s. All271 frozen source/test files remained byte-identical.
- Corrected historical scorecard generated successfully; no new Kotlin run is implied by its plank row.
- Claude-work only was used for Claude work; one Codex Terra/high diagnostic task ran in parallel and is complete. No other Claude account, paid-overage option or automatic quota fallback was used.
- No commits, pushes, PRs, merges, tags, device operations, deployment, cleanup or automation changes.

Next: complete checksummed fresh-source acquisition when the server serves the remaining bytes; freeze raw lunge/squat event labels before replay. In parallel, use the new inversion failure case to distinguish orientation, framing and landmark-location errors before any new handstand behavior. No user workout is a prerequisite.

Private records are in offline-corpus/frozen-validation-next-20260908; source search in static-next-20260908; raw plank diagnosis in plank-diagnostic-next-20260908. Exact commands and recovery state are in the ignored .superpowers/sdd/2026-09-08-frozen-validation-next ledger.
