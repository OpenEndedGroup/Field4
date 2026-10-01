# Slug validation

Verified on macOS / Apple silicon, 2026-09-30. Native source pinned to
Slughorn `409c4a2e68cafcb49e11ea0edd0145d4e255cbea`.

## Automated checks

- `bash native/field-slug/build.sh /tmp/field4-pr96-slughorn` builds the helper
  and runs its real Canvas lifecycle/coverage self-test.
- `uv run python native/field-slug/tests/check_prepare.py native/field-slug/build/field-slug`
  passes bounds, determinism, implicit closure, contour, tolerance and malformed-input checks.
  Forty local two-contour preparations including process startup: median 3.21 ms,
  maximum 5.33 ms. These are preparation timings, not a rendering benchmark.
- `./gradlew jvmTest --tests 'afield.graphics.slug.SlugPathTest'` checks real
  native output, defensive copies, invalid metrics and GPU addresses, FLine
  serialization, actual shader compilation, 96-byte reflected push layout and
  the persisted lesson's topology/source parity.
- `uv run --project . --group backdoor python tools/run_field_tests.py --id four.slug.readback`
  passes actual float render-target readback: nonzero-winding hole, fractional edge
  coverage, straight-alpha RGB and alpha, changed transform scale, fresh atlas
  replacement, deferred retirement and idempotent close. No Vulkan validation
  errors were reported. The native helper must be built first; missing setup fails.

## Repository and documentation gates

Six focused JVM tests and 109 citation-contract tests pass, with no failures or
skips. `checkTestTreeWrites` reports no test writes. `generateCodebaseReference`
completed successfully without a generated diff. The HTML-only docs build passes
its asset and local-link checks; existing captures were retained, not regenerated.

## Field box

The documented box was launched with its actual shader properties under the
backdoor graphics renderer. Its first launch and a second launch both rendered
the curved outline and square hole, with the camera moving and tint alpha 0.8.
Native screenshots `/tmp/pr96-slug-live.png` and `/tmp/pr96-slug-rerun.png` were
visually inspected; stopping the box succeeded. These are local diagnostic
artifacts, not committed documentation captures.

This caught a real box-only issue: Field's output `error` helper returns Unit,
so push-binding failure callbacks must explicitly throw IllegalStateException.
The JVM smoke alone would not have caught that scripting-name resolution.

## Remaining scope

No certification of near-plane crossing, grazing-angle projection, VR multiview,
all HDR composite arrangements, Windows native builds, glyph shaping or strokes.
The automated coverage oracle currently uses rectangles with a hole; curved edges
were inspected in the running example. There is no performance claim about the
fragment shader or high-frequency preparation.
