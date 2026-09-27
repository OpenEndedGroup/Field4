# StackSL cheatsheet

*Every public declaration used below is listed, with its KDoc, in the codebase reference: [`stacksl.kt`](reference/graphics.md#stacksl-kt). Start from [what's already in the box](reference/index.md).*

<div class="stacksl-reference" markdown>

StackSL lowers to `vec4 stack(vec4 stack_in)`. The usual input convention is `vec4(x, y, z_or_hash, time)`, the stack starts with that input, and every word works with `vec4` values.

Stack signatures use `a b -- c`, where the top of stack is on the right. If a word needs a missing value, the compiler fills by wrap/default convention and reports it in diagnostics.

## Mental model

The smallest complete program is one word and a clamp:

```diffstatic {data-lang=stacksl}
uv saturate
```

`uv` pushes `vec4(uv.xy, 0, 1)`, `saturate` clamps it, and whatever is on top of the stack at the
end becomes the colour — so that frame is a red-green gradient. Every program below is the same
shape: push something, transform it, let the top value fall out as the colour.

<div class="field-data-table" markdown>

<div class="field-reference" data-code-lang="stacksl" markdown>

| Stage | Meaning |
| --- | --- |
| Push | Numbers, colors, `uv`, `p`, `prev`, external reads, vector literals, and store reads put values on the stack. |
| Transform | Math, swizzles, noise, color grading, and suffix modifiers consume values and push results. |
| Sample | `sample`, `sample-abs`, and `gather` read configured textures, usually the previous frame; coordinates are raw by default and `.w` variants wrap. |
| Return | The top value becomes the color unless explicit `->0` through `->3` writes override channels. |

</div>

</div>

## Inputs and constants

`uv` runs `0..1` across the frame; `p` is the same coordinate centred on zero, which is the one to
reach for whenever a figure has a middle.

```diffstatic {data-lang=stacksl}
p 4 * phacelle palette saturate
```

Worked: [The input convention](stacksl_examples.md#the-input-convention), and the whole
[rendered gallery](stacksl_examples.md#rendered-gallery) opens on `uv saturate`.

<div data-code-lang="stacksl" markdown>

| Word | Pushes | Use |
| --- | --- | --- |
| `?name` | host GLSL value | read an existing host-side GLSL `vec4`, for example `?pc.foo` or `?pc.inData.p[idx].posvel.Z`. |
| `[x]`, `[x,y]`, `[x,y,z]`, `[x,y,z,w]` | vector literal | bracketed vector literal; missing z/w use StackSL's usual `0,1` fillers. |
| `[3x,2y,2z,0x]` | stack selector | build a vector from stack-indexed swizzles; `0` is top of stack, so this consumes four values. Compact swizzles such as `[2yz]` are also valid. |
| `black`, `blue`, `cyan`, `gray`, `green`, `grey`, `magenta`, `orange`, `red`, `white`, `yellow` | named color | RGBA colors. |
| `e`, `pi`, `tau` | scalar constant | splatted to all four channels. |
| `hash`, `h` | `vec4(hash)` | third coordinate or per-pixel seed from `stack_in.z`. |
| `input`, `in` | `stack_in` | the full `vec4(x, y, z_or_hash, time)`. |
| `p`, `pos`, `center`, `centered` | `vec4(uv.xy - 0.5, 0, 1)` | centered coordinate for rings, angles, radial waves. |
| `time`, `t` | `vec4(time)` | time from `stack_in.w`. |
| `uv` | `vec4(uv.xy, 0, 1)` | normalized texture coordinate in `0..1`. |

</div>

## Kotlin helpers

These helpers compile StackSL to GLSL and run it through a tiny on-demand compute shader. They need a running `Renderer` in context.

<div data-code-lang="kotlin" markdown>

| Helper | Returns | Use |
| --- | --- | --- |
| `slEval("uv length", vec(1,2,3,4))` | `Vec4` | Compiles and evaluates one input vector on the GPU, returning a `Vec4`. Use this for a single sample of a program. |
| `slGlsl("uv length")` | `String` | Returns the generated GLSL as a string. Inspect it when a word sequence produces an unexpected result. |
| `slSweep("uv length", vec(1,2,3,4) to vec(4,3,2,1))` | `StackSLSweep` | Evaluates 100 interpolated inputs between the two endpoints. The returned sweep’s `.h` view plots each channel and its statistics. |

</div>

## Stack words

All values are `vec4`. Swizzles turn selected channels back into a `vec4`: one channel is splatted, two channels become `xy,0,1`, three become `xyz,1`, and four stay four-wide. The dot prefix is optional.

These are how a program reaches a value it has already computed. Below, `dup` copies the polar
coordinate, `y` takes the angle lane off the copy, `0.2t` drifts it with time, and `>y` writes the
result back into the lane it came from — so the angle turns while the radius is left alone:

```diffstatic {data-lang=stacksl}
p polar
dup y 0.2t + >y
cart
fbm
sigmoid
palette
```

Worked: [Swizzles and constructors](stacksl_examples.md#swizzles-and-constructors).

<div data-code-lang="stacksl" markdown>

| Word | Stack | Effect |
| --- | --- | --- |
| `.x`, `.xy`, `.rgb`, `w`, `rgba`, etc. | `a -- b` | swizzle; channels may use `xyzw` or `rgba`. |
| `drop`<br>`1 2 drop` | `a --` | Discards the top value. Everything below it stays in the same order. |
| `dup`<br>`uv dup` | `a -- a a` | Copies the top value without consuming it. Use the two copies when one result feeds two later operations. |
| `nip`<br>`1 2 nip` | `a b -- b` | Removes the second value while keeping the top. This discards an earlier intermediate result. |
| `noop` | `--` | Leaves the stack unchanged. It can occupy a program slot without changing the result. |
| `over`<br>`1 2 over` | `a b -- a b a` | Copies the second value onto the top. Both original values remain available below it. |
| `rot`<br>`1 2 3 rot` | `a b c -- b c a` | Moves the third value to the top while preserving the order of the other two. The stack effect is `a b c -- b c a`. |
| `swap`<br>`1 2 swap` | `a b -- b a` | Exchanges the top two values. Use it to reverse operands before subtraction or division. |
| `tuck`<br>`1 2 tuck` | `a b -- b a b` | Copies the top value below the previous value. The original top remains on top. |
| `vec2`, `v2` | `x y -- vec` | construct from the `.x` channel of stack values. |
| `vec3`, `v3` | `x y z -- vec` | construct from the `.x` channel of stack values. |
| `vec4`, `v4` | `x y z w -- vec` | construct from the `.x` channel of stack values. |

</div>

## Number forms and suffixes

Suffixes let you write modulation where you are already thinking about a number. The same modifiers work as numeric literals, top-of-stack amplitude operators, and top-of-stack driver operators.

`]sh10` below is the driver form: it consumes the scaled coordinate as its phase and holds a random
value across each bucket of it, which is what turns a smooth gradient into blocks.

```diffstatic {data-lang=stacksl}
uv 6 * ]sh10 palette saturate
```

Worked: [Modifiers, ranges, and memories](stacksl_examples.md#modifiers-ranges-and-memories).

<div class="field-data-table" markdown>

<div data-code-lang="stacksl" markdown>

| Form | Example | Means |
| --- | --- | --- |
| Channel suffix | `0.7r`, `0.2g`, `0.1b`, `1a` | push a channel-weighted vector. |
| Plain number | `0.25` | push `vec4(0.25)`. |
| Pulse wave | `0.5pulse4d0.2` | amount times a pulse train; `d` sets duty fraction. |
| Stacked suffixes | `0.4s3q8`, `p ]fbm4q8` | suffixes compose left-to-right; later suffixes use the previous suffix result as their driver. |
| Range vector | `0.1_0.2` | push `vec4(0.1, 0.133333, 0.166667, 0.2)`; useful for four memory scales. |
| Sample hold | `0.5sh10` | amount times stepped random values, changing at integer buckets of `time * 10`. |
| Time suffix | `0.48t` | push `time 0.48 *`. |
| Time wave | `0.5c`, `0.5s2`, `0.5tri3p0.2` | amount times a normalized oscillator; number after the modifier is frequency, `p` is phase. |
| Top amplitude | <code>expr &#124;c1.8p0.2</code> | consume `expr` as amount and multiply by a time/spatial/noise factor. |
| Top driver | `expr ]wr18` | consume `expr` as the oscillator/noise driver; amplitude is one. |

</div>

</div>

## Modifier alphabet

`|` keeps the default driver, usually time or input coordinates. `]` changes the driver to the consumed top value.

`]wr14` is a sine wave across radius at fourteen cycles, driven by the centred coordinate it
consumes — concentric rings, in one token:

```diffstatic {data-lang=stacksl}
p ]wr14 palette saturate
```

<div class="field-data-table" markdown>

<div data-code-lang="stacksl" markdown>

| Group | Modifiers | What they do |
| --- | --- | --- |
| Coordinates | `ang`, `cx`, `cy`, `hash`, `rad`, `x`, `y` | use input `uv`, centered `uv`, radial distance, angle, or hash. |
| Oscillators | `c`, `pulse`, `s`, `saw`, `sq`, `tri` | cosine, pulse train, sine, saw, square, and triangle waves in `0..1`. |
| Shaping | `q` | quantize the current suffix result to `N` levels; `q8` gives eight levels. |
| Random time | `j`, `n`, `sh` | jitter hash, smooth value noise, and sample-hold hash from scalar phase; default phase is `time` in `.w`. |
| Spatial noise | `fbm`, `grain`, `noise` | noise/fbm/hash over coordinates for offsets, masks, and material texture. |
| Spatial waves | `wa`, `wr`, `wx`, `wy` | sine waves across angle, radius, x, or y. |
| Time and channels | `a`, `b`, `g`, `r`, `t` | time scalar from `.w`, or put the amount into one RGBA channel. |

</div>

</div>

Examples: `0.4c2`, `0.4s3q8`, `p length |pulse8d0.15q4`, `p ]fbm5q8`, `prev xy ]c3p0.1`.

## Math and logic

Eighteen stripes: take the `x` lane, scale it, fold it through `sin`, then bring the `-1..1` result
back into `0..1` before colouring it.

```diffstatic {data-lang=stacksl}
uv x 18 * sin 0.5 + 0.5 * palette saturate
```

<div data-code-lang="stacksl" markdown>

| Word | Stack | Effect |
| --- | --- | --- |
| `!=`, `<>`, `neq` | `a b -- mask` | not-equal comparison mask in `0..1`. |
| `*`, `mul` | `a b -- c` | Multiplies corresponding lanes. A scalar literal is splatted, so `uv 2 *` scales every channel. |
| `+`, `add` | `a b -- c` | Adds corresponding lanes. Use a vector literal when each channel needs a different offset. |
| `-`, `sub` | `a b -- c` | Subtracts the top vector from the one below it. Operand order matters: `3 1 -` returns 2. |
| `/`, `div` | `a b -- c` | Divides the lower vector by the top vector lane by lane, protecting small denominators. It is the guarded division form rather than raw GLSL division. |
| `1-` | `a -- b` | Subtracts each lane from one. On a `[0,1]` mask it swaps foreground and background. |
| `<`, `lt` | `a b -- mask` | less-than comparison mask in `0..1`. |
| `<=`, `lte` | `a b -- mask` | less-than-or-equal comparison mask in `0..1`. |
| `=`, `eq` | `a b -- mask` | equality comparison mask in `0..1`. |
| `>`, `gt` | `a b -- mask` | greater-than comparison mask in `0..1`. |
| `>=`, `gte` | `a b -- mask` | greater-than-or-equal comparison mask in `0..1`. |
| `abs` | `a -- b` | Takes the absolute value of each lane. Negative and positive inputs of equal magnitude become equal. |
| `acos` | `a -- b` | Computes inverse cosine per lane, returning radians. Inputs must be in the cosine range `[-1,1]`. |
| `and` | `a b -- mask` | Thresholds the absolute input lanes at 0.5, then takes their minimum. A lane is true only when both inputs pass the threshold. |
| `asin` | `a -- b` | Computes inverse sine per lane, returning radians. Inputs must be in `[-1,1]`. |
| `atan` | `a -- b` | Computes inverse tangent per lane, returning radians. |
| `atan2`<br>`1 0 atan2` | `y x -- a` | Computes the angle from y and x, with x on top of the stack. The result is in radians and distinguishes quadrants. |
| `ceil` | `a -- b` | Rounds each lane upward to an integer-valued float. For example, `1.2 ceil` produces 2 in every lane. |
| `cos`<br>`time cos` | `a -- b` | Computes cosine per lane from angles in radians. Its output lies in `[-1,1]`. |
| `exp` | `a -- b` | Raises e to each lane’s value. Large positive inputs can overflow. |
| `floor`<br>`1.8 floor` | `a -- b` | Rounds each lane downward to an integer-valued float. Negative values round toward negative infinity. |
| `fract`<br>`1.8 fract` | `a -- b` | Subtracts the floor from each lane, giving its fractional part in `[0,1)`. Use it to repeat a ramp. |
| `inversesqrt` | `a -- b` | Computes the reciprocal square root per lane. Use positive inputs when a finite result is required. |
| `log` | `a -- b` | Computes natural logarithm per lane. Use positive inputs; zero and negative values do not produce finite real results. |
| `max` | `a b -- c` | GLSL-style binary max. Use `max.1` or `vmax` to reduce RGBA channels. |
| `min` | `a b -- c` | GLSL-style binary min. Use `min.1` or `vmin` to reduce RGBA channels. |
| `mix`, `lerp` | `a b t -- c` | raw GLSL mix; `t` is not clamped. |
| `mix.c`, `lerp.c` | `a b t -- c` | mix with `t` hard-clamped to `0..1`. |
| `mix.sc`, `lerp.sc` | `a b t -- c` | mix with `t` soft-clamped toward `0..1`. |
| `mod` | `a b -- c` | safe modulo with small-denominator protection. |
| `neg`, `negate` | `a -- b` | Negates each component, reversing its sign. This is distinct from `1-`, which complements around one. |
| `not` | `a -- mask` | Inverts a boolean-like mask using a 0.5 threshold. Use it after a comparison to reverse the selection. |
| `or` | `a b -- mask` | Combines two boolean-like vectors using the 0.5 threshold. A true input can make the corresponding output lane true. |
| `pow` | `a b -- c` | Raises each lane of the lower value to the corresponding power on top. This follows raw GLSL power behavior; use `pow.safe` for an absolute base. |
| `pow.safe` | `a b -- c` | Applies power using the absolute base and the safe form. Choose it when signed input may cross zero. |
| `select`, `if` | `false true condition -- c` | choose per lane with a boolean-ish `0.5` threshold. |
| `sin`<br>`time sin` | `a -- b` | Computes sine per lane from angles in radians. Map its `[-1,1]` range before using it directly as a colour. |
| `smoothstep` | `lo hi x -- c` | Interpolates smoothly from zero to one between the lower and upper edges. The stack order is low edge, high edge, then the value to test. |
| `sqrt` | `a -- b` | Computes the square root of each lane. Negative inputs do not have a real-valued result. |
| `step` | `edge x -- c` | Returns zero below the edge and one at or above it, independently per lane. Push the edge before the value. |
| `tan` | `a -- b` | Computes tangent per lane from radians. Values grow rapidly near odd multiples of half pi. |

</div>

Arity variants use `word.N` for explicit consumption and `word.all` for stack blasts where supported. Examples: `average.4`, `median.5`, `min.1`, `min.all`, `sort.all`.

## Reducers and geometry

A reducer collapses a `vec4` to one number splatted across all four lanes, which is how a distance
becomes a shape: `length` of the centred coordinate is the radius, and `pulse` keeps a soft band at
one radius, so this is a ring.

```diffstatic {data-lang=stacksl}
p length 0.5 0.08 pulse white *
```

Worked: [Pulses, rings, and FM](stacksl_examples.md#pulses-rings-and-fm).

<div data-code-lang="stacksl" markdown>

| Word | Stack | Effect |
| --- | --- | --- |
| `average`, `avg`, `mean` | `a -- s` | Averages the four RGBA lanes and splats the result. This is a channel reduction, not an average of the whole stack. |
| `cross` | `a b -- c` | Computes the cross product of the xyz components and sets alpha to one. Parallel input directions give a zero xyz vector. |
| `distance2` | `a b -- s` | Measures Euclidean distance using xy only and splats the result. Ignore z and w when measuring screen-space separation. |
| `distance3` | `a b -- s` | Measures Euclidean distance using xyz and splats the result. The fourth channel does not affect the distance. |
| `distance` | `a b -- s` | Measures Euclidean distance across all four components and splats it. Use `distance2` or `distance3` when w is metadata rather than a coordinate. |
| `dot` | `a b -- s` | Sums the products of corresponding components and splats the result. All four lanes contribute. |
| `intermediate` | `values -- values` | adjacent midpoints; use `.2` through `.8` or `.all`. |
| `length`, `len`, `length2`, `len2`<br>`p length` | `a -- s` | Measures the length of xy and splats it. On `p`, this is distance from the image centre. |
| `length3`, `len3` | `a -- s` | Measures the xyz length and splats it. Use it for three-dimensional coordinates. |
| `length4`, `len4` | `a -- s` | Measures the full four-component length. A nonzero w therefore contributes to the result. |
| `luma` | `color -- s` | Computes brightness with weights 0.2126 red, 0.7152 green and 0.0722 blue. The scalar result is copied into all lanes. |
| `median`, `med` | `a -- s` | middle-ish channel value. |
| `normalize` | `a -- b` | component vector normalize. |
| `sum`<br>`[1,2,3,4] sum` | `a -- s` | Adds the RGBA components and splats the total. This reduces one vector, rather than adding several stack values. |
| `prod`, `product` | `a -- s` | Multiplies the RGBA components and splats the product. A zero component makes the whole result zero. |
| `any`, `all` | `a -- mask` | boolean-ish channel reducers using threshold `0.5`. |
| `x+y`, `xplusy` | `a -- s` | Adds the x and y lanes and splats the result. On `uv` this makes a diagonal ramp. |
| `xylen` | `a -- s` | Measures xy length, like `length`. It makes the chosen coordinate plane explicit in a longer program. |
| `rgbnorm`, `rgb-normalize` | `color -- color` | Normalizes the RGB vector while preserving alpha. Use it when colour direction should change independently of opacity. |
| `rotate` | `point angle -- point` | rotate `point.xy` by `angle.x` radians. |
| `rotates` | `point center angle -- point` | rotate `point.xy` around `center.xy` by `angle.x` radians. |
| `sort` | `values -- values` | stack-wide component sort; use `.2` through `.8` or `.all`. |
| `standoff` | `a b c -- d` | `safeNormalize(a - b) * c + b`, normalizing over all four lanes. |
| `standoff2` | `a b c -- d` | same as `standoff`, but the safe normalization length is computed over `xy`; `z/w` are still carried and scaled. |
| `standoff3` | `a b c -- d` | same as `standoff`, but the safe normalization length is computed over `xyz`; `w` is still carried and scaled. |
| `vmax`, `maximum`<br>`[1,2,3,4] vmax` | `a -- s` | Selects the largest RGBA component and splats it. Use ordinary `max` to compare two vectors lane by lane. |
| `vmin`, `minimum` | `a -- s` | Selects the smallest RGBA component and splats it. Use ordinary `min` to compare two vectors lane by lane. |
| `>x`, `>y`, `>z`, `>w` | `a b -- c` | replace one lane of `a` with `b.x`. |

</div>

## Domain transforms

These change the coordinate before anything is read at it, so they compose: six mirrored sectors,
then a twist that grows with radius, and only then the noise.

```diffstatic {data-lang=stacksl}
p 6 kaleido
1.7 twist
3.0 *
fbm
palette
saturate
```

Worked: [Domain transforms and shapers](stacksl_examples.md#domain-transforms-and-shapers).

<div data-code-lang="stacksl" markdown>

| Word | Stack | Effect |
| --- | --- | --- |
| `cart`, `cartesian`<br>`p polar cart` | `polar -- coord` | Converts radius and angle back into xy, preserving z and w. The angle is in radians. |
| `kaleido`, `kaleidoscope` | `coord sectors -- coord` | angular mirror/repeat over `sectors.x`; short form uses six sectors. |
| `polar`<br>`p polar` | `coord -- polar` | Converts xy into radius and angle, preserving z and w. The angle is in radians, so radius and angle can be transformed separately before `cart`. |
| `repeat` | `a -- b` | Applies `fract` per lane to wrap values into `[0,1)`. This repeats a coordinate or ramp rather than repeating program execution. |
| `spiral` | `coord amount -- coord` | polar twist by radius and log-radius terms; short form uses `tau`. |
| `tile` | `coord scale -- coord` | `vec4(fract(coord.xy * scale.xy), coord.zw)`; short form uses scale `1`. |
| `twist` | `coord amount -- coord` | rotate `coord.xy` by `radius * amount.x`; short form uses `tau`. |

</div>

## Creative operators

The shapers you reach for once a field exists. Here a noise field is remapped onto `0.2..0.8`,
quantised to six levels, and pushed off centre before it is coloured.

```diffstatic {data-lang=stacksl}
uv 5 * fbm
0.2 0.8 ramp
6 posterize
0.62 bias
palette
```

Worked: [Domain transforms and shapers](stacksl_examples.md#domain-transforms-and-shapers).

<div data-code-lang="stacksl" markdown>

| Word | Stack | What it does |
| --- | --- | --- |
| `alpha` | `base modulation range -- alpha` | raw EMA-alpha time-constant modulation: decay rate is multiplied by `pow(range, modulation)`; negative base alpha keeps a negative output sign. |
| `alpha.c` | `base modulation range -- alpha` | clamped variant: base alpha is clamped to `1e-6..0.999999`, modulation to `-1..1`. |
| `alpha.sc` | `base modulation range -- alpha` | soft-clamped variant for bounded modulation without a hard edge. |
| `band` | `x center width -- mask` | `step(center - abs(width), x) - step(center + abs(width), x)`; short form defaults to center `0.5`, width `0.1`. |
| `bias` | `value amount -- out` | `x / (((1 / b - 2) * (1 - x)) + 1)`, where `b = clamp(amount, 0.00001, 0.99999)`; short form uses neutral `0.5`. |
| `bitcrush`, `bit-crush` | `value bits -- out` | quantize normalized lanes to a bit depth; short form defaults to `4`, explicit forms are `bitcrush.1` and `bitcrush.2`. |
| `contrast` | `color amount -- color` | `(color - 0.5) * amount + 0.5`; short form amount defaults to `1.2`. |
| `ema` | `current previous alpha -- smooth` | `mix(previous, current, alpha)`; use `prev 0.006_0.09 ema` for RGBA memories at different speeds. |
| `ema.c`, `ema.sc` | `current previous alpha -- smooth` | hard- or soft-clamped alpha variants. |
| `ema-diff`, `diff-ema`, `dema` | `current previous alpha -- diff` | `current - mix(previous, current, alpha)`, useful for edges and motion energy. |
| `ema-diff.c`, `ema-diff.sc`, `diff-ema.c`, `diff-ema.sc`, `dema.c`, `dema.sc` | `current previous alpha -- diff` | hard- or soft-clamped alpha variants. |
| `fbm`<br>`p 4 * fbm` | `coord -- s` | Evaluates five-octave fractal noise from the input coordinates. Use it as a multiscale texture or displacement signal. |
| `fm` | `phase carrier mod -- wave` | `sin(phase + mod * sin(carrier))`; has defaults when short. |
| `fmchirp` | `x freq mod modPhase phase chirp modChirp inputPow outputPow -- out` | `x + spow(sin(phi + sin(mu)), outputPow)`, where `xw = spow(x, inputPow)`, `phi = tau * (freq*xw + phase + chirp*xw*xw)`, `mu = tau * (mod*xw + modPhase + modChirp*xw*xw)`, and `spow(x,e) = sign(x) * abs(x)^e`; `fmchirp.8` uses `input.x` as implicit x. |
| `gain` | `value amount -- out` | `mix(0.5*bias(2*x, a), 1 - 0.5*bias(2*(1 - x), a), step(0.5, x))`; short form uses neutral `0.5`. |
| `grade` | `color lift gain gamma -- color` | `pow(max((color + lift) * gain, 0), max(abs(gamma), 0.0001))`; short form uses neutral lift/gain/gamma. |
| `gray-code`, `graycode`, `gray-encode` | `value -- out` | convert normalized 16-bit lanes into Gray code. |
| `gray-decode`, `ungray` | `value -- out` | decode normalized 16-bit Gray-code lanes. |
| `lfsr` | `state -- out` | step a coupled `vec4` 16-bit LFSR. |
| `mirror` | `a -- b` | `1 - abs(2*fract(a/2) - 1)`. |
| `noise`, `perlin` | `coord -- s` | Evaluates value noise at `coord.xy + coord.zw * 0.07`. The extra channels therefore shift the sampled location. |
| `palette`, `cos-palette`, `cosine-palette` | `t a b c d -- color` | cosine palette `a + b*cos(t*c + d)`; one-value short form gives a useful default palette. |
| `clamp` | `value low high -- out` | GLSL clamp; one-value short form clamps to `0..1`. |
| `phacelle`, `directional-noise` | `point frequency offset normalization -- vec` | directional phasor noise; optionally consume a quoted pattern word before or after the arguments. |
| `posterize` | `value levels -- out` | quantize normalized lanes to a level count; short form uses `6`. |
| `pulse` | `x center width -- mask` | soft band around `center`; with fewer than three values defaults to center `0.5`, width `0.08`. |
| `ramp` | `x low high -- out` | remap `low..high` into a clamped `0..1` ramp. |
| `sat`, `saturate` | `color -- color` | Clamps all four channels to `[0,1]`. Put it at the end of a colour program when intermediate math may exceed display range. |
| `saturation`, `sat-color` | `color amount -- color` | adjust color saturation; short form amount defaults to `1`. |
| `sigmoid` | `x amount -- out` | logistic S curve around `0.5`; short form uses amount `8`. |
| `smooth` | `x -- out` | `smoothstep(0, 1, x)` as a one-word shaper. |
| `softplus` | `x amount -- out` | soft rectifier `log(1 + exp(k*x)) / k`; short form uses amount `4`. |
| `threshold` | `x edge -- mask` | `step(edge, x)`; short form uses edge `0.5`. |
| `tonemap` | `color -- color` | `vec4(max(color.rgb, 0) / (max(color.rgb, 0) + 1), color.a)`. |
| `xor`, `bitxor` | `a b -- out` | normalized 16-bit lane XOR. |

</div>

## Volumetric noise

`noise` and `fbm` above are two-dimensional. They read `coord.xy + coord.zw * 0.07`, so a `vec4`
carrying a position and a time is flattened into a plane, with z entering at a fourteenth of the
weight of x. That is a shear, not a third dimension: on a volume seen down z it reads as one fixed
pattern imprinted at every depth. These words read `input.xyz` (and `.xyzw`) properly.

Three differences from the 2D words:

- **The range really is `0..1`.** The 2D `fbm` sums five octaves at amplitudes totalling `0.96875`,
  so `fbm 0.5 -` sits about `0.016` low and a force built from it drifts instead of averaging out.
  These words divide by the amplitude sum they actually accumulated, so the range holds for any
  octave count, lacunarity and gain.
- **They cost more.** `curl3` is eighteen `fbm3` evaluations: a central difference along each of
  three axes, each arm a three-offset vector potential. Its short form already runs three octaves
  where the rest of the family runs five, because a curl is a derivative and at gain `0.5` and
  lacunarity `2` every octave contributes equally to one — more octaves add confetti, not richness.
- **They use their own hashes,** with no `sin()` in them, so the CPU reference in
  `VolumetricNoise.kt` computes the same field the shader does and the family can be unit-tested
  without a GPU.

Every word takes its coordinate first. The second slot is the word's own knob and the third is the
octave count, so `input 0.03 3 curl3.3` is "epsilon 0.03, three octaves".

<div data-code-lang="stacksl" markdown>

| Word | Stack | What it does |
| --- | --- | --- |
| `noise3` | `coord -- s` | 3D value noise over `coord.xyz`, exactly `0..1`. |
| `noise4` | `coord -- s` | 4D value noise over the whole `vec4`; use `.w` as time for a volume that animates without sliding. |
| `fbm3` | `coord octaves lacunarity gain -- s` | fractal 3D noise, exactly `0..1`. Short form is five octaves, lacunarity `2`, gain `0.5`; `fbm3.2` takes an octave count, `fbm3.4` takes all four. |
| `fbm4` | `coord octaves lacunarity gain -- s` | the same in four dimensions. |
| `curl3` | `coord epsilon octaves -- vec` | divergence-free curl noise, as `vec4(v.xyz, 0)`. The curl of a three-offset `fbm3` vector potential, so an advected particle swirls instead of piling into a sink. Short form uses epsilon `0.05` and three octaves. |
| `fbmd3` | `coord epsilon octaves -- vec` | `fbm3` and its gradient at once: `.x` is the value, `.yzw` the gradient, which is the normal of the isosurface through that point. |
| `warp3` | `coord strength octaves -- s` | domain-warped `fbm3` — `fbm3` sampled at a position displaced by another `fbm3` vector field. Filaments and folds rather than clouds. Still `0..1`. |
| `worley3`, `voronoi3` | `coord -- vec` | cellular noise, packed: `.x` is F1 (distance to the nearest feature point), `.y` is F2, `.z` is `F2 - F1` (near zero exactly on the cell walls, so it is the crack field), `.w` is a constant per cell for flat shading. `worley3 x` reads like `fbm x`; `worley3 z` draws the cracks. |

</div>

```diffstatic {data-lang=stacksl}
input 2.2 * curl3 xyz 0.4 *        # a divergence-free three-channel force
input 6 * worley3 z 1- 8 pow       # glowing cell walls
input 1.7 * warp3                  # filaments
input fbmd3 yzw normalize          # a surface normal from the noise field
```

## Color spaces

<div class="field-reference" data-code-lang="stacksl" markdown>

| Word | Meaning |
| --- | --- |
| `oklab`, `oklab2rgb`, `from-oklab` | convert OKLab-like values back to RGB. |
| `rgb2oklab`, `to-oklab` | convert RGB to perceptual-ish OKLab coordinates. |
| `rgb2ryb`, `to-ryb` | convert RGB to RYB for painterly mixing without clamping. |
| `rgb2ryb.c`, `to-ryb.c` | clamped RGB-to-RYB conversion. |
| `ryb`, `ryb2rgb`, `from-ryb` | convert paint-like RYB color to RGB without clamping. |
| `ryb.c`, `ryb2rgb.c`, `from-ryb.c` | clamped RYB-to-RGB conversion. |

</div>

Good trick: do math in `rgb2oklab`, especially channel offsets and contrast, then return through `oklab`.

## Definitions

Define reusable words with `: name ... ;`. A call consumes one input value, runs the definition with that value as its local stack/input, then pushes the result.

**A definition must therefore be net zero on the stack: consume its input, leave its result.** It is not an error: an unbalanced definition compiles, the surplus is pruned, and everything downstream silently takes the wrong operand. Open a definition with `>name`, which *moves* the top, and not `=name`, which *copies* it:

```diffstatic {data-lang=stacksl}
: half  >v  <v 0.5 *  ;      # right: consumes one, leaves one
: half  =v  <v 0.5 *  ;      # wrong: leaves its input under its result, so every call pushes two
```

`stack.auto` reports this. A slot whose program has an unbalanced definition, or any warning at all, writes a card into the box's `stack.auto` output channel and closes it again once the program is clean. Ask directly with `stackAutoShaderReport("…", "slotName")`, which returns a `StackAutoReport` carrying `problems`, `warnings` and `notes` and renders itself as HTML for `display()`.

Only definitions something actually *calls* are checked. A **root** program legitimately ends deeper than one — its stack starts with the program's input already on it, so `input x 12 *` ends at two — and so does a definition used only as an entrypoint (`{{ stack.auto.foo:tap }}`), because there it gets root semantics.

```diffstatic {data-lang=stacksl}
: ring
  length 0.5 0.06 pulse
;

p ring white *
```

Definitions are inlined into GLSL, so shared math can still be optimized.

## Word references

Prefix a word with `'` to put a compile-time reference to that word on the stack. `apply`, `call`, and `invoke` consume a word reference and a value, inline the referenced word with that value as its input, and push the result. Both `word value apply` and `value word apply` work.

<div data-code-lang="stacksl" markdown>

| Word | Stack | What it does |
| --- | --- | --- |
| `apply`, `call`, `invoke` | `'word value -- result` | inline the referenced word with that value as its input. The reference and the value may arrive in either order. |

</div>

```diffstatic {data-lang=stacksl}
: pattern
  normalize
;

'pattern p apply
```

References can be passed through another definition, which is useful for shader helpers that need a pattern or sampling function.

```diffstatic {data-lang=stacksl}
: pattern
  xylen
;

: mapped
  apply
;

'pattern uv mapped
```

This is still static lowering: StackSL carries the word reference while compiling, then emits direct GLSL for the referenced word rather than a runtime function pointer.

`phacelle` / `directional-noise` uses this for directional phasor noise. It takes `point frequency offset normalization -- noise`, with defaults for everything after `point`. Add a quoted pattern definition before or after the arguments to orient the cells. The pattern receives each cell point as its input and may do normal StackSL work, including sampling.

```diffstatic {data-lang=stacksl}
: flowPattern
  input sample-abs
  normalize
;

'flowPattern p 100 * 1.5 0.2 1 phacelle.4
```

## Local marks

Local marks name a stack value inside the current root program or definition. They are compile-time aliases, not mutable variables, so unused marked values are still pruned.

<div data-code-lang="stacksl" markdown>

| Form | Stack | Meaning |
| --- | --- | --- |
| `=name` | `a -- a` | copy the current top into `name`, leaving the stack unchanged. |
| `>name` | `a --` | move the current top into `name`. |
| `<name` | `-- a` | push a marked value back onto the stack. |
| `name` | `-- a` | bare read of an existing mark, useful after `=name`. |

</div>

A mark cannot be called `x`, `y`, `z` or `w`: `>x` is already the word that replaces one lane of a
value, so `>x <x` reads a mark that was never made.

<div data-code-lang="stacksl" markdown>

</div>

```diffstatic {data-lang=stacksl}
uv 8 * =coord
coord fbm
coord 0.2t + fbm
0.5 mix
palette
```

Use the explicit move/read form when you want to keep the working stack clean — and **always** inside a definition, where `=name` would leave the input on the stack and unbalance the call (see [Definitions](#definitions)):

```diffstatic {data-lang=stacksl}
uv 8 * >coord
<coord fbm
<coord 0.2t + fbm
0.5 mix
palette
```

## Feedback sampling, store, and host values

<div data-code-lang="stacksl" markdown>

| Word | Stack | Meaning |
| --- | --- | --- |
| `0->`, `1->`, `2->`, `3->` | `-- value` | read stored R/G/B/A channel at the current pixel; in the ping-pong harness this aliases previous-frame RGBA. |
| `->0`, `->1`, `->2`, `->3` | `value --` | write `value.x` to that output channel; mentioned writes take priority at return. |
| `->?name` | `value --` | write the top value to an existing host-side GLSL l-value, for example `->?pc.foo` or `->?pc.inData.p[idx].posvel.Z`. |
| `gather`, `gather0`, `gather1`, `gather2`, `gather3` | `offset -- vec4` | raw `textureGather` red channel around `uv + offset.xy`. |
| `gather.w`, `gather0.w`, `gather1.w`, `gather2.w`, `gather3.w` | `offset -- vec4` | wrapped `textureGather` around `fract(uv + offset.xy)`. |
| `texelSize`, `texel-size` | `-- vec4` | inverse size of the first configured sampler as `vec4(1/width, 1/height, 0, 1)`; numbered forms `texelSize1` etc. select later samplers. |
| `textureSize`, `texture-size` | `-- vec4` | size of the first configured sampler as `vec4(width, height, 0, 1)`; numbered forms `textureSize1` etc. select later samplers. |
| `blur`, `blur5` | `radius -- color` / `center radius -- color` | five-tap weighted blur of the first configured sampler. |
| `blur9` | `radius -- color` / `center radius -- color` | nine-tap weighted blur. |
| `crossSample`, `cross-sample` | `center radius -- color` | average four full-color samples at `center.xy +/- radius.x/y` cross offsets. |
| `curl` | `radius -- s` / `center radius -- s` | central-difference 2D curl of texture xy channels. |
| `divergence` | `radius -- s` / `center radius -- s` | central-difference divergence of texture xy channels. |
| `gradient` | `radius -- vec4` / `center radius -- vec4` | luminance gradient as `vec4(gx, gy, magnitude, 1)`. |
| `gradient.r`/`.g`/`.b`/`.a` | `radius -- vec4` / `center radius -- vec4` | selected-channel central-difference gradient as `vec4(gx, gy, magnitude, 1)`. |
| `laplace`, `laplacian` | `radius -- color` / `center radius -- color` | four-neighbor Laplacian. |
| `rCrossSample`, `r-cross-sample` | `center radius -- color` | average four full-color samples on a deterministically rotated cross around `center.xy`. |
| `sobel` | `radius -- s` / `center radius -- s` | Sobel luminance edge magnitude. |
| `crossSample.r`/`.g`/`.b`/`.a` | `center radius -- vec4` | return four selected-channel taps in `xyzw`: `+x`, `-x`, `+y`, `-y`. |
| `rCrossSample.r`/`.g`/`.b`/`.a` | `center radius -- vec4` | return four selected-channel taps from the randomly rotated cross. |
| `prev`, `state`<br>`uv prev 0.1 ema` | `-- color` | Samples the previous frame at the current UV coordinate. Feedback programs combine this with a new value before returning the next frame. |
| `sample`, `sample0`, `sample1`, `sample2`, `sample3` | `offset -- color` | sample a configured texture at raw `uv + offset.xy`. |
| `sample.w`, `sample0.w`, `sample1.w`, `sample2.w`, `sample3.w` | `offset -- color` | sample at wrapped `fract(uv + offset.xy)`. |
| `sample-abs`, `sample0-abs`, `sample1-abs`, `sample2-abs`, `sample3-abs` | `coord -- color` | sample at raw absolute `coord.xy`. |
| `sample-abs.w`, `sample0-abs.w`, `sample1-abs.w`, `sample2-abs.w`, `sample3-abs.w` | `coord -- color` | sample at wrapped absolute `fract(coord.xy)`. |

</div>

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

Channel gradient with sampler texel size:

```diffstatic {data-lang=stacksl}
uv texelSize gradient.r
```

</div>
