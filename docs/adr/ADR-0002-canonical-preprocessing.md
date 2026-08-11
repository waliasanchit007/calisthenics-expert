# ADR-0002: Canonical Model-Input Preprocessing

- Status: Accepted
- Date: 2026-08-12
- Decision owners: Sanchit Walia, `claude-engineer`, and the coordinating agent

## Context

Platform video decoders, camera pixel formats, colour conversion, orientation handling, scaling, and padding can create differences before pose inference begins. Comparing Android and iOS outputs is not scientifically useful unless identical fixture pixels reach the backend and live capture follows one declared normalization rule.

## Decision

The canonical model-input plane is upright, unmirrored, full-range sRGB RGB24 with packed row-major bytes.

Both decoded fixtures and live capture use one scale-to-fit transformation:

1. normalize orientation and mirroring;
2. convert the declared source colour matrix and range to full-range sRGB RGB24;
3. preserve aspect ratio and resize with bilinear interpolation;
4. round half values upward;
5. centre the result, assigning odd padding to the right or bottom;
6. pad with RGB `(0, 0, 0)`.

Fixture metadata records exact canonical bytes and SHA-256 values. Live manifests record source pixel metadata, target dimensions, and preprocessing-contract version. Platform-optimized conversions must match source-format-specific synthetic reference vectors byte for byte before use in conformance capture.

## Consequences

- Decoded fixtures bypass platform decoders and isolate backend/delegate variance.
- Live camera differences remain attributable because preprocessing is declared and testable.
- Source-format conversion vectors are added with each native adapter rather than guessed in this platform-neutral milestone.
- No accuracy tolerance is introduced; preprocessing conformance is an exact byte-level property.
