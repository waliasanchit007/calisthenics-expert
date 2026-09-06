# Contrasting movement: manual push-up experiment

Owner direction: proceed with push-ups after the pull-up variation self-test. This is
product-learning-reset section 4, not completion of the formal study or a production release.

## Intended outcome

Choose Pull-up or Push-up before starting the camera. For push-ups, place the phone side-on
with the selected shoulder, elbow and wrist visible throughout the movement; include the whole
body in the preview. Practice once from extended arms, lower, pause briefly, then return to
extended arms. Confirm that it was a full practice. Subsequent observed cycles increment the
experimental count. Keep the current uncluttered screen and automatic private feedback.

## Deliberate limits

This is manual exercise selection, not exercise recognition. A below-shoulder wrist and an
elbow cycle cannot establish floor support, body alignment, depth or strict technique. Other
arm movements can satisfy this prototype. Do not call candidate cycles verified push-ups.
No additional learned model, hip/form threshold, automatic side selection, cloud inference,
stable interpretation contract, or expanded recording permission is introduced.

## Implementation boundary

Reuse the common calibrated arm-cycle mechanics. Add a typed experimental movement profile;
PULL_UP remains the default and preserves its existing behavior. During COUNTDOWN only, PUSH_UP
requires the selected wrist below its shoulder in the upright source image before practice can
start. Once practice starts, require finite wrist measurement but do not gate its sign: the wrist
and shoulder may approach the same image row at the bottom. Zero is rejected for initial setup,
not during motion. Missing/nonfinite wrist is always rejected; do not negate or invent measurements.
The initial sign is a setup constraint, not an anatomical classifier. All confidence, freshness,
capture-gap, median, range, endpoint dwell and explicit-confirmation rules stay unchanged.
The flexed endpoint is the push-up bottom, not its top; operator messages must reflect that.

Use neutral arm-cycle names for the reusable experimental implementation, with compatibility
aliases for existing pull-up callers if needed. Do not create a general movement framework or
force future holds through this rep analyzer. Shared pose/measurements remain exercise-independent.

Movement selection is available only while the camera/session is stopped. Changing selection
clears practice, range, pending motion and count. Stop/reset clears transient state while retaining
the explicit selection. Camera start still requires fresh in-app self-test confirmation.

Record the movement identity on every new debug trace row. Exact replay uses that profile;
historical traces lacking the field are pull-up traces. Reject unknown, null or mixed movement
identities instead of silently replaying as pull-up. Keep stored outcomes backwards compatible;
translate the historical TOP_NOT_CONFIRMED to a bottom-position cue for the push-up operator.
No personal traces or images enter source control. No upload, push, PR, merge or tag.

## Acceptance

- Synthetic below-shoulder trajectories complete practice only after a complete cycle and
  explicit confirmation; slow and supported brief-reversal cycles count once each.
- Initial wrong-sign/zero wrist delays practice. Finite zero/sign crossings after setup must not
  create synthetic tracking loss; missing/nonfinite measurements still invalidate pending motion.
- Partial, missing-joint, stale, duplicate, backwards and interrupted trajectories never create
  a fabricated cycle. Count survives interruption and requires a newly observed start endpoint.
- Pull-up synthetic regressions and the latest private replay retain exact increment events.
- UI exercises movement selection, disabled switching while active, movement-specific setup,
  explicit confirmation, stop and reset. No agent-started real camera capture.
- Debug logger and replay tests prove identity preservation and legacy compatibility.
- Run focused Kotlin tests while iterating, then required Python/static/repository checks,
  Android builds and shared iOS compilation. Install without clearing personal app data only
  after software verification. Real push-up usefulness remains pending an operator self-test.

## Alternatives considered

Copying the whole pull-up analyzer would duplicate its hard-won continuity fixes. A generic
exercise/hold framework would be premature with only one observed exercise. A small explicit
profile is the preferred middle ground; contrasting movement evidence decides future abstractions.
