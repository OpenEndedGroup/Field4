# StackSL's CPU backend

`afield.stacksl.cpu` runs StackSL programs with no GPU, no window and no graphics context. It does
that by **interpreting the GLSL the StackSL compiler emits** — the same text a GPU is handed, byte
for byte.

## Why this exists

Until it did, a StackSL program could only be *run* on a GPU. So every claim about what a word does
was checked one of two ways: by eye against a screenshot, or against a hand-written Kotlin twin of
the emitted GLSL. On 2026-08-29 the second of those produced a committed figure that illustrated
"GLSL behaviour" without any GLSL having run — the picture came from a transliteration. That is the
failure this closes.

It also makes StackSL testable at all. `StackSL.kt` is 3475 lines of compiler; before the
volumetric-noise branch it had no unit test of any kind, and the tests it gained can only reach the
words that `VolumetricNoiseReference` mirrors by hand.

## Why an interpreter for the output, rather than a second arm on the compiler

The standing advice in these notes was to hang a CPU backend off the parsed program, the way
RectSL's `TrackOp` enum carries both an `eval:` and a `glsl:` for every operator. **StackSL cannot
be done that way.** Its one internal node is

```kotlin
private data class StackValue(id: Int, expression: String, deps: List<Int>, …)   // StackSL.kt:399
```

and `expression` is *already GLSL text*, produced by `String`-returning lambdas (`unary` and
`binary`, `StackSL.kt:2024-2035`). There is no symbolic op anywhere in `afield.stacksl` to give a
second arm to. Adding an `eval:` per word means rewriting the emitter across the whole word set, and
every one of those rewrites is a chance for the CPU arm to disagree with the GPU arm.

Parsing the emitter's output instead buys two things the per-word approach cannot:

- **zero edits to `StackSL.kt`**, so it cannot regress the GPU path;
- **no possible drift**, because both backends read one string. Change what `fbm` compiles to and
  this backend changes with it on the next call, with no edit here.

## The subset, measured rather than guessed

Dumped for all 31 committed gallery programs — 1472 lines of emitted GLSL — the entire surface is:

- `float` / `vec2..4` / `mat2..3` / `uvec4` declarations, with initialisers;
- `=`, `+=`, `-=`, `*=`, `/=`, `^=`, and `>>` / `<<` / `&` / `|` / `^`;
- `if`, `for (int i = …; i < …; i++)`, `return`;
- function definitions, and about 30 builtins;
- `layout(...) in/out/uniform` declarations, `uniform sampler2D`, and a
  `layout(push_constant) uniform Push { vec4 params; } pc;` block.

**No ternary, no `while`, no `switch`, no structs, no arrays.** The parser covers slightly more than
that (ternary, `while`, `do`, `break`/`continue`) so the next shader does not fall off a cliff, and
refuses everything else *by name* — `GlslUnsupported("GLSL arrays are not in the StackSL subset")`
rather than a guess.

## Files

| file | what it is |
| --- | --- |
| `GlslValue.kt` | the value model (`GlslType`, `GlslValue`), GLSL builtin semantics, `GlslSamplers`, `CpuTexture2D` |
| `GlslParser.kt` | lexer, AST, parser for a whole fragment shader |
| `GlslInterpreter.kt` | the evaluator: `GlslInvocation`, one per thread |
| `StackSLCpu.kt` | the public surface: `compile`, `expandStackAuto`, `stillTemplate`, `renderStill`, `renderFragment`, `renderProgram`, `CpuRaster` |
| `StackSLCpuGallery.kt` | the gallery oracle: loading, tiering, statistics |
| `StackSLCpuContactSheet.kt` | the contact-sheet `main`; the only file here that touches AWT |

The evaluator is deliberately AWT-free, so a live Field box can render a program straight into a box
output: `PngEncoder` → `display()`.

```kotlin
import afield.stacksl.cpu.StackSLCpu
display(StackSLCpu.renderStill("uv 6 * fbm palette saturate").toPngBytes())
```

## Accuracy: four mechanisms, four different honest claims

This is the part worth reading carefully, because a single global epsilon would be dishonest in both
directions at once. `AgreementTier` classifies each program from its **own emitted GLSL** — not from
a hand-kept list, so a program that gains an `fbm` is reclassified without anyone remembering to.

### `exact-ops` — agreement is required

`+ - * /`, `floor`, `fract`, `abs`, `min`, `max`, `clamp`, `mix`, `step`, `dot`, matrix products.
IEEE-754 specifies all of these exactly. Two conforming implementations must produce the same bits
unless one contracts a multiply-add into an FMA. A disagreement here is a defect and is asserted as
one.

### `transcendental` — a tolerance, because the spec gives none

`sin`, `cos`, `tan`, `asin`, `acos`, `atan`, `exp`, `log`, `pow`, `sqrt`, `inversesqrt` are
**implementation-defined** in GLSL: the specification gives them no accuracy requirement. So no CPU
implementation can be bit-exact with a GPU *even in principle*. But they are smooth, so the error
stays the size of their argument's last bits and a tolerance of a level or two is the right claim.

### `hash21-chaos` — exact per operation, and still not reliable

`_hash21` is the hash behind `noise` and `fbm`:

```glsl
float _hash21(vec2 p) {
    p = fract(p * vec2(123.34, 456.21));
    p += dot(p, p + 45.32);
    return fract(p.x * p.y);
}
```

Multiply, add, `fract`. **No `sin`.** So it is IEEE-exact operation by operation — and that is not
the same as stable. It is called on `floor(p)`, and `_fbm` walks `p` through five `mat2` warps
before that `floor`. When accumulated rounding puts `p` on the other side of an integer the lattice
cell changes and the value jumps by O(1). The mean stays a hundredth of a level while a handful of
pixels differ by 200.

That is chaos, not error, and it is reported as a distribution rather than bounded by an epsilon.

### `hash11-chaos` — cannot agree, and should not be asked to

`_hash11`, behind `valueNoise`, is `fract(sin(p * 127.1) * 43758.5453123)`: an
implementation-defined `sin` at a large argument, multiplied by 43758, then `fract`ed. Both
mechanisms at once, and the worse one is unavoidable.

**`_hash11` and `_hash21` are not the same animal, and describing them as "the hashes" is how a
false claim shipped in a KDoc and on a published page.** Only `_hash11` has a `sin` in it.

## Measured against the committed GPU gallery

`public/docs/assets/stacksl_gallery/` holds 31 StackSL programs and the fragment template each was
rendered through; `public/docs/assets/stacksl/stacksl-<name>.png` is the 512×512 still a real GPU
produced for it. 29 of the 31 templates are byte-identical (the still template); the other 2 are the
feedback template.

Two consequences worth naming:

- the fragment files are the **template**, with the literal `{{ stack.auto.program }}` still in
  them, not a dump of expanded GLSL. So a CPU render that reads them is forced back through the live
  `StackSLCompiler`. A stale dump would have let both backends agree with each other while both
  disagreed with today's emitter.
- the ground truth is **8-bit** (`VK_FORMAT_R8G8B8A8_UNORM`) and clamped to `[0,1]` by the harness's
  own `main()`. So anything below half a level is invisible, "bit-exact" would be unfalsifiable, and
  every number below is in 8-bit levels. It also means the oracle **cannot move**: there is no
  driver variance to leave headroom for, so the asserted bounds sit one level above the measurement
  rather than an order of magnitude above it.

| tier | n | worst max | worst mean | worst % within one level |
| --- | --- | --- | --- | --- |
| `exact-ops` | 2 | 1 | 0.0000 | 100.0000% |
| `transcendental` | 5 | 1 | 0.0009 | 100.0000% |
| `hash21-chaos` | 21 | 255 | 0.1673 | 96.2620% |
| `hash11-chaos` | 1 | 250 | 96.6836 | 1.7578% |
| `feedback` | 2 | — | — | out of scope |

Headline numbers:

- **`uv-coordinates` (`uv saturate`) is bit-identical: all 262144 pixels.** That is also the only
  unarguable proof of the raster convention — a Y flip, a half-pixel offset or a truncating
  quantiser each makes it impossible.
- **24 of the 29 comparable programs agree at *every* pixel to within one 8-bit level.**
- **28 of 29 agree at more than 96% of pixels.**
- **325 of 325 documentable StackSL words execute on the CPU backend** (`stackSLDocumentableWords`,
  compiled as `input <word>` and evaluated). Note this is a *different, weaker* claim than the
  pixel comparison: it means the word compiled, parsed, every construct in it was implemented, and
  it produced a finite colour. It does not mean the answer was checked against a GPU.

### The five that do not agree everywhere, each with its mechanism

| program | max | % within one | mechanism |
| --- | --- | --- | --- |
| `posterize-shaper` | 255 | 99.9546% | a hard threshold turning a sub-ulp `fbm` difference into a full black-to-orange flip. The difference image shows it as isolated specks strung along the contours and nowhere else. |
| `hard-threshold` | 255 | 99.9874% | the same, with fewer contours. |
| `subroutine-ridges` | 7 | 96.2620% | accumulated `_fbm` rounding, spread as a faint haze over the smooth regions. Invisible to the eye. |
| `word-reference` | 6 | 98.0061% | the same. |
| `sample-hold` | 250 | 1.7578% | `_hash11`. Unrelated colours — see below. |

`sample-hold` is the interesting one, because it is where "the hash is unbounded" has to be
separated from "we have a bug". The sample-and-hold **lattice** is `floor` of an exactly-computed
phase, so it is identical on both backends, and that is checkable: the columns at which the image
changes are the same. Measured, the CPU's band boundaries are a strict *subset* of the GPU's — 58 of
59, with no boundary the GPU lacks. (The missing column is two adjacent bands hashing to the same
byte on one backend, which is a property of nothing.) Same structure, unrelated values. A backend
bug would move the boundaries too.

### Feedback: out of scope, with a reason

`feedback-trails` and `diffusion-feedback` sample the previous frame, so their still is the end of a
60-frame ping-pong and not a function of `fragUV`. Reproducing the GPU's still would mean
reproducing the GPU's sampler and 60 frames of divergence, which is not a claim worth making.

The backend is not *structurally* unable to do feedback — `CpuTexture2D` (bilinear, clamp-to-edge)
ping-ponged in a `for` loop is the whole of `PingPongRenderToTexture`'s idea, and the demo document
does exactly that for 72 frames. What is out of scope is the *comparison*.

**Incidental finding, not fixed here:** `stacksl-feedback-trails.png` and
`stacksl-diffusion-feedback.png` are **entirely black** — 0 non-black pixels out of 262144 each.
`StackSLGalleryHarness.renderGallery` renders every entry through `fragmentTemplate()`, which
declares no `source` sampler, so a feedback program cannot compile there and no still is ever
captured. The two committed files are stale.

## The picture

`dev_docs/assets/stacksl_cpu_backend/gallery_contact_sheet.png` — CPU | GPU | |difference| ×16 for
every program, with the per-program table beside it in `gallery_agreement.md`. Regenerate with:

```
java -Djava.awt.headless=true -cp build/classes/kotlin/jvm/main:<kotlin-stdlib> \
     afield.stacksl.cpu.StackSLCpuContactSheetKt
```

Its difference tiles decimate by **maximum**, not average. That is deliberate: the interesting
failure is a handful of pixels differing by 200 inside an image whose mean difference is a
hundredth of a level, and a 4×4 box average divides exactly that pixel by 16 before the
amplification puts it back — hiding the one thing worth seeing.

## The reference checks the reference

`VolumetricNoiseReference` is a hand transliteration of the emitted GLSL for `noise3`, `fbm3`,
`curl3`, `worley3` and the rest, and the branch that added it named that duplication as its own top
risk. `StackSLCpuVolumetricCrossCheckTest` now closes the loop: it parses the emitted GLSL and
compares every function, bit for bit. The two sides share no code and no author — one is a person
reading GLSL and writing Kotlin, the other a machine reading GLSL and executing it.

It found one thing. `hash31`, `hash33`, `hash41` and `worley3` were already bit-exact (20000, 5000,
5000 and 300 samples). `noise3` matched 2054 of 4000, `noise4` 940 of 2000, `curl3` 5 of 300 — and
the pattern names the cause: everything with a `mix` in it was off, everything without one was
exact. The reference had `mix(a, b, t) = a + (b - a) * t`; GLSL defines it as `x * (1 - a) + y * a`.
Algebraically identical, a different floating-point expression, one ulp apart per call. Fixed, and
now all nine functions are bit-exact.

That residual was 1.79e-7 and it still mattered, for the reason the whole `hash21` story is about:
`fract` amplifies, so a reference one ulp out is not a reference for anything built on top of it.
Which is why the test counts **bit-exact samples** rather than reporting a mean error. Only
bit-exactness composes.

## Tests

| test | claim | runtime |
| --- | --- | --- |
| `GlslCoreTest` | the GLSL semantics that differ from the nearest Kotlin equivalent, or where the plausible convention is the wrong one | 0.1s |
| `StackSLCpuStillTemplateTest` | `StackSLCpu.stillTemplate` is byte-identical to the committed gallery template | <1s |
| `StackSLCpuWordCoverageTest` | all 325 documentable words compile and run; every builtin the emitter calls is implemented | ~10s |
| `StackSLCpuVolumetricCrossCheckTest` | every `VolumetricNoiseReference` function is bit-exact against the emitted GLSL | ~10s |
| `StackSLCpuGalleryTest` | 29 programs against the committed GPU stills, per mechanism | ~240s |
| `StackSLCpuDemoBundleTest` | the demo document parses through Field's real EDN codec, its inline fallbacks match the loose files, and both its programs render | ~5s |

`GlslCoreTest` was red-first checked: mutating `GlslMath.mod` to Kotlin's `%` and `GlslMath.mix` to
the one-product rewrite turns exactly two of its tests red and nothing else.

Two of `StackSLCpuGalleryTest`'s assertions were written from guesses, went red on correct
behaviour, and were replaced by the numbers the run printed. Both say so in place.

## Speed, honestly

A tree-walking interpreter: roughly 10⁵ pixel-shader invocations per second per core, against a
GPU's 10⁹. 512×512 of a five-octave `fbm` is single-digit seconds across ten cores; the whole
31-program gallery is about 200 seconds. Rows are rendered in parallel with one `GlslInvocation`
each, because an invocation holds mutable frames and is not thread-safe.

It is a reference, not a renderer. Two things were done for speed and are worth knowing about:
`GlslType` instances are interned (every component-wise operation would otherwise allocate one
alongside its result), and control flow is a returned `Flow` rather than an exception (`_fbm`
returns from inside a loop, and a 512×512 render evaluates that `return` 1.3 million times per
program).

## The loadable demo

`public/docs/assets/stacksl_cpu_demo/` — open the directory as a Field document; two boxes, press
run on either. The first prints the emitted GLSL beside the image it produced; the second
ping-pongs `diffusion-feedback` for 72 frames on the CPU. See that directory's `README.md`.

## What is not covered

- **The GPU's texture sampler.** `CpuTexture2D` is bilinear with clamp-to-edge, which is what
  `VK_FILTER_LINEAR` means, but not bit-for-bit what any particular driver does. The default
  `textureGather` is an approximation (four half-texel `sample` calls) and says so.
- **Derivatives.** `dFdx`, `dFdy` and `fwidth` are per-quad on hardware and have no meaning for one
  isolated pixel. The emitter does not use them; if it starts to, this refuses by name.
- **Fused multiply-add.** A GPU may contract `a * b + c`; this interpreter is the unfused reading.
  That is the one place where an `exact-ops` program is allowed a single level of slack.
- **`uint` beyond 16 bits.** Integer components are stored as `Float`, exact below 2²⁴. Every
  `uvec4` the emitter produces goes through `toBits16`, which masks to 65535, so the bound holds —
  and an operand outside it is refused rather than silently losing its low bits.
