# Differentiable StackSL — a loadable demo

Open this directory as a Field document. Three boxes; press run on each, left to right.

| box | what it shows | cost |
| --- | --- | --- |
| **The wires** | Every numeric literal in a StackSL program, lifted into an addressable free parameter, with the token, line and column the compiler recorded for each one — then the program rendered at 220×220 on the CPU. | ~2 s |
| **Differentiable StackSL** | Gradient descent on those literals against a target image, at 64×64 for 80 Adam steps. Four panels: target, seed, fitted, residual ×8. Prints what each literal became. | ~10 s |
| **The round trip** | The fitted numbers written back into StackSL source, recompiled from scratch through the ordinary non-wired compiler and rendered at 256 — so the output of a fit is a program you can paste into a `stackSL` box and run on a GPU. | ~8 s |

No GPU. No window. No graphics context.

## Why a StackSL program is differentiable

StackSL is a **per-pixel field**: `main()` runs once per pixel with `fragUV` as its only spatial
input, so `d(pixel)/d(wire)` is the ordinary chain rule. No rasteriser, no coverage term, no
approximation, no bias to characterise.

That is what separates it from a shape language. RectSL draws shapes, so a pixel depends on a
parameter through *coverage*, `d(pixel)/d(parameter)` needs a differentiable rasteriser, and the
coverage approximation is a modelling choice with a bias to characterise. None of that applies to a
field evaluated per pixel.

Two properties of the implementation:

- **The derivative flows through the emitted GLSL**, interpreted by the same parser and the same
  interpreter control flow the `Float` reference path walks, with the scalar swapped for a
  reverse-mode AD node. There is no second evaluator to drift from the first.
- **The AD pass's primal is bit-identical to the `Float` path.** Every node rounds to `Float` where
  the `Float` path rounds, so the differentiable pass computes the *same image* rather than a
  `Double` approximation of it — which matters because `fract` of a large product turns a last-bit
  difference into an O(1) one.

## What a fit can reach

The four alpha wires do not move. That is correct: the `w` component of a `palette` operand never
reaches RGB, so its gradient is exactly zero.

A `threshold` is a wall. It lowers to a hard `step`, so everything upstream of it is derivative-free
and a fit cannot reach past it. Of the 325 documentable StackSL words, 295 accept a wire upstream of
them at all; of those, 154 pass a derivative, 74 block it and 67 refuse outright. Most of the
blocked ones do not *lack* a derivative, they discard the value (`uv`, `p`, `time`, `pi`, `drop`,
`swap`, the twelve named colours); the genuinely non-differentiable ones are the shapers and the
predicates.

A noise frequency **is** fittable. `fbm` hashes a `floor`ed lattice **cell index**, and `floor`
carries exactly zero derivative, so no gradient flows through the hash at all — only through
`fract`'s interpolation weight, which is smooth. `uv 3 * fbm`'s frequency wire agrees with a central
difference to 1.5e-5.

The loss is a per-pixel sum of squares and it rewards exactly that. It has no term for structure,
so a fit can drive the number a long way down while producing an image that does not resemble the
target. The residual panel shows where the error sits, which the loss figure cannot.

## Editing it

Change any number in either program and run again. Change `palette` to `ryb-paint`, or add
`saturate` — and then notice the second box's figures get slightly worse, because `saturate` is a
clamp and a clamped pixel contributes nothing to the gradient.

If you edit the loose `*_code_.kt` files rather than the boxes, note that Field prefers the loose
file over the copy inlined in `metadata.edn` — `StackSLFitDemoBundleTest` compares the two byte for
byte and goes red when they part company.

`dev_docs/STACKSL_DIFFERENTIABLE.md` has the measurements, the populations and the floors.
