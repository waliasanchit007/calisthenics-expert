# Decoded-Frame Fixture Contract v1

## Scope

This contract defines the prepared decoded frames used for synthetic, repeatable
cross-platform conformance. A fixture bypasses each platform's video decoder;
it is not a description of camera buffers or a preprocessing-vector descriptor.
`FixtureMetadata v1` records the frame sequence and its integrity data.

## Canonical frame representation

Each referenced frame is a tightly packed, row-major RGB24 byte plane:

- `pixel_format` is `rgb24`.
- `color_space` is `srgb` and `color_range` is `full`.
- `channel_order` is `rgb`.
- `orientation` is `upright`; no EXIF or platform orientation transform remains.
- Byte offset for channel `c` of pixel `(x, y)` is `3 * (y * width + x) + c`,
  where `c` is 0 for R, 1 for G, and 2 for B.

Frame dimensions, synthetic provenance, and the preprocessing-contract version
are declared in the fixture metadata. `frame_index` values are exactly
`0..n-1`, and timestamps are strictly increasing in that order.

## Preparing a fixture frame

When source pixels must be prepared, the generator performs these operations in
order:

1. Apply orientation so the source is upright.
2. Convert to full-range sRGB RGB24.
3. Scale to fit the target rectangle while preserving aspect ratio with
   bilinear interpolation.
4. Round each scaled dimension half upward.
5. Centre the scaled image; an odd remainder belongs to the right or bottom.
6. Fill every padding pixel with RGB `(0, 0, 0)`.
7. Write the packed bytes and record their length and SHA-256.

For source dimensions `sw` by `sh` and target dimensions `tw` by `th`, let
`s = min(tw/sw, th/sh)`, represented as an exact rational number. The scaled
dimensions are `floor(sw*s + 1/2)` and `floor(sh*s + 1/2)`. Their top-left
destination location is `((tw-scaled_width)//2, (th-scaled_height)//2)`.

For destination coordinate `(dx, dy)` in the scaled image, interpolation uses
centre-of-pixel coordinates:

```text
source_x = (dx + 1/2) * sw / scaled_width - 1/2
source_y = (dy + 1/2) * sh / scaled_height - 1/2
```

Clamp both coordinates to the source edges. Bilinearly combine the four
neighbouring channel values using exact rational weights, then emit
`floor(value + 1/2)`. No tolerance is permitted for conformance.

## Integrity and path rules

Every frame path is relative to the directory containing the metadata. The
resolved path must remain beneath that directory and name a regular file;
absolute paths and traversal outside the fixture root are invalid. The recorded
byte length and lowercase SHA-256 must match the referenced bytes exactly.
