# Autonomous feedback: claim-specific evidence, not owner-supplied workouts

Owner correction, September10: source acquisition and the learning loop are the
orchestrator's responsibility. Do not turn unavailable perfect footage into a
request for the owner to demonstrate every movement. This is an offline process
correction, not authorization to deploy, upload, accept access agreements or
relax privacy/security. Existing September7 reuse reports remain active inputs.

## Decision

Keep the source/rights gate; replace the all-or-nothing usability decision with
explicit claim-specific admission. Material is retained for what it can establish:

| Material | Permitted evidence | Not established |
| --- | --- | --- |
| Licensed still, independently visually labelled | Visible posture, joint identity/occlusion, confusion between visible arrangements | Rep completion, support force, hold duration |
| Partial or noisy licensed sequence | Observable phases and missingness; abstention/uncertainty in occluded intervals | Missing endpoints, complete cycles across unobserved gaps |
| Usable complete sequence | Approximate independently labelled event boundaries, timing and confusion behavior | Generalization beyond tested sources/conditions |
| Synthetic landmark sequence | State transitions, continuity, dropout and boundary behavior | Real detector accuracy or anatomical validity |

No synthetic extension, repeated GIF loop, teacher-model agreement or aggregate
count turns into independent human gold. Label unknown intervals explicitly.
Inspect raw media before predictions; cache once and replay existing common Kotlin
logic. Do not create another framework or classifier for each evidence tier.

## Actual first pass, not a promise

Three primary-source licensed original stills were acquired, hashed, raw-inspected
and independently passed through fresh desktop MediaPipe IMAGE instances. Each
returned33 landmarks;12-body-joint confidence/bounds coverage was8,9,10 respectively.
These counts are availability, not landmark correctness or class recognition.

- [Planche.jpg](https://commons.wikimedia.org/wiki/File:Planche.jpg), Jonathanfv,
  CC-BY3: complete nearer-side shoulder/elbow/wrist/hip/knee/ankle observations;
  qualitative overlay broadly follows that side. Far-side uncertainty retained.
- [Full planche.jpg](https://commons.wikimedia.org/wiki/File:Full_planche.jpg),
  Mrplr, CC-BY-SA4: nearer-side points broadly follow the photographed posture;
  opposite-side arm uncertainty and camera tilt remain. No timed-hold claim.
- [Back lever](https://commons.wikimedia.org/wiki/File:Back_lever_being_performed_outdoors.png),
  Muscleups, CC-BY-SA4: retained as a hard occlusion case. Model ankle28 is placed
  on the wooden post above the body, not on a photographed foot, despite passing
  confidence/bounds gates. A returned high-confidence skeleton is not trustworthy
  by itself. This case must not become a back-lever positive pose oracle.

All source metadata, provisional raw labels, original media, landmark outputs and
diagnostic overlays stay outside Git in the private posture-bank-20260910 corpus.
The bounded analysis exited0; no production changes or full-suite reruns occurred.
IMAGE-mode inference is separate from VIDEO caches and does not prove Android
equivalence. It was used to screen stills, not synthesize temporal observations.

## Claude consultation: accepted and rejected

Fresh claude-work read-only consultation completed successfully. Accept the
material-tier/claim-tier distinction and license-first publisher enumeration.
Reject proposed0.70/0.95/20 acceptance constants: unsupported thresholds. Also
reject the claim that our prior reports omitted observability qualifications;
they explicitly labelled the dip/pull-up controls weak/unobservable. A broad
all-engines/all-clips replay is not today's goal and risks repeating old work.
Only run new confusion cells that answer a concrete unresolved question.

New candidate route: institutional Open Images/Open Beelden collections, inspect
each item's rights/content before retrieval. Internet Archive uploader license
claims require provenance, not blind trust. No content match is yet verified.
Controller rechecked official static-skill repositories: public keypoints still
lack an explicit observed reuse grant; RGB source images are request-gated.
UCF101-DS has an explicit noncommercial-research notice, not automatic clearance
for eventual product training. No restricted data was ingested.

## Next execution packet

1. Muscle-up first packet completed: froze11 raw-inspected keyframe labels before
   inference, then reused the existing desktop VIDEO extractor on31 original-PTS
   GIF frames. Pose returned25/31. Left arm passed confidence/bounds18/31; right
   arm21/31. Neither number measures joint correctness. At frame12 both arm checks
   pass but the overlay places the arm skeleton on the athlete's shorts while the
   actual upper body is cropped; frame15's right-arm check similarly passes. The
   support endpoint remains unknown. Keep this as a cached false-tracking case;
   evaluate skeletal identity/geometry consistency without fabricating the top
   endpoint or lowering confidence. No muscle-up counter exists or was executed.
2. Search only a concrete new primary-source family for the missing endpoint;
   do not repeat the exhausted Commons keyword searches or bypass blocked hosts.
3. Continue planche/back-lever posture evidence on the acquired sources while
   temporal footage is unavailable. Near-side visibility can support narrower
   measurements; occluded joints remain unknown. Do not require all33 for every
   observation, and do not remove joints needed by a particular measurement.
4. Each executable iteration ends with source/labels/config hashes, prediction
   trace, misses/extras/unknowns and one specific next correction. Reuse cached
   inference; run targeted regressions for code changes, not blanket reviews.

No workout or video delivery is requested from the owner. Escalate only an actual
new permission, cost, release or material product decision. The old daytime
automation remains paused; this document does not imply background execution.
