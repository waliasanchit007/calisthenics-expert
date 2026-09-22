# ADR-0007: Overlapped preparation and inference in the exploratory viewer

- Status: Accepted for software implementation by the owner's instruction to proceed, 2026-09-23.
- Scope: the ADR-0006 exploratory viewer only — `ViewerSession`, `ViewerMovementCoordinator` and the
  activity that joins them. The formal study harness, its compositions, telemetry, exports and
  evidence are unchanged.
- Amends ADR-0006 in one place: its rule that "the raw live observation retains its 250 ms freshness
  cutoff" is kept for display and replaced for analysis (decision 2).

## Context

The live viewer produces ~10 poses per second from a camera that offers ~30 frames (ledger Update
51). The rate is set by preparation (median 35.7 ms) and inference (41.3 ms) running in series with
one frame in flight, so every third frame becomes a pose; our own locks cost 0.2 ms. At that rate the
frozen knee-raise engine cannot resolve its short lowered dwells (Update 46) and HSPU transitions
skip the band between its gates (Updates 47, 49).

Emulating candidate designs with the measured durations (Update 53) found that overlapping the two
stages lifts the rate to ~23 fps and takes the emulated knee raise from 0–1 to 5 of 6 and HSPU from 2
to 4 of 5. A sensitivity check (Update 54) then found that overlap raises latency past the viewer's
250 ms freshness cutoff for ~2% of results, and that withholding them collapses the knee raise back
to 1.5 of 6 — each withheld result leaves a gap wider than the config's 100 ms capture-gap limit.
Both decisions below are therefore required together.

## Decision 1 — overlap preparation with inference

- `ViewerSession` gains a pipeline mode, `SERIAL` or `OVERLAPPED`. The viewer uses `OVERLAPPED`.
  `SERIAL` is today's behaviour, kept unchanged as the test reference and as a one-line fallback.
  Every trace row records the mode.
- **Exactly one inference is in flight.** The inference port already enforces this; it is unchanged.
- Every admitted offer is prepared on the offering thread, including while an inference is running.
- A prepared frame is submitted at once if inference is idle. Otherwise it waits in a **one-slot
  mailbox**. A newer prepared frame replaces the waiting one, which is discarded, counted as
  `superseded`, and never submitted. There is never a queue: at most one prepared frame waits and it
  is always the newest. This makes ADR-0006's "never queue old frames" structural rather than
  incidental.
- When an inference completes and a frame is waiting, that frame is submitted from the completion
  path — the port's single-thread callback executor, whose slot is released before the callback
  runs. Which thread submits is decided under the session lock, so two threads can never submit.
- Backend timestamps strictly increase. A waiting frame whose millisecond timestamp is not newer than
  the last one submitted is discarded, never submitted.
- Each result stays paired with its own frame.
- Stop, close and failure clear the mailbox. A late completion neither publishes nor submits, and a
  port rejection that races close is benign.
- The five-stage timing partition (Updates 48 and 50) stays exact per frame and is still published
  once, at completion. `submit_gap_ms` now includes the time a frame waits in the mailbox — the one
  stage overlap adds.

## Decision 2 — analysis admission is separated from display freshness

- **Display** freshness stays at 250 ms: the preview overlay, the panel and the session's stale
  status are unchanged.
- **Analysis** receives every completed result exactly once, in capture order, provided it is
  delivered before the coordinator's existing result deadline, 500 ms after its capture
  (`RESULT_DEADLINE_NS = MAX_CAPTURE_GAP_NS + SAMPLE_FRESHNESS_NS`). A result younger than that is
  late, not lost. Beyond it the coordinator has already declared a result timeout, so the result is
  withheld as before.
- Analysis takes results from the completion stream itself, not from the latest-state snapshot
  re-read at display time, so display staleness can no longer delete an observation before analysis
  sees it, and a busy main thread cannot conflate two completions into one.
- Everything else ADR-0006 requires of the analysis feed is unchanged: raw results only, never a
  held or display pose, no interpolation, duplicates and backwards timestamps rejected, and tracking
  gaps still declared at the same deadline.
- Two counters make the relaxation visible: `analysis_late` (admitted to analysis at an age of 250 ms
  or more) and `analysis_expired` (withheld at 500 ms or more).
- This changes nothing about any recorded session: the serial pipeline has never delivered a result
  past 250 ms (session `d39f3f2b`: 0 of 1144, p99 218.9 ms). It matters only once overlap raises
  latency — emulated p50 208, p99 257, max 287 ms, none past 500 ms.

## Verification before any live attempt is scored

1. Unit tests for each invariant above, including: one in flight under concurrent offers and
   completions; the mailbox keeps only the newest frame; submissions strictly ordered; close clears
   the mailbox and blocks late submission; the partition sums exactly with a mailbox wait; every
   completion reaches analysis once; a 260 ms-old result reaches analysis but not the display; a
   result at 500 ms reaches neither. `SERIAL` passes the existing suite unchanged.
2. One device session in `OVERLAPPED`, read with `tools/stage_profile.py` — pose rate, the partition,
   `superseded`, `analysis_late`, `analysis_expired` — with the device thermal state noted at start and
   end, because overlap roughly triples preparation work.
3. Re-run the pipeline emulator with that session's measured durations and latencies instead of the
   serial ones, and pre-register the resulting count prediction in the ledger.
4. Only then is a live attempt scored, against that prediction.

## Not changed

Engines, configs and thresholds; the camera configuration, model, variant and delegate; ADR-0006's
collection, retention, attestation and debug-session rules; and the formal study gates.

## Consequences

- Preparation runs for nearly every camera frame instead of one in three: about three times the
  preparation CPU, more heat, and possible throttling that the emulation did not model.
- Capture-to-result latency rises by roughly 28 ms at the median; the overlay nonetheless refreshes
  about 2.3 times as often, so the pose on screen is on average fresher.
- The analysis path is no longer governed by the display's freshness rule. Anything later than the
  existing 500 ms deadline is still withheld.
- Rollback is the `SERIAL` mode, which is kept, tested and one constructor argument away.
