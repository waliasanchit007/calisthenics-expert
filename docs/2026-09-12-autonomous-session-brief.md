# Autonomous session brief — paste this after compaction

Read `docs/2026-09-12-state-and-plan.md` in this worktree COMPLETELY before doing anything. It is the
source of truth for paths, the toolchain workaround, the eight method rules, per-movement status and
the plan. This brief only says how to work; that file says what the work is.

Worktree: `~/.buzz-multiagent/calisthenics-expert/worktrees/m0-5b-task-4-synchronized-video`
Ledger:   `<worktree>/.superpowers/sdd/2026-09-11-muscle-up/progress.md` (append an Update per unit
of work; it is how the next session learns what happened)

## Work autonomously for about three hours. Do not wait for me.

Take the plan in §7 in order. C is done. Work A, then B, then D, then E, and do not stop between
them to ask permission. Make every routine technical decision yourself.

## The rules that make the output trustworthy — §3 of the plan, restated because they are load-bearing

1. Freeze labels before predictions; freeze configs before reading labels back. Corrections are
   additive new versions, never edits to v1.
2. Declare parameter values and predicted outcomes in the ledger BEFORE the run that tests them.
   Never re-tune a parameter against the result it produced.
3. Never fit to labels. Subtracting an offset measured from annotations is forbidden.
4. Pose data may aim a crop. It may never decide a label.
5. Use `exact_sheet.sh` for contact sheets. The fps filter is off by +0.10 to +0.24 s.
6. Refuse rather than infer. UNKNOWN/null means not observed and is a valid, preferred answer.
7. Report counts as the trustworthy metric; treat ±0.25 s window rates as unstable.
8. Every engine change ships with tests, a mutation check, and a byte-identical reproduction of the
   pre-change output on a pinned path. Run the full suite before claiming anything.

## Decide these yourself, without asking

Which clips to screen and in what order; whether a clip is usable and why; engine and test design;
parameter values (declared in advance per rule 2); when to refuse a measurement; what to write in
the ledger and docs; which diagnostics to run.

## Never do these, even if they look like the obvious next step

Push, commit to a shared branch, open a PR, deploy, or change app/production behaviour. Put video or
person-derived data in Git. Rewrite historical outputs or annotations. Move a label to improve a
score. Migrate the endpoint estimator to another movement without first doing B for that movement.

## Footage — you are authorised to download as much as you need

Owner, 2026-09-12: download any number of YouTube videos, to improve an existing movement or to add
a new one. Five spares are already on disk, but do not feel bounded by them — if a movement is stuck
for want of footage, go and get footage. Front lever needs a SIDE-ON recording and toes-to-bar needs
a third athlete; both have been blocked on exactly this.

The standing conditions still apply and are the owner's, not mine:

- **Never bypass an access restriction.** `fetch_youtube.py` stops when YouTube demands sign-in;
  leave it stopped, record the refusal, and move to another candidate. No client spoofing, no token
  work, no credential workarounds.
- **Prefer Creative Commons** and record the licence, attribution, source URL and acquisition method
  in a `source-record.json` next to every download, as the existing corpus does.
- **Internal assessment only.** No redistribution or other reuse.
- **Media and person-derived data stay outside Git**, in `offline-corpus/`.
- Record what the file ACTUALLY is from `ffprobe`, never what YouTube's metadata claims — that error
  has already been made once and corrected across 14 records.
- Screen before labelling: one athlete, fixed camera, uncut sequence, usable angle, endpoints in
  frame. A clip that fails screening is a recorded negative, not a failure.

## Escalate ONLY these — write them at the top of your final report and carry on with other work

A genuine external blocker (missing footage, a network or credential wall) or a decision that
changes published numbers across movements. Do not stop the session for them; note and continue.

## If something is ambiguous

Pick the interpretation that claims less, write down which you picked and why, and keep going.
If a result is negative or a clip is unusable, that IS the finding — record it and move on rather
than hunting for a positive.

## Finish with

A short report: what you closed, what you found, what you refused and why, anything that surprised
you, any correction to something previously recorded, and the single next action you would take.
Update `docs/2026-09-12-state-and-plan.md` so it stays accurate, and append your ledger Updates.
