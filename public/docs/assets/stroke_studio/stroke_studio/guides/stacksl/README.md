# Stroke StackSL

Edit the **StackSL** property on **Stroke studio**. Replace the body inside one named definition. Keep one input and one result on the stack. `>name` moves the input into a local and `<name` recalls it. Numbers and swizzles are vector-valued and `vec4` packs the first component of four values.

## Hooks

| Definition | Input xyzw | Output xyzw |
| --- | --- | --- |
| `brushStyle` | Along-path fraction, age in seconds, stable seed, direction class | Thickness ×, weight ×, scale ×, lateral offset in half-widths |
| `brushSurface` | Anchor UV, along-path fraction, age in seconds | Same lanes as brushStyle. Gains multiply and lateral offsets add |
| `brushOffset` | Same lanes as brushStyle | World XYZ offset, unused |
| `lifeEnvelope` | Age, attack duration, release duration in seconds, surface ID | Draw fraction and opacity in xy, zw unused |
| `guideVelocity` | UV, frame delta in seconds, scene time in seconds | XY velocity in **analysis pixels per second**, confidence in z, optional speed in w |
| `pigmentStyle` | Mean signed RGB residual along the traced path, direction strength | Signed linear RGB before the Pigment fraction control, w unused |
| `regionalStyle` | UV, normalized quadtree variance interest, direction strength | Birth probability ×, thickness ×, step length ×, w unused |
| `edgeTone` | Mixed analysis RGB, downsampled stroke coverage | Linear analysis RGB, w unused |
| `compositeTone` | Signed RGB after blur and blend, accumulated coverage | Signed RGB before clear color and gamut mapping, w unused |

Brush gains are positive multipliers. Use **Pigment fraction** for the global amount of color error used at birth. World displacement adds to the authored `brushOffset`. The two draw hooks that shape local width can also vary pressure along the length of an existing mark. `lifeEnvelope` drives both the draw and interrupted-release transitions, so keep it continuous when editing live.

## Ambient texture samplers

UV `(0,0)` is the top left of the analysis and paint grids. Use `sample0-abs` through `sample3-abs` for known coordinates. Relative `sample0` through `sample3` add an offset to the first two input lanes. Relative sampling therefore makes sense for the hooks whose input starts with UV. For other hooks, supply explicit absolute coordinates.

| Hook group | Sampler 0 | Sampler 1 | Sampler 2 | Sampler 3 |
| --- | --- | --- | --- | --- |
| Brush and life | Lit RGB | Optical velocity | Current world | Previous world |
| Velocity and pigment | Optical velocity | Edge direction | Signed analysis-grid paint | Lit RGB |
| Regional | Summary value | Summary extent | Summary moment | Edge direction |
| Edge tone | Full-resolution signed paint | Lit RGB | Optical velocity | Current world |
| Composite tone | Full-resolution signed paint | Medium Gaussian | Broad Gaussian | — |

Brush geometry reads the established scene and velocity earlier in the frame. The draw pass's own destination is not a brush sampler. A hook can move data between stages by publishing it from a different GPU pass and connecting that pass before the consumer.

## Recipes

### Taper the ribbon without narrowing the tips to zero

```stacksl
: brushStyle
    >stroke
    <stroke .x 3.141593 * sin 0.75 * 0.45 +
    1 1 0 vec4
;
```

### Wave in world space

```stacksl
: brushOffset
    >stroke
    0
    <stroke .x 6.283185 * <stroke .z 6.283185 * + sin 0.04 *
    0 0 vec4
;
```

### Draw paths in an authored vortex

Set **Velocity integration** to one and clear the pool. This edits the field used to integrate new ribbons on the visible surfaces. It does not move the balls themselves.

```stacksl
: guideVelocity
    >where
    0.5 <where .y - 180 *
    <where .x 0.5 - 180 *
    1 0 vec4
;
```

Restore `sample0-abs` as the body to return to projected scene velocity. For a known vertical test vector, use `drop 0 120 1 120 vec4`.

### Use a quadtree cell's luminance range

This uses `max - min` from the extent plane as a thickness multiplier. The value plane at sampler 0 provides variance in its z lane and mean in y. The regional branch runs at birth.

```stacksl
: regionalStyle
    sample1-abs >extent
    1
    <extent .z <extent .y - 3 * 0.7 + 0.7 1.8 clamp
    1 0 vec4
;
```

### Balance the signed pigment before accumulation

```stacksl
: pigmentStyle
    >error
    <error .x <error .y 0.92 * <error .z 0.85 * 0 vec4
;
```

The same idiom can be used in **compositeTone** for grading the final look. The pigment hook changes future feedback. The composite hook changes the current display.
