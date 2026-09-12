# Next movement evidence candidate: sit-ups

Date: 2026-09-07. This is preparation for an offline selected-movement experiment,
not a supported feature, event annotation or form verdict.

## Why this candidate now

Both approved reuse notes remain authoritative planning inputs. The first note
explicitly includes sit-ups as an evidence-led option. The already-downloaded
MM-Fit w01 author CSV contains three situp-labelled intervals:
34650-35244, 35503-36070 and 36684-37260, each with aggregate count10.
These are author annotation indices/counts, not verified container timestamps
or individual completion labels.

A first candidate was located using the nominal30fps frame ranges, then checked
against decoded footage before pose extraction. The 1fps chronological contact
sheet from source1148-1184s shows a bent-knee floor exercise with repeated trunk
recline/rise, seated preparation and seated recovery. This supports investigating
the movement family; it does not verify exact cycles, complete anatomical ROM,
foot support, or strict sit-up versus crunch technique.

## Prepared local artifacts

Private directory:
`/Users/sanchit.walia/.buzz-multiagent/calisthenics-expert/offline-corpus/situp-candidate-inspection-8W1DUj`

- source-record.json: inherited source/license attribution, original and derivative
  digests, original-recording group, development split, derivation and inspection.
- set1-contact-sheet.jpg: local inspection artifact, no Git redistribution.
- mmfit-w01-situp-candidate.mp4: 36seconds, 640x360, no audio, original cadence;
  clip-local PTS. The source is the existing permitted recording, not a new download.
- cache-v1: fresh desktop MediaPipe0.10.21 Full CPU extraction, 1080frames.
  Output completed with exit0; pose SHA256 independently rechecked.

Original video hash:
`e4de54951c488d6f153186a46fa5d53a0d2d39e77e0f6b07a62351975d591af8`

Derivative:
`a1e330ecdeb890d00eabdcce95e7fbe8dd0c6c07da022b5dadff25be1ad5f62a`

Pose cache:
`649187f43d674b30727934207cfcffa510a843561c59cf8dba04dce68ef4ed1e`

## Observability, not performance

Whole pose absent: 0/1080frames.
At the existing explicit0.5 presence/visibility threshold, the left
shoulder/hip/knee chain is usable in1080/1080frames; the right chain in12/1080.
This is a confidence diagnostic, not proof of spatial accuracy or a validated
sit-up detector input requirement. Do not report the same observations as
bilateral corroboration, and do not fill the far side with invented points.

The extractor emitted its existing MediaPipe projection/feedback-manager
warnings; completion and hashes establish cache creation, not absence of
backend caveats. No mobile equivalence is claimed.

## Next executable work

1. Define visible event convention before looking at predictions: full observed
   recline-to-rise-to-recline cycles or the chosen equivalent endpoint; state
   whether preparation is excluded. Do not silently equate this with strict form.
2. Inspect denser raw frames and label event windows before any counter output.
   Keep ambiguous ROM/event boundaries explicit; aggregate10 is not timing truth.
3. Use raw seated-rest portions as negative context; source an independent
   recording before any generalization claim. All w01 sets remain development.
4. Run a fixed-action PoseRAC comparator if its admission prerequisites resolve,
   otherwise evaluate a minimal explainable common-Kotlin prototype against these
   labels and confusions, not a new Python counting implementation.
5. Report misses/extras and uncertainty, without deploying to the phone or
   requiring another owner workout.

No sit-up counter was implemented in this preparation step. Existing counters
and the pending lunge/hold/confusion work remain in the scorecard rather than
being superseded by the new candidate.
