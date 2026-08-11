# Calisthenics Movement Intelligence — Product & Technical Direction

## 0. Objective

We want to build a cross-platform Android/iOS calisthenics system that can use an ordinary phone camera to understand human movement.

The long-term product is not just a rep counter.

The long-term goal is:

> Build an increasingly capable on-device movement intelligence system that observes calisthenics movements, converts them into reliable structured data, measures technique and progression, and eventually supports personalized coaching.

However, the most important assumption is not yet proven:

> Can a normal monocular phone camera produce a sufficiently reliable representation of calisthenics movement under realistic conditions?

We must validate that before investing heavily in rep engines, custom ML, or AI coaching.

The immediate work should therefore start with a **Phase 0 feasibility and measurement study**.

---

# 1. Core Technical Philosophy

The preferred hierarchy is:

```text
Deterministic measurement
        ↓
Small on-device ML
        ↓
Backend ML where justified
        ↓
Generative AI / LLM
```

Do not use a large multimodal LLM to continuously watch workouts if the same problem can be solved locally.

The ideal runtime architecture is:

```text
Camera
   ↓
Capture Quality
   ↓
Pose Estimation
   ↓
Pose Reliability
   ↓
Normalization / Canonicalization
   ↓
Movement Representation
   ↓
Deterministic Movement Analysis
   ↓
Optional Small Temporal ML
   ↓
Workout / Progression Intelligence
   ↓
Optional LLM Coach
```

Most core workout functionality should eventually work:

* offline
* on-device
* with near-zero inference cost
* without AI tokens
* on both Android and iOS

---

# 2. Pose Is an Intermediate Representation, Not the Product

The initial pose backend should be **MediaPipe Pose Landmarker on both Android and iOS**.

Do not mix Apple Vision, ML Kit, MediaPipe, etc. across platforms initially because different landmark definitions and error distributions would contaminate our movement dataset.

MediaPipe provides a skeleton representation such as:

```text
shoulder: x, y, z
elbow:    x, y, z
wrist:    x, y, z
hip:      x, y, z
knee:     x, y, z
ankle:    x, y, z
...
```

A workout becomes:

```text
Pose(t0)
Pose(t1)
Pose(t2)
...
Pose(tn)
```

The important insight is that custom ML should generally operate on this compact temporal skeleton representation rather than raw video.

But MediaPipe must not be treated as ground truth.

Everything above the pose layer inherits its errors.

---

# 3. Phase 0 — Feasibility / Measurement Study

Do NOT begin by building the final pull-up state machine.

First build a diagnostic capture harness.

Its purpose is to answer:

> How stable and trustworthy are pose landmarks for pull-ups under realistic mobile-phone recording conditions?

The harness should support:

* camera preview
* MediaPipe pose overlay
* raw normalized landmarks
* MediaPipe world landmarks, if available
* landmark visibility/confidence
* timestamps
* derived joint angles
* recording/export of pose streams
* optional synchronized video recording
* backend/model version metadata
* device/platform metadata
* configurable inference FPS

Test approximately 25–30 pull-up sets across variations such as:

### Camera angle

* frontal
* approximately 30°
* approximately 45°
* approximately 60°
* side-biased

### Camera distance

* approximately 2m
* approximately 3m
* approximately 4m

### Camera height

* floor/low
* waist
* chest

### Conditions

* indoor
* outdoor
* bright
* lower light
* tight clothing
* normal clothing
* baggy clothing
* different athletes/body proportions if available

We are particularly interested in:

* elbow landmark stability
* shoulder stability
* wrist dropout near the bar
* hip stability
* landmark jitter
* confidence drops
* tracking loss
* sensitivity to camera angle
* angle changes caused by perspective rather than actual movement

For the first study, manually annotate useful events such as:

```text
rep start
top position
bottom position
rep end
valid / invalid
obvious tracking failure
```

Do not spend excessive effort establishing laboratory-grade biomechanical joint-angle ground truth yet.

The first question is whether the signal is usable enough for the product.

---

# 4. Capture Quality Is Part of the Intelligence System

Phone placement should not be treated as ordinary UI polish.

Camera placement directly changes the quality of the model input.

Create a conceptual `CaptureQualityEngine`.

It should eventually determine things such as:

```text
fullBodyVisible
requiredJointsVisible
subjectScale
cameraAngleEstimate
lightingAdequate
poseConfidence
occlusion
trackingQuality
```

The product should constrain the problem before trying to solve arbitrary camera setups.

For example, V1 may deliberately support only a proven recording envelope:

```text
Place the phone 3–4m away.
Use approximately a 30–45° front-side view.
Keep the whole body visible.
```

The UI can guide users:

```text
✓ Full body visible
✓ Shoulders visible
✓ Elbows visible

Move phone slightly farther away.
```

Supporting a restricted but reliable configuration is preferable to silently failing across arbitrary camera placements.

---

# 5. Pose Reliability Layer

Do not send raw MediaPipe values directly into exercise logic.

Introduce an explicit reliability layer.

Conceptually:

```text
RawPoseFrame
     ↓
Confidence filtering
     ↓
Joint dropout handling
     ↓
Temporal smoothing
     ↓
Tracking-state estimation
     ↓
ReliablePoseFrame
```

Tracking state should be first-class:

```text
TRACKING_GOOD
TRACKING_DEGRADED
TRACKING_LOST
```

Do not silently guess when tracking fails.

Movement events should be able to become:

```text
VALID
INVALID
UNCERTAIN
```

Example:

```text
Rep 5 ✓
Rep 6 ?

Tracking lost near top position.
```

An explicit uncertain result is better than a confident wrong count.

Trust is more important than pretending the system always knows.

---

# 6. Normalization / Canonicalization Is a Major Subsystem

Do not treat normalization as a trivial coordinate conversion.

This layer may contain much of the hard engineering and eventually become important IP.

Conceptually:

```text
ReliablePoseFrame
      ↓
Body scale normalization
      ↓
Body-relative coordinates
      ↓
Torso / hip-centered coordinate system
      ↓
Orientation normalization
      ↓
Camera-angle compensation where possible
      ↓
Perspective robustness
      ↓
CanonicalPoseFrame
```

Potential concerns include:

* body proportions
* torso-length normalization
* shoulder-width normalization
* camera azimuth
* subject rotation
* perspective distortion
* confidence-weighted interpolation
* temporal smoothing
* missing landmarks

The goal is:

> The same human movement should produce as similar a canonical representation as reasonably possible despite moderate differences in camera placement and body proportions.

Do not assume normalized MediaPipe coordinates or `world_landmarks` automatically solve this.

Evaluate both experimentally.

---

# 7. Movement Data Model

Do not make `Rep` the fundamental domain object.

Calisthenics includes repetitions and isometric skills.

Use a broader abstraction:

```text
MovementEvent
    ├── RepEvent
    ├── HoldEvent
    └── potentially SequenceEvent
```

Even if V1 only implements pull-up reps, the storage/domain model should not prevent holds later.

### RepEvent may contain

```text
start
bottom
ascending
top
descending
end

concentricDuration
eccentricDuration

ROM-related measurements
symmetry measurements
swing measurements

validity
uncertainty
tracking quality
```

### HoldEvent may contain

```text
entry
stableStart
exit
duration

alignment over time
motion over time
confidence over time
hold classification
```

This supports future skills such as:

* handstand
* front lever
* back lever
* L-sit
* planche progressions

without redesigning the entire data model.

---

# 8. Derived Features Should Remain Explainable

The geometry layer can derive features such as:

```text
elbow angle
shoulder angle
hip angle
knee angle
joint velocity
joint acceleration
body-line deviation
left/right symmetry
torso inclination
relative wrist/shoulder/hip positions
range of motion
```

Do not prematurely collapse these into a generic score such as:

```text
quality = 0.89
```

A single quality number is meaningless until externally validated.

Prefer storing components:

```json
{
  "rom": 0.94,
  "symmetry": 0.91,
  "bodySwing": 0.12,
  "kippingProbability": 0.08,
  "concentricSeconds": 1.21,
  "eccentricSeconds": 1.63
}
```

A composite score can be introduced later only if it proves useful and correlates with a meaningful external standard such as coach ratings.

---

# 9. Phase 1/2 — Pull-Up Engine

Only after Phase 0 shows the pose signal is viable should we implement the deterministic pull-up engine.

Conceptually:

```text
CanonicalPoseFrame
       ↓
MovementFeatures
       ↓
PullUpStateMachine
```

Possible states:

```text
WAITING
BOTTOM
ASCENDING
TOP
DESCENDING
```

A complete sequence:

```text
BOTTOM
   ↓
ASCENDING
   ↓
TOP
   ↓
DESCENDING
   ↓
BOTTOM
```

may produce a `RepEvent`.

Thresholds must come from measurement data, not guesses.

Avoid hard-coding arbitrary assumptions such as:

```text
elbowAngle < 70° = TOP
```

until we know how those measurements behave across supported camera configurations.

---

# 10. Benchmarking Must Exist From Day One

Create a held-out benchmark dataset before heavily tuning the algorithm.

It should be:

* manually annotated
* version-controlled
* separated from training data
* frozen for meaningful comparisons
* diverse across supported capture conditions

Evaluate event detection rather than only final rep count.

Useful metrics include:

```text
rep precision
rep recall
F1
false positives
false negatives
tracking-loss detection
uncertain-event frequency
```

Use temporal tolerance when matching predicted rep events against ground truth.

Example:

```text
Actual:
REP REP REP REP

Predicted:
REP REP FALSE_POSITIVE REP MISSED
```

A simple total count can hide this failure.

Always report metrics by condition where possible:

```text
front view
30° view
45° view
different distance
outdoor
low light
baggy clothing
etc.
```

Overall accuracy without condition breakdown can hide unusable edge cases.

Do not commit to arbitrary accuracy thresholds before obtaining baseline data.

After Phase 0/early Phase 1, define explicit product acceptance thresholds.

---

# 11. Thermal / Battery / FPS Budget

Do not assume 30 FPS pose inference is necessary.

Experiment with:

```text
30 FPS
15 FPS
12 FPS
10 FPS
```

The camera preview may remain 30 FPS while pose inference samples fewer frames.

Example:

```text
Camera: 30 FPS
      ↓
Pose inference: 15 FPS
```

Rep phases generally evolve much more slowly than individual video frames, so lower inference rates may preserve accuracy while substantially reducing:

* CPU/GPU usage
* thermals
* battery drain
* model workload

Record performance and thermal behavior during realistic 20–30 minute sessions.

Target FPS remains an open question until measured.

---

# 12. Store Raw Pose Data Where Appropriate

Do not persist only derived features.

Where the user has appropriately consented to data retention, retain:

```text
RawPoseSequence
+
CanonicalPoseSequence
+
PipelineMetadata
+
DerivedFeatures
+
MovementEvents
+
Labels if available
```

Reason:

```text
Pose data
   ↓
FeatureExtractor v1
   ↓
ROM = .87
```

Six months later:

```text
same original pose data
   ↓
FeatureExtractor v4
   ↓
ROM = .93
```

Without raw landmarks, old workouts cannot be reprocessed when the interpretation pipeline improves.

Every stored sample should include metadata such as:

```text
pose backend
pose model version
pipeline version
platform
device class/model
sampling FPS
canonicalization version
exercise analyzer version
```

Never pool data from different pipelines/backend versions naively.

---

# 13. Custom Temporal ML — Long-Term Direction

Once deterministic analysis produces useful data and we have a meaningful dataset, introduce small temporal models.

The model should generally receive:

```text
Pose sequence over time
```

rather than video pixels.

For example:

```text
60 frames
×
33 joints
×
XYZ
```

possibly plus selected derived features.

Candidate architectures:

* 1D CNN
* Temporal Convolutional Network
* GRU
* LSTM
* small Transformer

These can remain small enough for on-device inference.

The first role of ML should be to **assist deterministic logic**, not replace it.

Example:

```text
State machine:
REP VALID

ML:
kipping_probability = 0.83
```

Result:

```text
REP VALID
FORM SIGNAL: POSSIBLE KIPPING
```

Only migrate responsibilities from deterministic logic into ML when benchmark evidence demonstrates clear improvement.

---

# 14. Long-Term Shared Movement Encoder

After enough high-quality movement data exists, investigate a shared temporal movement representation.

Conceptually:

```text
                   Pose Sequence
                        ↓
                Movement Encoder
                        ↓
        ┌───────────────┼────────────────┐
        ↓               ↓                ↓
 Exercise Head      Phase Head        Form Head
        ↓               ↓                ↓
 pull-up           ascending          kipping
 squat             top                asymmetry
 dip               descending         partial ROM
 push-up           etc.               etc.
```

Additional heads could later support:

* exercise recognition
* rep segmentation
* form classification
* skill classification
* movement quality components
* hold progression classification

The shared encoder learns reusable representations of human movement rather than relearning basic body dynamics for every exercise.

This is a long-term research direction, not an MVP dependency.

---

# 15. Training Data Strategy

Do not assume rule-generated labels are ground truth.

Separate labels by quality/source.

### Gold Labels

High-confidence human/coach verified labels.

Examples:

```text
valid rep
partial ROM
kipping
asymmetry
skill progression
```

These are the most valuable labels for form quality.

### Silver Labels

Examples:

* strong teacher-model prediction + human verification
* multi-reviewer agreement
* other high-confidence annotation processes

### Bronze / Weak Labels

Examples:

* deterministic rule engine
* automated phase segmentation
* heuristic classifications
* user corrections

These are useful, but their limitations must remain explicit.

Rule-derived labels are particularly useful for:

* rep segmentation
* phase detection
* representation pretraining

They should not be expected to teach a model to outperform those rules on technique/form failures without additional independent labels.

---

# 16. User Corrections Are Signals, Not Truth

User actions such as:

```text
"Missed rep"
"Wrong count"
"That should count"
"That wasn't kipping"
```

should be recorded as disagreement signals.

Do not directly convert them into ground truth.

Users may:

* misunderstand technique standards
* accidentally provide incorrect feedback
* prefer inflated counts
* correct only certain types of errors

Use these interactions to identify:

```text
model-human disagreements
hard cases
uncertain cases
```

These are excellent candidates for later annotation.

---

# 17. Active Learning / Teacher Models

Large multimodal models may be useful during dataset creation.

They should not necessarily run continuously during production workouts.

A useful learning loop is:

```text
On-device model
       ↓
low confidence / disagreement / unusual sample
       ↓
selected training candidate
       ↓
powerful teacher model
       ↓
suggested annotation
       ↓
human verification where required
       ↓
high-quality dataset
       ↓
small on-device student model
```

Possible teacher systems may include Vision Agents, Gemini, OpenAI, Claude, or future multimodal models.

The expensive model helps improve the cheap local model.

This is preferable to permanently paying a multimodal model to watch every workout.

---

# 18. Personalization

Separate two concepts carefully.

## Personal Calibration

May adapt to the user:

```text
limb proportions
body proportions
camera setup
normal movement ranges caused by anthropometry
```

## Technique Definition

Must not silently adapt toward poor form.

Example:

If someone performs 100 partial pull-ups, the system should not conclude:

```text
partial pull-ups are now normal for this athlete
```

Personalization should compensate for:

* anatomy
* camera geometry
* user-specific measurement bias

It should not redefine correct technique merely because incorrect execution is frequent.

---

# 19. Progression Intelligence

The movement engine should produce measurements.

Long-term history analysis should usually happen outside the movement classifier.

Example:

```text
Week 1
ROM        86%
Kipping    24%
Tempo      1.2 / 1.0

Week 8
ROM        95%
Kipping     7%
Tempo      1.3 / 1.8
```

A progression engine can reason about:

* increasing volume
* movement consistency
* ROM changes
* tempo
* fatigue
* skill progression
* technique trends

Keep quantitative progression logic deterministic/testable where possible.

---

# 20. LLM / AI Coach

The LLM sits near the top of the architecture, not at the camera layer.

Preferred:

```text
Movement Engine
      ↓
Structured Workout Data
      ↓
Progression / Training Logic
      ↓
Recommended insight/action
      ↓
LLM
      ↓
Natural explanation / conversation
```

Example structured input:

```json
{
  "exercise": "pull_up",
  "validReps": 7,
  "rom": 0.92,
  "kippingReps": 2,
  "velocityDrop": 0.23
}
```

The LLM should primarily help with:

* explanation
* conversation
* summarization
* contextual coaching language

It should not be the only source of quantitative training policy.

For example:

```text
Training engine:
recommendedRest = 90 seconds
reason = velocity drop
```

LLM:

```text
"Your final reps slowed considerably, so take around 90 seconds before the next set."
```

This is safer and reproducible.

---

# 21. Privacy / Data Strategy

Default to:

```text
on-device processing
```

Uploading movement/video data should require explicit user awareness and appropriate consent.

Design for:

* minimal raw video retention
* user deletion
* purpose-specific collection
* separate product usage from training-data contribution
* data minimization
* pipeline metadata
* secure storage/transmission

Pose landmarks should be treated conservatively as potentially sensitive movement/body data.

Do not assume that converting video into landmarks automatically removes all privacy concerns.

---

# 22. Product Wedge

Do not assume the final commercial product must be branded primarily as an “AI Coach.”

The underlying movement engine may support multiple product wedges:

```text
automatic workout logging
rep verification
form feedback
calisthenics skill tracking
remote coach verification
competitions/challenges
accountability
training progression
```

An especially interesting direction is **movement verification**.

Example:

```text
Challenge:
50 strict pull-ups

Camera verifies:
✓ top position
✓ bottom position
✓ complete rep

Verified score: 47
```

Or remote coaching:

```text
Coach assigns:
3 × 8 strict pull-ups

Athlete result:
8 / 8 / 7

Set 3 Rep 7:
partial ROM detected
```

The core asset remains the movement intelligence layer regardless of the eventual commercial wrapper.

---

# 23. Product Loop vs Learning Loop

Keep these separate.

## Product Loop

```text
Capture
  ↓
Reliable Pose
  ↓
Movement Events
  ↓
Useful Measurement
  ↓
User Value
```

## Learning Loop

```text
Movement Samples
      ↓
Uncertainty / Disagreements
      ↓
High-quality Annotation
      ↓
Training
      ↓
Benchmark Evaluation
      ↓
Better Model
```

Do not collect data indiscriminately.

Prioritize:

```text
uncertain cases
failures
model/rule disagreements
new camera conditions
new body types
hard examples
```

This active-learning strategy can provide significantly more value per labelled sample.

---

# 24. Roadmap

## Phase 0 — Feasibility Study

Build the diagnostic pose capture harness.

Measure MediaPipe reliability for pull-ups.

Decide:

* supported camera placement
* normalized vs world landmarks
* required joints
* acceptable FPS
* tracking reliability
* whether pose quality is sufficient

This phase has a go/no-go outcome.

---

## Phase 1 — Capture & Canonicalization

Build:

```text
CaptureQualityEngine
PoseReliabilityLayer
PoseCanonicalizer
```

Establish stable cross-platform `PoseFrame` / `CanonicalPoseFrame` contracts.

---

## Phase 2 — Deterministic Pull-Up Engine

Build:

```text
PullUpAnalyzer
MovementEvent
RepEvent
tracking uncertainty
```

Benchmark against the held-out dataset.

---

## Phase 3 — Production Pull-Up Experience

Add:

* framing assistant
* skeleton overlay
* rep counting
* valid/invalid/uncertain reps
* tempo
* ROM-related measurements
* basic form signals
* voice count
* workout history

---

## Phase 4 — More Repetition Exercises

Potentially:

* push-ups
* squats
* dips

Reuse the same movement architecture.

---

## Phase 5 — ML Assistance

Train temporal models to improve:

* exercise detection
* phase detection
* difficult rep segmentation
* selected form classifications

Use benchmark evidence to decide where ML actually improves the system.

---

## Phase 6 — Holds / Skills

Add:

* handstand
* front lever
* back lever
* other calisthenics skills

Use `HoldEvent` rather than forcing hold movements into a rep abstraction.

---

## Phase 7 — Progression / Coaching

Build:

* historical movement analytics
* personal calibration
* progression detection
* structured coaching logic
* optional LLM explanation layer

---

## Phase 8 — Shared Movement Encoder

Only after sufficient high-quality data exists, investigate a shared multi-task movement model.

This is a research direction, not an early commitment.

---

# 25. Open Questions Register

Maintain these explicitly rather than allowing implementation choices to silently become architecture.

Initial open questions:

1. Are MediaPipe normalized landmarks or world landmarks more reliable for our movement representation?
2. What camera angle gives the strongest pull-up signal?
3. What camera distance/height should V1 support?
4. Is body-relative 2D geometry sufficient, or is useful depth information available?
5. How much camera-angle normalization is realistically achievable?
6. What pose inference FPS is required?
7. What smoothing/filtering method works without delaying rep events?
8. Which joints are mandatory for a pull-up?
9. When should tracking become `DEGRADED` or `LOST`?
10. How should uncertain reps be represented in the UI?
11. What raw pose data should be retained locally?
12. Which data may be uploaded and under what consent model?
13. What benchmark metrics constitute an acceptable pull-up MVP?
14. How should `HoldEvent` metrics be represented?
15. How should pose pipeline/model versioning work?

Decisions should be documented with evidence.

---

# 26. Falsification / Stop Conditions

We should actively try to prove our assumptions wrong.

### Pose premise fails

If MediaPipe cannot provide stable enough landmarks even under a reasonable guided camera configuration, investigate alternative pose models or reconsider camera-only analysis for that movement.

### Capture premise fails

If reliable pull-up analysis requires phone placement so restrictive that normal users will not tolerate it, the product hypothesis is weakened even if technical accuracy is high.

### Form-analysis premise fails

If monocular pose cannot reliably distinguish a particular technique problem, do not present that signal as authoritative.

Only claim measurements supported by evidence.

### On-device premise fails

If pose/model inference creates unacceptable thermal or battery behavior, first reduce FPS, model complexity, and processing before moving to an expensive continuous-cloud architecture.

### Training-data premise fails

If useful labelled data cannot be obtained economically or users will not contribute sufficient samples, do not base the product's moat entirely on a proprietary dataset.

### User-value premise fails

If users do not care enough about automatic measurement/verification to repeatedly position a camera for workouts, reconsider the product wrapper even if movement analysis works technically.

---

# 27. Long-Term Moat

The strongest technical asset may eventually be:

```text
movement sequences
+
reliable event labels
+
technique labels
+
uncertainty data
+
longitudinal progression
+
movement models trained from that data
```

The potential flywheel is:

```text
Better capture
     ↓
Better movement representation
     ↓
Better measurement
     ↓
Better product
     ↓
More useful movement data
     ↓
Better annotations
     ↓
Better local models
     ↓
Better measurement
```

The dataset is only valuable if:

* the underlying pose signal is reliable
* labels are high quality
* pipeline versions are recorded
* user consent permits its use
* the product gains enough adoption to generate meaningful diversity

Do not treat “we will have more data” as an automatic moat.

---

# 28. North Star

We are not building:

> A camera rep counter.

And we are not initially building:

> A large AI model that watches workouts.

We are trying to establish:

> Whether an ordinary mobile phone can create a reliable, canonical representation of calisthenics movement.

If that premise is validated, the long-term goal is:

> Build an on-device movement intelligence system that increasingly understands what movement occurred, how it was performed, how the athlete is progressing, and how that information can be converted into useful coaching.

The first implementation should remain intentionally small.

The architecture, data model, evaluation methodology, and data strategy should ensure that work done today can contribute toward that larger system rather than being discarded when the product becomes more intelligent.

---

# Immediate Instruction

Start with **Phase 0 only**.

Do not build custom ML or the complete production app yet.

Produce a small cross-platform or platform-first diagnostic prototype capable of:

1. capturing camera frames,
2. running MediaPipe Pose,
3. visualizing the skeleton,
4. recording raw normalized + world landmarks,
5. recording visibility/confidence values,
6. calculating selected basic joint measurements,
7. recording timestamps and pipeline metadata,
8. optionally recording synchronized video,
9. changing pose inference FPS,
10. exporting a session in a format suitable for offline analysis.

Keep the code structured so the following concepts can later become stable interfaces:

```text
RawPoseFrame
ReliablePoseFrame
CanonicalPoseFrame
MovementEvent
RepEvent
HoldEvent
CaptureQuality
TrackingState
```

Do not invent thresholds prematurely.

The immediate deliverable is not “accurate pull-up counting.”

The immediate deliverable is:

> **Evidence about whether our raw movement signal is reliable enough, under what capture conditions, and what transformations are required before we build the movement engine.**
