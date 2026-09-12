# Handstand landmark-identity checkpoint

Date:2026-09-09. Exploratory desktop evidence, not Android deployment, technique assessment, or supported handstand counting. Branch codex/movement-viewer at a690a351569cb8c76aaaece47e1398c9d0e7836c.

## Outcome

The saved hand-supported inversion sequence exposes **arm/leg identity confusion**, not merely missing landmarks. A fixed180-degree preprocessing experiment corrects some frames but fails others. Do not ship an always-rotate change or relax confidence thresholds from the earlier availability gain.

Controller independently inspected raw/original/mapped-rotation panels, including full-resolution frames at12.236s and28.370s:

- At28.370s, the original right-wrist landmark16 is on an elevated leg at approximately(770.9,532.6) pixels; ankle28 is near a supporting hand at(835.5,893.5). Their visibility scores are0.942 and0.976, and presence0.999 and0.973. High scores therefore do not establish correct joint identity.
- The rotated-input result, mapped back without exchanging anatomical left/right indices, places wrist16 near the hand at(819.6,916.2) and ankle28 near the foot at(752.1,552.7). This is an observed improvement on that frame, not a whole-clip accuracy claim.
- At12.236s both versions contain substantial misplaced landmarks. A more complete skeleton can still be wrong.
- Rotation introduces complete pose loss at15.470s and18.670s (original returns33 landmarks, rotated returns0), and recovers a returned pose at25.136s (0 to33). These are independently checked cache counts, not declarations that every returned landmark is correct.

The source includes handwalking and leg motion. These observations do not establish stationary-hold duration or form validity. Visible landmarks were checked qualitatively; no independently annotated pixel-coordinate ground truth or accuracy threshold was invented.

## Replay and evidence checks

Claude-work generated21 deterministic samples before viewing overlays:12 evenly spaced inversion samples,3 explicitly enriched samples from the prior missingness interval, and entry/exit/upright controls after endpoint deduplication. This is not a uniform accuracy sample. Three panels preserve raw pixels beside both overlays; visibility-only rendering is distinct from the prior completeness metric requiring visibility, presence and in-frame coordinates.

Controller verified all21 selected native timestamps using exact Fraction arithmetic. The temporary renderer uses float rounding, but its selected timestamps agree exactly for this file; this does not authorize replacing the canonical timestamp implementation. All21 recorded pixel comparisons reported exact180-degree rotation with zero pixel difference, consistent with the previous whole-sequence derivative verification.

Both original and rotated inputs were re-extracted once using the unchanged canonical extractor, pinned model and settings. Controller independently validated cache bindings and compared every complete pose record, including confidence fields:

| Source | Frames | Different records from previous run | Pose-stream SHA256 |
|---|---:|---:|---|
| Original |1499|0|21d2b741ca9f5fa11b23c52f2047319bfd1344e8c8183edbea2cb31f477f724e|
| Fixed180 |1499|0|44aca02c82cbd9e0990e0c1af6564bbe50303b2429a7a751fa1b57bf7b210d4f|

This is two identical runs per orientation on one desktop environment, not universal determinism or mobile equivalence.

Fresh controller tests:74 reporting/cache/scorecard tests passed in0.40s. The frozen271 source/test files were unchanged. No new Kotlin or device-run claim is made.

## Other track and product alignment

Fresh MM-Fit w02 acquisition remains blocked: three bounded official-server requests returned504 and transferred no additional bytes. The recoverable partial remains1,888,735,192 of2,503,286,677bytes, unadmitted. Resume it rather than redownload; validate published MD5fc468d615d6924afc94650f92fe4dbca and full size before raw inspection or inference. Fresh lunge/squat evaluation is not complete.

Both September7 reuse research documents remain planning inputs. This handstand diagnosis supplements, rather than replaces, the unresolved front-lever/back-lever/planche dataset rights and representation work. Existing Google/PoseRAC comparison results are not rerun; RepNet remains conditional. The current objective is replay-driven coverage without another owner workout, not adding unsupported movement names to the UI.

## Next bounded action

Use the newly saved joint-identity failures to test one framing/preprocessing hypothesis on the same footage before app changes. Freeze the transform before inference, retain original and fixed180 controls, check raw joint locations as well as availability, and include entry/exit/upright controls. Do not choose the better orientation independently per frame using retrospective labels. No new counter or technique claim follows until its input representation is defensible.

Private artifacts:offline-corpus/handstand-location-20260909. Exact agent report and controller recovery ledger:.superpowers/sdd/2026-09-08-frozen-validation-next. Raw/source hashes and licensing are retained in the preceding September8 checkpoint. No commits, pushes, PRs, merges, tags, device operations, uploads, paid credits or automation changes were made.

Controller did not accept the worker's initial claims of anatomical left/right swap confirmation or its5/5/11 completeness tally as location wins/losses. The first report also called an elevated leg at12.236s a raised arm. One bounded report-only correction completed in claude-handstand-location-report-v2.md; the original is preserved. This checkpoint's directly verified observations control acceptance. Cross-orientation disagreement alone cannot establish which estimate is anatomically correct. Diagnostic temporary images were also generated under /tmp; no cleanup was performed.

Residual interpretation limits in the worker's v2: only the three deliberately added missingness samples are selected from failure evidence; the independently evenly spaced28.370s sample happens to fall in that interval and is not itself selected because of failure. Rotation improving those samples is an observation, not guaranteed by the selection rule. Pixel-transform equality and pose replay equality check different properties; one is not a substitute for the other. No third review round was opened, and these overstatements are not adopted as project conclusions.
