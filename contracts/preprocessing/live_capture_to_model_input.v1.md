# Live Capture-to-Model-Input Contract v1

## Scope

This contract governs the camera-buffer path that decoded-frame fixtures bypass.
It creates the same canonical model-input plane before pose inference. It does
not define a YUV conversion algorithm for every platform format; those adapters
require their own synthetic byte-level vectors before use.

## Required recorded source metadata

Before a conformance session begins, the harness reads and records the source
pixel format, colour matrix, colour range, orientation, mirroring, width, and
height. If any required colour metadata is unavailable, the session cannot
start. A harness must not silently discard an individual buffer or assume a
colour matrix or range.

## Required transformation

For every accepted camera buffer, perform these operations in order:

1. Apply orientation and mirroring so the source is upright and unmirrored.
2. Convert the declared source format, matrix, and range to full-range sRGB
   RGB24.
3. Scale to fit the manifest's model-input dimensions, preserving aspect ratio
   with bilinear interpolation.
4. Round scaled dimensions half upward, centre them, put an odd remainder on
   the right or bottom, and fill padding with RGB `(0, 0, 0)`.
5. Feed the resulting packed, row-major RGB24 plane to the pose backend.

Step 4 is exactly the geometry defined by
`decoded_frame_fixture.v1.md`: use centre-of-pixel mapping, clamp source
coordinates to edge pixels, calculate bilinear weights as exact fractions, and
round channels with `floor(value + 1/2)`. Crop-to-fill, aspect-ratio distortion,
and platform-default letterboxing are not conformant.

The manifest records the configured model-input dimensions and
`live_preprocessing_contract_version`. The canonical output plane is upright,
unmirrored, full-range sRGB RGB24 in R-G-B channel order, tightly packed and
row-major.

## Conformance

Platform adapters may use optimized implementations only when they produce the
committed RGB24 preprocessing vectors byte for byte. Source-format-specific
conversion vectors are required before an optimized YUV adapter can be used.
There is no numerical tolerance: preprocessing conformance is an exact
byte-level property.
