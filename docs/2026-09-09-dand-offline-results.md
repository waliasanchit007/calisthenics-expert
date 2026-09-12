# Hindu push-up / dand: offline development result

## Source-first admission

[Dand (Basic).gif](https://commons.wikimedia.org/wiki/File:Dand_(Basic).gif), revision 884657104, is RickyBennison's own work, explicitly CC BY-SA 4.0. Downloaded original: 640×360, 22,667,107 bytes, SHA-256 `608ae4092b0b4c6ab0cba7b7dc88a5d37e813c6e2ccee44b4161edab4cf03429`. It remains private and is not included in the app or repository. Attribution and rights are in its source record; no endorsement is implied.

Raw RGB inspection before pose extraction identified five high-hip → low-hip/chest-raised support → high-hip cycles in one traversal. This is one person and one GIF, not five independent sources. Frame windows frozen before predictions: 28–36, 56–64, 84–92, 116–124, 144–147. These are approximate raw return windows, not expert gold labels. A pale foreground artifact near frame 12 was retained, not edited out.

Canonical desktop extraction completed with 148 frames, original PTS 0–11.76 seconds. LEFT shoulder/elbow/wrist/hip/knee/ankle all meet the existing confidence/bounds screen in **148/148** frames, including each endpoint; RIGHT meets it in **0/148**. LEFT was selected from availability, before examining numeric geometry or counting predictions.

## Small reusable experiment

The selected-mode counter composes the existing endpoint sequencing with high-versus-low hip position, observable hand-below-shoulder position, extended knee, and extended elbow at endpoints. It does not measure hand contact, force, spinal arch, scapular mechanics, or exercise technique. Image-plane coordinates are pixel-scaled for angles. Tracking loss, invalid timestamps, and gaps discard unfinished candidates.

The development settings were frozen before predictions: LEFT, confidence 0.5, maximum gap 250 ms, two endpoint samples, hip-height difference 0.03 of image height, endpoint elbow 150 degrees, knee 150 degrees. No single-clip tuning followed the replay.

The JVM entrypoint `com.calisthenics.diagnostic.viewer.experimental.ReplayHinduPose` accepts `--posePath`, `--configPath`, `--outputPath`. It reuses the existing strict replay pose-row parser, not pike geometry or pike thresholds. This is offline only; there is no new app selection or installed binary.

## Actual results

| Source | Candidates | Interpretation |
| --- | --- | --- |
| Dand positive: 148 frames | 5 at 2.24, 4.48, 6.72, 8.96, 11.44 seconds | All frames admitted. Five visible cycles, but only three events match the frozen return windows. The final two precede their windows by 320 ms and 80 ms. |
| Pike positive used as dand confusion: 77 frames | 0 | All frames observable/admitted; unlike prior crop-heavy controls this provides useful selected-mode discrimination evidence. |
| Dip control: 379 frames | 0 | 362 unavailable, 17 knee-flexed rejections. This control does not establish general discrimination. |

Strict timing comparison records **3 matched, 2 missed, 2 extra** (precision/recall 0.6). Matching the aggregate count does not erase these timing misses. No held-out or Android accuracy claim is made.

Private reproducible evidence: `~/.buzz-multiagent/calisthenics-expert/offline-corpus/daytime-coverage-20260909/dand-v1/`, including source and raw labels, frozen config, canonical cache, `predictions-v1/` outputs and hash-bound receipts. Controller replay command lives in the ignored daytime ledger directory as `replay-dand.py`.

## Verification and next boundary

Controller observed missing-class RED separately for the engine and executable replay, then GREEN. Seven new tests cover the complete cycle, stationary/pike confusion, interrupted cycles, malformed confidence/knee geometry, real JVM entrypoint, output overwrite refusal, and invalid config.

Full shared JVM integration exited zero: **779 tests in 86 suites, zero failures/errors/skips**. No existing engine or thresholds changed. Tracked whitespace check passed. No fresh Python, Android, device, or release verification is claimed for this Kotlin-only offline addition. No Git commits, push, PR, merge, tags, deployment, recording, or uploads occurred.

Next priority remains another source-ready movement or usable hold sequence rather than tuning this one GIF. The September 7 research inputs and their unresolved rights/representation decisions are unchanged. This result does not unblock cropped hanging raises or establish a new static hold.
