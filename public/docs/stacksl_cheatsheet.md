# StackSL cheatsheet

<div class="stacksl-reference" markdown>

StackSL lowers to `vec4 stack(vec4 stack_in)`. The usual input convention is `vec4(x, y, z_or_hash, time)`, the stack starts with that input, and every word works with `vec4` values.

Stack signatures use `a b -- c`, where the top of stack is on the right. If a word needs a missing value, the compiler fills by wrap/default convention and reports it in diagnostics.

## Mental model

| Stage | Meaning |
| --- | --- |
| Push | Numbers, colors, `uv`, `p`, `prev`, external reads, vector literals, and store reads put values on the stack. |
| Transform | Math, swizzles, noise, color grading, and suffix modifiers consume values and push results. |
| Sample | `sample`, `sample-abs`, and `gather` read configured textures, usually the previous frame; coordinates are raw by default and `.w` variants wrap. |
| Return | The top value becomes the color unless explicit `->0` through `->3` writes override channels. |

## Inputs and constants

| Word | Pushes | Use |
| --- | --- | --- |
| `?name` | host GLSL value | read an existing host-side GLSL `vec4`, for example `?pc.foo` or `?pc.inData.p[idx].posvel.Z`. |
| `[x]`, `[x,y]`, `[x,y,z]`, `[x,y,z,w]` | vector literal | bracketed vector literal; missing z/w use StackSL's usual `0,1` fillers. |
| `black`, `blue`, `cyan`, `gray`, `green`, `grey`, `magenta`, `orange`, `red`, `white`, `yellow` | named color | RGBA colors. |
| `e`, `pi`, `tau` | scalar constant | splatted to all four channels. |
| `hash`, `h` | `vec4(hash)` | third coordinate or per-pixel seed from `stack_in.z`. |
| `input`, `in` | `stack_in` | the full `vec4(x, y, z_or_hash, time)`. |
| `p`, `pos`, `center`, `centered` | `vec4(uv.xy - 0.5, 0, 1)` | centered coordinate for rings, angles, radial waves. |
| `time`, `t` | `vec4(time)` | time from `stack_in.w`. |
| `uv` | `vec4(uv.xy, 0, 1)` | normalized texture coordinate in `0..1`. |

## Kotlin helpers

These helpers compile StackSL to GLSL and run it through a tiny on-demand compute shader. They need a running `Renderer` in context.

| Helper | Returns | Use |
| --- | --- | --- |
| `slEval("uv length", vec(1,2,3,4))` | `Vec4` | evaluate one input vector. |
| `slGlsl("uv length")` | `String` | return the generated GLSL. |
| `slSweep("uv length", vec(1,2,3,4) to vec(4,3,2,1))` | `StackSLSweep` | evaluate 100 interpolated inputs; `.h` shows the channel plot and stats. |

## Stack words

All values are `vec4`. Swizzles turn selected channels back into a `vec4`: one channel is splatted, two channels become `xy,0,1`, three become `xyz,1`, and four stay four-wide. The dot prefix is optional.

| Word | Stack | Effect |
| --- | --- | --- |
| `.x`, `.xy`, `.rgb`, `w`, `rgba`, etc. | `a -- b` | swizzle; channels may use `xyzw` or `rgba`. |
| `drop` | `a --` | discard top. |
| `dup` | `a -- a a` | duplicate top. |
| `nip` | `a b -- b` | drop second item. |
| `noop` | `--` | do nothing. |
| `over` | `a b -- a b a` | copy second item. |
| `rot` | `a b c -- b c a` | rotate third item to top. |
| `swap` | `a b -- b a` | swap top two. |
| `tuck` | `a b -- b a b` | copy top under the previous item. |
| `vec2`, `v2` | `x y -- vec` | construct from the `.x` channel of stack values. |
| `vec3`, `v3` | `x y z -- vec` | construct from the `.x` channel of stack values. |
| `vec4`, `v4` | `x y z w -- vec` | construct from the `.x` channel of stack values. |

## Number forms and suffixes

Suffixes let you write modulation where you are already thinking about a number. The same modifiers work as numeric literals, top-of-stack amplitude operators, and top-of-stack driver operators.

| Form | Example | Means |
| --- | --- | --- |
| Channel suffix | `0.7r`, `0.2g`, `0.1b`, `1a` | push a channel-weighted vector. |
| Plain number | `0.25` | push `vec4(0.25)`. |
| Pulse wave | `0.5pulse4d0.2` | amount times a pulse train; `d` sets duty fraction. |
| Range vector | `0.1_0.2` | push `vec4(0.1, 0.133333, 0.166667, 0.2)`; useful for four memory scales. |
| Sample hold | `0.5sh10` | amount times stepped random values, changing at integer buckets of `time * 10`. |
| Time suffix | `0.48t` | push `time 0.48 *`. |
| Time wave | `0.5c`, `0.5s2`, `0.5tri3p0.2` | amount times a normalized oscillator; number after the modifier is frequency, `p` is phase. |
| Top amplitude | <code>expr &#124;c1.8p0.2</code> | consume `expr` as amount and multiply by a time/spatial/noise factor. |
| Top driver | `expr ]wr18` | consume `expr` as the oscillator/noise driver; amplitude is one. |

## Modifier alphabet

`|` keeps the default driver, usually time or input coordinates. `]` changes the driver to the consumed top value.

| Group | Modifiers | What they do |
| --- | --- | --- |
| Coordinates | `ang`, `cx`, `cy`, `hash`, `rad`, `x`, `y` | use input `uv`, centered `uv`, radial distance, angle, or hash. |
| Oscillators | `c`, `pulse`, `s`, `saw`, `sq`, `tri` | cosine, pulse train, sine, saw, square, and triangle waves in `0..1`. |
| Random time | `j`, `n`, `sh` | jitter hash, smooth value noise, and sample-hold hash from scalar phase; default phase is `time` in `.w`. |
| Spatial noise | `fbm`, `grain`, `noise` | noise/fbm/hash over coordinates for offsets, masks, and material texture. |
| Spatial waves | `wa`, `wr`, `wx`, `wy` | sine waves across angle, radius, x, or y. |
| Time and channels | `a`, `b`, `g`, `r`, `t` | time scalar from `.w`, or put the amount into one RGBA channel. |

Examples: `0.4c2`, `p length |pulse8d0.15`, `p ]fbm5`, `prev xy ]c3p0.1`.

## Math and logic

| Word | Stack | Effect |
| --- | --- | --- |
| `!=`, `<>`, `neq` | `a b -- mask` | not-equal comparison mask in `0..1`. |
| `*`, `mul` | `a b -- c` | component-wise multiplication. |
| `+`, `add` | `a b -- c` | component-wise addition. |
| `-`, `sub` | `a b -- c` | component-wise subtraction. |
| `/`, `div` | `a b -- c` | safe divide with small-denominator protection. |
| `1-` | `a -- b` | one minus value, `1 - a`. |
| `<`, `lt` | `a b -- mask` | less-than comparison mask in `0..1`. |
| `<=`, `lte` | `a b -- mask` | less-than-or-equal comparison mask in `0..1`. |
| `=`, `eq` | `a b -- mask` | equality comparison mask in `0..1`. |
| `>`, `gt` | `a b -- mask` | greater-than comparison mask in `0..1`. |
| `>=`, `gte` | `a b -- mask` | greater-than-or-equal comparison mask in `0..1`. |
| `abs` | `a -- b` | GLSL-style unary math. |
| `acos` | `a -- b` | inverse cosine. |
| `and` | `a b -- mask` | boolean-ish mask using threshold `0.5`. |
| `asin` | `a -- b` | inverse sine. |
| `atan` | `a -- b` | inverse tangent. |
| `atan2` | `y x -- a` | two-argument arctangent. |
| `ceil` | `a -- b` | ceiling. |
| `cos` | `a -- b` | cosine. |
| `exp` | `a -- b` | exponential. |
| `floor` | `a -- b` | floor. |
| `fract` | `a -- b` | fractional part. |
| `inversesqrt` | `a -- b` | inverse square root. |
| `log` | `a -- b` | natural log. |
| `max` | `a b -- c` | binary max; with one explicit value, reduce RGBA. |
| `min` | `a b -- c` | binary min; with one explicit value, reduce RGBA. |
| `mix`, `lerp` | `a b t -- c` | raw GLSL mix; `t` is not clamped. |
| `mix.c`, `lerp.c` | `a b t -- c` | mix with `t` hard-clamped to `0..1`. |
| `mix.sc`, `lerp.sc` | `a b t -- c` | mix with `t` soft-clamped toward `0..1`. |
| `mod` | `a b -- c` | safe modulo with small-denominator protection. |
| `neg`, `negate` | `a -- b` | unary negation. |
| `not` | `a -- mask` | boolean-ish inversion using threshold `0.5`. |
| `or` | `a b -- mask` | boolean-ish mask using threshold `0.5`. |
| `pow` | `a b -- c` | raw GLSL power. |
| `pow.safe` | `a b -- c` | safe absolute base power. |
| `select`, `if` | `false true condition -- c` | choose per lane with a boolean-ish `0.5` threshold. |
| `sin` | `a -- b` | sine. |
| `smoothstep` | `lo hi x -- c` | smooth threshold. |
| `sqrt` | `a -- b` | square root. |
| `step` | `edge x -- c` | GLSL step with defaults if needed. |
| `tan` | `a -- b` | tangent. |

Arity variants use `word.N` for explicit consumption and `word.all` for stack blasts where supported. Examples: `average.4`, `median.5`, `min.all`, `sort.all`.

## Reducers and geometry

| Word | Stack | Effect |
| --- | --- | --- |
| `average`, `avg`, `mean` | `a -- s` | mean of RGBA. |
| `cross` | `a b -- c` | cross of xyz, alpha set to one. |
| `distance2` | `a b -- s` | distance between `a.xy` and `b.xy`, splatted. |
| `distance3` | `a b -- s` | distance between `a.xyz` and `b.xyz`, splatted. |
| `distance` | `a b -- s` | distance between full `vec4` values, splatted. |
| `dot` | `a b -- s` | dot product splatted to RGBA. |
| `intermediate` | `values -- values` | adjacent midpoints; use `.2` through `.8` or `.all`. |
| `length`, `len`, `length2`, `len2` | `a -- s` | length of `a.xy`, splatted. |
| `length3`, `len3` | `a -- s` | length of `a.xyz`. |
| `length4`, `len4` | `a -- s` | length of full `vec4`. |
| `luma` | `color -- s` | Rec.709 luminance splatted. |
| `median`, `med` | `a -- s` | middle-ish channel value. |
| `normalize` | `a -- b` | component vector normalize. |
| `rotate` | `point angle -- point` | rotate `point.xy` by `angle.x` radians. |
| `rotates` | `point center angle -- point` | rotate `point.xy` around `center.xy` by `angle.x` radians. |
| `sort` | `values -- values` | stack-wide component sort; use `.2` through `.8` or `.all`. |
| `standoff` | `a b c -- d` | `safeNormalize(a - b) * c + b`, normalizing over all four lanes. |
| `standoff2` | `a b c -- d` | same as `standoff`, but the safe normalization length is computed over `xy`; `z/w` are still carried and scaled. |
| `standoff3` | `a b c -- d` | same as `standoff`, but the safe normalization length is computed over `xyz`; `w` is still carried and scaled. |
| `vmax`, `maximum` | `a -- s` | component max of RGBA. |
| `vmin`, `minimum` | `a -- s` | component min of RGBA. |
| `>x`, `>y`, `>z`, `>w` | `a b -- c` | replace one lane of `a` with `b.x`. |

## Creative operators

| Word | Stack | What it does |
| --- | --- | --- |
| `alpha` | `base modulation range -- alpha` | raw EMA-alpha time-constant modulation: decay rate is multiplied by `pow(range, modulation)`; negative base alpha keeps a negative output sign. |
| `alpha.c` | `base modulation range -- alpha` | clamped variant: base alpha is clamped to `1e-6..0.999999`, modulation to `-1..1`. |
| `alpha.sc` | `base modulation range -- alpha` | soft-clamped variant for bounded modulation without a hard edge. |
| `bitcrush`, `bit-crush` | `value bits -- out` | quantize normalized lanes to a bit depth; short form defaults to `4`, explicit forms are `bitcrush.1` and `bitcrush.2`. |
| `contrast` | `color amount -- color` | contrast around mid gray; short form amount defaults to `1.2`. |
| `ema` | `current previous alpha -- smooth` | raw-alpha exponential moving average; use `prev 0.006_0.09 ema` for RGBA memories at different speeds. |
| `ema.c`, `ema.sc` | `current previous alpha -- smooth` | hard- or soft-clamped alpha variants. |
| `ema-diff`, `diff-ema`, `dema` | `current previous alpha -- diff` | raw-alpha current minus EMA, useful for edges and motion energy. |
| `ema-diff.c`, `ema-diff.sc`, `diff-ema.c`, `diff-ema.sc`, `dema.c`, `dema.sc` | `current previous alpha -- diff` | hard- or soft-clamped alpha variants. |
| `fbm` | `coord -- s` | five-octave fractal noise. |
| `fm` | `phase carrier mod -- wave` | frequency-modulated sine color field; has defaults when short. |
| `fmchirp` | `x freq mod modPhase phase chirp modChirp inputPow outputPow -- out` | FM residual curve with signed power warping; `fmchirp.8` uses `input.x` as implicit x. |
| `grade` | `color lift gain gamma -- color` | color grading; short form uses neutral lift/gain/gamma. |
| `gray-code`, `graycode`, `gray-encode` | `value -- out` | convert normalized 16-bit lanes into Gray code. |
| `gray-decode`, `ungray` | `value -- out` | decode normalized 16-bit Gray-code lanes. |
| `lfsr` | `state -- out` | step a coupled `vec4` 16-bit LFSR. |
| `mirror` | `a -- b` | triangle-wrap values into `0..1`. |
| `noise`, `perlin` | `coord -- s` | value noise from `coord.xy + coord.zw * 0.07`. |
| `palette`, `cos-palette`, `cosine-palette` | `t a b c d -- color` | cosine palette `a + b*cos(t*c + d)`; one-value short form gives a useful default palette. |
| `pulse` | `x center width -- mask` | soft band around `center`; with fewer than three values defaults to center `0.5`, width `0.08`. |
| `sat`, `saturate`, `clamp` | `color -- color` | clamp all channels to `0..1`. |
| `saturation`, `sat-color` | `color amount -- color` | adjust color saturation; short form amount defaults to `1`. |
| `tonemap` | `color -- color` | filmic-ish compression for bright feedback. |
| `xor`, `bitxor` | `a b -- out` | normalized 16-bit lane XOR. |

## Color spaces

| Word | Meaning |
| --- | --- |
| `oklab`, `oklab2rgb`, `from-oklab` | convert OKLab-like values back to RGB. |
| `rgb2oklab`, `to-oklab` | convert RGB to perceptual-ish OKLab coordinates. |
| `rgb2ryb`, `to-ryb` | convert RGB to RYB for painterly mixing without clamping. |
| `rgb2ryb.c`, `to-ryb.c` | clamped RGB-to-RYB conversion. |
| `ryb`, `ryb2rgb`, `from-ryb` | convert paint-like RYB color to RGB without clamping. |
| `ryb.c`, `ryb2rgb.c`, `from-ryb.c` | clamped RYB-to-RGB conversion. |

Good trick: do math in `rgb2oklab`, especially channel offsets and contrast, then return through `oklab`.

## Definitions

Define reusable words with `: name ... ;`. A call consumes one input value, runs the definition with that value as its local stack/input, then pushes the result.

```diffstatic {data-lang=stacksl}
: ring
  length 0.5 0.06 pulse
;

p ring white *
```

Definitions are inlined into GLSL, so shared math can still be optimized.

## Feedback sampling, store, and host values

| Word | Stack | Meaning |
| --- | --- | --- |
| `0->`, `1->`, `2->`, `3->` | `-- value` | read stored R/G/B/A channel at the current pixel; in the ping-pong harness this aliases previous-frame RGBA. |
| `->0`, `->1`, `->2`, `->3` | `value --` | write `value.x` to that output channel; mentioned writes take priority at return. |
| `->?name` | `value --` | write the top value to an existing host-side GLSL l-value, for example `->?pc.foo` or `->?pc.inData.p[idx].posvel.Z`. |
| `gather`, `gather0`, `gather1`, `gather2`, `gather3` | `offset -- vec4` | raw `textureGather` red channel around `uv + offset.xy`. |
| `gather.w`, `gather0.w`, `gather1.w`, `gather2.w`, `gather3.w` | `offset -- vec4` | wrapped `textureGather` around `fract(uv + offset.xy)`. |
| `prev`, `state` | `-- color` | sample previous frame at current `uv`. |
| `sample`, `sample0`, `sample1`, `sample2`, `sample3` | `offset -- color` | sample a configured texture at raw `uv + offset.xy`. |
| `sample.w`, `sample0.w`, `sample1.w`, `sample2.w`, `sample3.w` | `offset -- color` | sample at wrapped `fract(uv + offset.xy)`. |
| `sample-abs`, `sample0-abs`, `sample1-abs`, `sample2-abs`, `sample3-abs` | `coord -- color` | sample at raw absolute `coord.xy`. |
| `sample-abs.w`, `sample0-abs.w`, `sample1-abs.w`, `sample2-abs.w`, `sample3-abs.w` | `coord -- color` | sample at wrapped absolute `fract(coord.xy)`. |

For long-term dynamics, think of RGBA as four memories. Use range vectors for rates, nearby `sample` offsets for diffusion, and explicit channel writes when you want separate state variables.

## Copyable patterns

Centered soft ring:

```diffstatic {data-lang=stacksl}
p length 0.5 0.08 pulse white *
```

Drifting feedback with a pulsed injection:

```diffstatic {data-lang=stacksl}
0.004wx7 0.004wy5 vec2 sample.w
0.986 *
p length 0.42 0.035 pulse palette 0.05 *
+
saturate
```

Subroutine called twice with different coordinates:

```diffstatic {data-lang=stacksl}
: ridge
  fbm 0.45 0.08 pulse
;

p 5.0 * 0.2t + ridge
p 7.0 * 0.13t - ridge
0.5 * +
palette
```

Nearby samples plus four slow memory scales:

```diffstatic {data-lang=stacksl}
prev
dup xy 0.012 * sample.w
swap zw 0.009 * sample.w
+ 0.5 *
p ]wr9 0.006 * 0.004c0.17 +
p ]wa7 0.006 * 0.004s0.11 +
vec2 sample.w
0.65 * +
uv 5.0 * prev 0.4 * + 0.02t + fbm
dup 0.23 + dup 0.37 + dup 0.61 + vec4
0.08 * +
prev 0.006_0.09 ema
dup average 0.015pulse0.23d0.09 + +
mirror saturate
```

</div>
