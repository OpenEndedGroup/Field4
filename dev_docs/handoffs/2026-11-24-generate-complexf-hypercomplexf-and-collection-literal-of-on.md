# Generate Complexf/Hypercomplexf and collection-literal of() on read-only vectors

- **Handoff:** `19bdb66f-8878-4c02-8b05-41965fa614f4` (project)
- **Filed from:** https://openendedgroup.com/field4/linear_algebra/
- **Filed by:** agent:claude, 2026-11-24 19:08
- **Close with:** `python tools/docs_notes.py handoff-close 19bdb66f "what happened"`

## The notes that led here

- `7be6dcb8` (public/docs/linear_algebra.md:3): somewhere on this page (but not necessarily in the first section!) we should link to StackSL's complex number weight type system.

I'm also wondering if we should add Complexf (and maybe Hypercomplexf) to our linalg generator system. I mean we have Quaternionf, why not complex?
  > Linear algebra is the small vocabulary underneath movement, cameras, lighting, meshes, simulation, and spatial data. In Field it is meant to read like GLSL and run like live code: reorder a vector's components by name, do arithmetic with the usual + - * /, copy a vector with one field changed, rescale it to a length, all as expressions and without ceremony.

## What was asked

On `public/docs/linear_algebra.md` (note 7be6dcb8) Marc asked whether the linalg generator should
emit `Complexf` (and maybe `Hypercomplexf`) next to `Quaternionf`. In the thread he adds that this
would change the page's "Spinny algebras" section a lot and probably make it easier to follow and
less random-looking. Note c7f340e4 asked whether `a.xy = [1, 2]` works. It does not (conjecture,
2026-11-24: reasoned from source, not compiled). The setter `var Vector2f.xy: Vector2fc` in
`src/generatedLinalg/kotlin/afield/math/linalg/Swizzles.kt` takes the interface `Vector2fc`, which
has no companion `of`. Only `Vector2f`'s companion has one (`Vectors.kt`, `Vector2f.Companion.of`).

## Why it is bigger than a docs edit

Both changes go into the generator behind `src/generatedLinalg/`. They add new public API and
regenerate the swizzle and vector files.

## Proposal

1. Generate `Complexf`/`Complexd` with `+ - * /`, `conj`, `abs`, `arg`, `exp`, `polar`, and
   conversion to and from `Vec2`. Then the spinny section's hand-rolled pairs could use a real type.
   `Hypercomplexf` (a parameterised ε²) could replace the section's ad-hoc `ε²` algebra.
2. Add `operator fun of(vararg Number)` to the companions of the read-only `Vector{2,3,4}{f,d}c`
   interfaces, returning the mutable type. Then `v.xy = [1, 2]` compiles.

Alternative to 1: keep the hand-rolled code on the page and only link StackSL's rotation weights
(this pass already added that link).

## Questions for the human

- Should Complex be float only, or float and double? And `Hypercomplex` with a runtime ε², or
  separate Dual and SplitComplex types?
- Should complex numbers interoperate with StackSL's `@types` complex weights?

## What the page needs afterwards

Rewrite "Spinny algebras" around the new type. Once (2) lands, drop the sentence under "Write
swizzles" that says `[1, 2]` does not compile.
