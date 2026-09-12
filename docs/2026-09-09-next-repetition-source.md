# Next repetition source checkpoint — 2026-09-09

Bounded source-preparation sidecar. No production code, Gradle/model/extraction/app
or Git operation was performed. Search stopped after one admitted source-ready family;
the prior inverted-row 403 and historical leg-lift material were not reused.

## Admission

**Family:** pike push-up

**Per-media source:** [Wikimedia Commons — File:Pike Push Ups.gif](https://commons.wikimedia.org/wiki/File:Pike_Push_Ups.gif), revision observed from the page on 2026-09-09. The page identifies the source as the uploader's own work, author/uploader `Danielflefil`, and explicitly states CC BY-SA 4.0 with attribution and share-alike obligations. This is media-level evidence; no site-wide license assumption was used.

**Private artifact:** 500×500 Commons derivative, downloaded only after the per-file license was verified, at 6,523,747 bytes (6.22 MiB), 77 frames, 25 fps, 3.08 seconds, GIF loop. It is retained only under the private dynamic corpus:

`/Users/sanchit.walia/.buzz-multiagent/calisthenics-expert/offline-corpus/coverage-first-20260909/dynamic/pike-push-up-v1/raw/pike-push-ups-500.gif`

SHA-256: `e6381af8cda2e4fd6c27e3e18b46ee97873bcb05978b7503e84499f1b1f01547`

The original 1,080×1,080 GIF listed by Commons is 42.34 MB, so it was not
downloaded under the 15 MB bound. The admitted derivative is the exact downloaded
file whose checksum is recorded above.

## Pixel inspection and frozen raw label

Inspection used decoded RGB pixels and a 10-frame contact sheet from the admitted
artifact; no pose model, landmark extraction, or prediction was run. The person is
fully in frame in the side view, with both hands and both feet visible throughout
the inspected cycle. Wrists, elbows, shoulders, hips, knees, and ankles are visibly
available at the top and lowered endpoints. The torso remains in an inverted-V
configuration; the observed action is a pike push-up, not a standard push-up or a
static pike hold.

One raw cycle is frozen before any model work:

| Label | Frame window | Approx. PTS | Pixel event |
| --- | ---: | ---: | --- |
| `pike_push_up_01` | inclusive 0–76 | 0.00–3.04 s | High inverted-V start, continuous shoulder/head lowering to the lowest visible position, then return to the high inverted-V endpoint |

The GIF repeats this same demonstration; it is one source recording and one
development example, not independent positives or population evidence. The window
must not be expanded, shortened, or relabelled after pose predictions.

### Raw endpoint record v2

The original `pike_push_up_01` cycle label above is unchanged and is not
completion timing. A companion record, derived from actual decoded frame PTS
(`0.04 s` per frame), records observed endpoint windows before any model work:

| Endpoint | Inclusive frames | Decoded PTS | Observation |
| --- | ---: | ---: | --- |
| `TOP_START` | 0–12 | 0.00–0.48 s | Stable high inverted-V start |
| `LOW` | 31–45 | 1.24–1.80 s | Lowest visible shoulder/head excursion with flexed elbows |
| `RETURN` | 67–76 | 2.68–3.04 s | Returned high inverted-V endpoint matching the start |

These are observed windows, not thresholds and not a completion-timing claim.
The actual private source path is
`/Users/sanchit.walia/.buzz-multiagent/calisthenics-expert/offline-corpus/coverage-first-20260909/dynamic/pike-push-up-v1/`;
it differs from the assigned `daytime-coverage-20260909/dynamic` path.

## What can actually be measured

This source is ready for a **single-side, side-view pike-push-up repetition
observability/counting experiment**. A plausible developmental event is the
downward-and-upward excursion of shoulder/head relative to the hand support while
the inverted-V body line remains present. The footage can support image-plane phase,
one-cycle timing, endpoint visibility, and missingness reporting.

It cannot by itself establish anatomical shoulder range, elbow angle accuracy,
scapular mechanics, load/force, technique quality, bilateral symmetry, or
generalization. The repeated GIF and single person also cannot support accuracy
claims. Any later extraction must preserve the frozen window and report whether
far-side joints remain independently observable rather than silently substituting
the near side.

## Evidence boundary

This is a source-ready admission checkpoint, not a validated movement feature. No
model output, pose cache, counter, app behavior, or release claim exists for this
family. Search was intentionally stopped at this bounded evidence checkpoint.

## Minimal reuse proposal — no code yet

Reuse the existing dip elbow-timing engine only as an offline timing skeleton:

1. Keep its frozen raw-window/PTS handling, phase sequencing, gap and
   unavailable-joint reporting, and per-event timing output.
2. Replace dip-specific elbow geometry with pike-specific observations: a
   supported inverted-V body line, hip/knee relationship to the hand-support
   plane, and shoulder/head excursion between the recorded TOP and LOW regions.
3. Treat hip/knee support geometry as an observability gate and diagnostic, not
   an anatomical or technique standard. Preserve near/far-side missingness.
4. Keep the pike family selected-mode and side-view only; retain unrelated
   movement controls and require a separately frozen positive/control set before
   interpreting counts.

No thresholds, model read, extraction, production code, or analyzer admission is
proposed at this checkpoint.
