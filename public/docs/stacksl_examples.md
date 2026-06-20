# StackSL examples

StackSL is a small stack language that lowers to GLSL. It is meant for shader functions that are quick to write, easy for an LLM to mutate, and robust enough for artificial-life or genetic-programming loops.

The first implementation is vector-first: every stack value is a `vec4`. Numbers become `vec4(n)`, swizzles repack into `vec4`, and most operators work component-wise. The compiler emits one callable function:

```diffstatic {data-lang=glsl}
vec4 prefix(vec4 prefix_in) { ... }
```

Named StackSL definitions become GLSL functions whose names start with the same prefix:

```diffstatic {data-lang=glsl}
vec4 prefix_warp(vec4 prefix_in) { ... }
vec4 prefix_paletteMist(vec4 prefix_in) { ... }
```

This keeps generated names out of the global shader namespace. The Field property for source text is `stackSL`; sidecar files use `.stacksl`.

## The input convention

The input vector is usually read as:

| Component | Meaning |
| --- | --- |
| `input.x` | x / u coordinate |
| `input.y` | y / v coordinate |
| `input.z` | z coordinate, hash, seed, or auxiliary state |
| `input.w` | time, frame, or phase |

The stack starts with `input` already on it. You can also push explicit views:

```diffstatic {data-lang=stacksl}
uv      # vec4(input.xy, 0, 1)
p       # vec4(input.xy - 0.5, 0, 1), also: pos, center, centered
time    # vec4(input.w)
hash    # vec4(input.z)
input   # vec4 input exactly
```

Short programs are intentionally valid. If a normal operator needs more values than the stack has, it wraps around and reuses the available values. If the stack is empty, it uses a default value such as `input`, `vec4(0)`, or a neutral color. Creative words such as `palette`, `ema`, `grade`, and `pulse` have short forms that consume one primary value and use defaults for the rest.

## Tiny programs

Identity is just the initial stack value:

```diffstatic {data-lang=stacksl}
# returns input
```

Tint the input toward blue:

```diffstatic {data-lang=stacksl}
blue mix
```

Build a normalized coordinate color:

```diffstatic {data-lang=stacksl}
uv saturate
```

Turn time into a grayscale pulse:

```diffstatic {data-lang=stacksl}
time sin 0.5 * 0.5 +
```

## Swizzles and constructors

Swizzles always return a `vec4`.

```diffstatic {data-lang=stacksl}
uv xy       # vec4(v.x, v.y, 0, 1)
input z    # vec4(input.z)
input rgb  # vec4(input.rgb, 1)
```

Constructors consume scalar `.x` components from the stack:

```diffstatic {data-lang=stacksl}
0.2 0.8 vec2
0.2 0.8 time vec3
red hash vec4
```

## FBM into a cosine palette

This is the basic creative-coding move: distort coordinates, make a scalar field, then color it.

```diffstatic {data-lang=stacksl}
uv 5.0 *
time 0.12 *
+
fbm
palette
saturate
```

`palette` can be used with one value. It defaults to a balanced cosine palette. To provide a full palette, put `t a b c d` on the stack before the word:

```diffstatic {data-lang=stacksl}
uv fbm
0.50 0.50 0.50 1.00 vec4
0.50 0.45 0.35 0.00 vec4
1.00 1.00 1.00 1.00 vec4
0.00 0.10 0.20 0.00 vec4
palette
```

## Reusable StackSL definitions

Definitions are written with `: name ... ;`. Inside StackSL, a definition behaves like a word that consumes one `vec4` and pushes one `vec4`. In GLSL, it becomes `prefix_name(vec4 prefix_in)`.

```diffstatic {data-lang=stacksl}
: warp
  uv 8.0 * time + sin
  0.18 *
  uv +
;

: field
  warp fbm
;

field palette contrast tonemap
```

With prefix `fx`, this emits `fx_warp`, `fx_field`, and the root function `fx`.

The compiler also inlines definition calls at their call site before emitting SSA-style GLSL. The named GLSL functions are still emitted, but inlining lets repeated StackSL calls share identical samples and math when their inputs and source constants are the same.

```diffstatic {data-lang=stacksl}
: ring
  0-> 0.982 *
  p length 0.42 0.035 pulse 0.08 *
  +
;

drop
input ring
input ring
+ 0.5 *
dup ->0
palette
saturate
```

## Ping-pong RTT sampling

Sample words need a texture environment. If the compiler is called with `sampleTextures = listOf("state")`, `sample` reads from `uniform sampler2D state`. Function and helper names are prefixed; sampler names are not, so several StackSL expansions can share the same texture.

```diffstatic {data-lang=stacksl}
uv 0.003 * sample
uv 4.0 * time + fbm palette
0.08 ema
saturate
```

In the short form, `ema` consumes the current value and defaults the previous value to `input` and alpha to `0.08`. For a ping-pong update, the explicit form is usually clearer:

```diffstatic {data-lang=stacksl}
uv 4.0 * time + fbm palette   # current
prev                          # previous at input.xy
0.06                          # alpha
ema
saturate
```

Relative sampling uses the top stack value as an offset:

```diffstatic {data-lang=stacksl}
uv time + sin 0.006 * sample
```

Absolute sampling ignores `input.xy`:

```diffstatic {data-lang=stacksl}
uv 0.5 + sample-abs
```

`gather` returns `textureGather(..., 0)` for small neighborhood probes:

```diffstatic {data-lang=stacksl}
uv 0.002 * gather
luma
```

## Per-pixel store channels

`0->`, `1->`, `2->`, and `3->` read the x/y/z/w channel of the per-point `vec4` store and push it as `vec4(channel)`. In the ping-pong harness, that store aliases the previous output at the current pixel, so `0->` is a compact red-memory read.

`->0`, `->1`, `->2`, and `->3` pop the top value and write its `.x` component into the corresponding output channel. Explicit channel writes win at the end of the generated function; unwritten channels still come from the ordinary top-of-stack `vec4`.

```diffstatic {data-lang=stacksl}
0-> 0.985 *
p length 0.5 0.08 |sh10 pulse
0.08 *
+
dup ->0
dup 0.65 * ->1
dup 0.35 * ->2
saturate
```

## Difference of EMA

`ema-diff` is useful for extracting motion or change energy:

```diffstatic {data-lang=stacksl}
uv 7.0 * time + fbm palette   # current
prev                          # previous state
0.04
ema-diff
abs
saturation
```

For a softer trail:

```diffstatic {data-lang=stacksl}
sample
uv 3.0 * fbm palette
0.025 ema
tonemap
```

## Pulses, rings, and FM

`pulse` can be used as `x pulse`, defaulting center and width:

```diffstatic {data-lang=stacksl}
p length
pulse
white *
```

`length` and `len` measure the `.xy` channels by default. This keeps coordinate idioms like `p length` from accidentally including the `z/w` filler channels of StackSL's `vec4` values. Use `length3` or `length4` when those components should contribute.

With explicit `x center width`:

```diffstatic {data-lang=stacksl}
p length
time sin 0.25 * 0.5 +
0.035
pulse
palette
```

`fm` is a compact phase modulator. In short form it consumes one phase value:

```diffstatic {data-lang=stacksl}
uv 14.0 * time +
fm
palette
```

Explicit form is `phase carrier modulator fm`:

```diffstatic {data-lang=stacksl}
uv x tau * time +
uv y tau * 3.0 *
2.5
fm
palette
```

## Modifiers, ranges, and memories

Number suffixes keep common channel and time products compact:

```diffstatic {data-lang=stacksl}
0.12t        # time * 0.12
0.7r 0.2g +  # channel-weight vectors
```

Range literals spread a scalar interval across RGBA, which is useful when a framebuffer stores four memories:

```diffstatic {data-lang=stacksl}
uv 4.0 * 0.06t + fbm
prev
0.015_0.18
ema
```

Modifiers come in three forms:

```diffstatic {data-lang=stacksl}
0.4c1.8p0.2   # amount * normalized cos(time * 1.8 + 0.2)
0.5sh4         # amount * sample-and-hold random, 4 buckets per time unit
0.25 |fbm3    # top-of-stack as amount, fbm factor
p length ]sh20 # top-of-stack as scalar driver, amount is 1
```

Wave modifiers are `c`, `s`, `sq`, `saw`, `tri`, and `pulse`; pulse also supports duty with `d`, as in `0.5pulse3d0.2`. Sample-and-hold is `sh`: it hashes `floor(driver * frequency + phase)` and stays stable inside each bucket. Spatial/noisy modifiers are `sh`, `n`, `j`, `x`, `y`, `cx`, `cy`, `hash`, `rad`, `ang`, `wx`, `wy`, `wr`, `wa`, `grain`, `noise`, and `fbm`.

Reducers collapse a `vec4` to a scalar splatted back to `vec4`: `average`, `vmin`, `vmax`, and `median`. Bare `min`/`max` become reducers when there is only one explicit stack value; otherwise they remain binary GLSL min/max.

```diffstatic {data-lang=stacksl}
prev
dup xy 0.012 * sample
swap zw 0.009 * sample
+ 0.5 *
p ]wr9 0.006 * 0.004c0.17 +
p ]wa7 0.006 * 0.004s0.11 +
vec2 sample
0.65 * +
uv 5.0 * prev 0.4 * + 0.02t + fbm
dup 0.23 + dup 0.37 + dup 0.61 + vec4
0.08 * +
prev 0.006_0.09 ema
dup average 0.015pulse0.23d0.09 + +
mirror saturate
```

## Color grading

The one-value forms are intentionally boring and safe:

```diffstatic {data-lang=stacksl}
uv fbm palette
saturation
contrast
grade
tonemap
```

For explicit grading, use `color lift gain gamma grade`:

```diffstatic {data-lang=stacksl}
uv 6.0 * fbm palette
0.02 0.00 -0.01 0.0 vec4
1.15 1.05 0.95 1.0 vec4
0.85 0.95 1.10 1.0 vec4
grade
saturate
```

Saturation uses `color amount saturation`:

```diffstatic {data-lang=stacksl}
uv fbm palette
0.35
saturation
```

## Color spaces

`ryb` treats the top value as red/yellow/blue channels and converts it to display RGB. `rgb2ryb` is the inverse approximation.

```diffstatic {data-lang=stacksl}
uv 0.5 + ryb
saturate
```

OKLab words use native, linear OKLab values. `rgb2oklab` moves a color into the perceptual working space; `oklab` and `oklab2rgb` convert back to RGB.

```diffstatic {data-lang=stacksl}
red rgb2oklab
blue rgb2oklab
0.5 mix
oklab
saturate
```

You can perturb lightness and chroma in OKLab before returning to RGB:

```diffstatic {data-lang=stacksl}
uv 5.0 * fbm palette
rgb2oklab
time sin 0.05 * 0 0 0 vec4
add
oklab2rgb
saturate
```

## Kotlin and template use

The low-level compiler is in `afield.stacksl`:

```diffstatic {data-lang=kotlin}
import afield.stacksl.StackSLCompileOptions
import afield.stacksl.StackSLCompiler

val result = StackSLCompiler.compile(
    source = stackSL ?: "",
    options = StackSLCompileOptions(
        prefix = "ping",
        entrypoint = "main",
        sampleTextures = listOf("state"),
        externalizeConstants = true
    )
)

if (!result.ok) {
    println(result.diagnostics.joinToString("\n"))
}

val glsl = result.glsl
```

When `externalizeConstants` is true, numeric literals become entries in `result.analysis.danglingWires` and the generated GLSL initializes a `float prefix_wires[...]` array. By default those wires contain the source literal values, but deployment code can provide different values to tune or evolve the generated function.

From a box, the stdlib helper is shorter:

```diffstatic {data-lang=kotlin}
val glsl = stackSLToGlsl(
    prefix = "ping",
    sampleTextures = listOf("otherView"),
    declareSamplers = false
)
```

A fragment shader can splice the result into a ping-pong update:

```diffstatic {data-lang=glsl}
// generated StackSL goes above this line

layout(location = 0) out vec4 outColor;
layout(location = 0) in vec2 uv;
layout(set = 0, binding = 0) uniform sampler2D otherView;

void main() {
    vec4 stackInput = vec4(uv, hash12(uv), time);
    outColor = ping(stackInput);
}
```

If the surrounding shader already declares the sampler uniforms, call the compiler with `declareSamplers = false` and keep the generated helper/function code only. With `sampleTextures = listOf("otherView")`, sample words call helpers with `otherView` directly.

## Built-in vocabulary

Stack and input:

```diffstatic {data-lang=stacksl}
dup drop swap over rot nip tuck
input in uv time t hash h
```

Math:

```diffstatic {data-lang=stacksl}
+ - * / mod pow min max neg abs floor ceil fract
sqrt inversesqrt exp log sin cos tan asin acos atan atan2
normalize length len length2 len2 length3 len3 length4 len4
dot cross standoff standoff2 standoff3 mix lerp step smoothstep
lt lte gt gte eq neq and or not
```

Creative:

```diffstatic {data-lang=stacksl}
noise perlin fbm pulse fm palette cos-palette
luma saturate clamp mirror tonemap
ryb ryb2rgb from-ryb rgb2ryb to-ryb
oklab oklab2rgb from-oklab rgb2oklab to-oklab
saturation contrast grade
ema ema-diff diff-ema dema
sample sample0 sample1 sample2 sample3
sample-abs prev state gather gather0 gather1 gather2 gather3
```

Constants:

```diffstatic {data-lang=stacksl}
pi tau e
black white gray red green blue cyan magenta yellow orange
```

## Notes for LLMs and genetic search

Prefer short, valid fragments. `uv fbm palette saturate` is better seed material than a long fragile program.

Use definitions to protect useful motifs:

```diffstatic {data-lang=stacksl}
: motif uv 6.0 * time + fbm ;
motif palette
```

Leave dead experiments in the source if you want. The compiler lowers pure expressions through SSA and emits only values that feed the returned top-of-stack color:

```diffstatic {data-lang=stacksl}
1 2 + drop      # computed, then pruned
uv fbm palette  # returned
```

Use `tolerant = true` for mutation runs. Unknown words become no-ops with warnings; strict mode reports them as errors for authoring.

When a program becomes too short, it still returns a valid `vec4`. That is deliberate: mutation should make strange images, not broken shaders.
