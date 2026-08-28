# Branch evidence: the capture Y-flip fix

**These are not published docs assets and not regenerated gallery output.** They are a three-file
record of the observation that justified the `TexturePreviewCache` / `writeJpeg` row-order fix, kept
next to the code that produced it. See [../../CAPTURE_ROW_ORDER.md](../../CAPTURE_ROW_ORDER.md) for
the per-path table and the derivation.

| File | What it is |
|---|---|
| `uv-coordinates-BEFORE-inverted.png` | the committed `public/docs/assets/stacksl/stacksl-uv-coordinates.png` as it stood at `3ee34ea27`, produced by the pre-fix code |
| `uv-coordinates-AFTER-upright.png` | the same example re-rendered on the GPU with this branch applied |
| `uv-coordinates-before-after-diff.png` | the two side by side, plus `|AFTER flipped − BEFORE|`, which is entirely black |

## Why this example is the oracle

The StackSL program is `uv saturate`, and the gallery's vertex shader
(`public/docs/assets/stacksl_gallery/uv-coordinates_vertex_.glsl`) sets `fragUV = pos` with
`gl_Position = pos*2-1`, so `uv.y = (clip.y+1)/2`. The green channel *is* `uv.y`. With the
negative-height viewport every render path shares, clip `y = +1` maps to image row 0 — so `uv.y = 1`
must appear at the **top**. Sampled corners:

| | top-left | bottom-left | verdict |
|---|---|---|---|
| BEFORE | `(16, 16, 0)` | `(16, 239, 0)` | `uv.y` high at bottom → inverted |
| AFTER | `(16, 239, 0)` | `(16, 16, 0)` | `uv.y` high at top → upright |

The point of this oracle is that the shader fixes the right answer. It is not a judgement about
what the picture ought to look like.

## The result these two stills stand for

The full run rendered **31** StackSL examples through the real Vulkan readback. Flipping each fresh
render vertically and comparing against its committed pre-fix counterpart:

```
31 of 31 byte-exact, 0 approximate, 0 other
6 of 31 vertically symmetric (byte-identical even unflipped, because a flip is a no-op on them)
per-channel difference extrema on this example: ((0,0), (0,0), (0,0))
```

So the fix's entire effect on this production path is a vertical flip — no channel swap, no resize,
no colour shift, no re-render nondeterminism. Only two of the 31 are committed here; the other 29
are regenerable by the command below.

## Re-running it

The renders live under `build/` (gitignored) and are reproducible:

```bash
# 1. render the gallery through TexturePreviewCache on a real GPU.
#    The runner points docs assets at build/field-tests/generated-doc-assets via
#    AFIELD_GENERATED_DOC_ASSET_ROOT, so committed assets are NOT touched.
tools/run_field_tests.py --id docs.stacksl.gallery --port 9482

# 2. the whole claim, in one command: every fresh render, flipped, vs the committed one.
python3 - <<'EOF'
from PIL import Image, ImageChops
import os
old, new = 'public/docs/assets/stacksl', 'build/field-tests/generated-doc-assets/stacksl'
exact = other = 0
for n in sorted(f for f in os.listdir(new) if f.endswith('.png')):
    o = os.path.join(old, n)
    if not os.path.exists(o):
        continue
    a = Image.open(o).convert('RGB')
    b = Image.open(os.path.join(new, n)).convert('RGB').transpose(Image.FLIP_TOP_BOTTOM)
    if a.size == b.size and ImageChops.difference(a, b).getbbox() is None:
        exact += 1
    else:
        other += 1
        print('NOT A PURE FLIP:', n)
print(f'{exact} byte-exact, {other} other')
EOF
```

Do not commit step 1's output into `public/docs/assets/` from a branch — regenerating the published
gallery is a post-merge step, and until it happens those committed figures are inverted relative to
the code.
