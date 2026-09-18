# StackSL on the CPU — a loadable demo

Open this directory as a Field document. Two boxes; press run on either.

| box | what it shows |
| --- | --- |
| **StackSL on the CPU — the emitter's own GLSL, interpreted** | Compiles a StackSL program with the ordinary `stack.auto` compiler, prints the GLSL it emitted, then renders that GLSL at 256×256 on the CPU and shows the picture. About two seconds. |
| **StackSL feedback, ping-ponged on the CPU** | The `diffusion-feedback` gallery program, verbatim, run for 72 frames with two `CpuTexture2D` buffers swapped each frame — the `PingPongRenderToTexture` idea in a `for` loop. Six frames stitched into a strip. About half a minute. |

No GPU. No window. No graphics context. No AWT. `PngEncoder` → `display()`.

## Why the backend cannot drift from the emitter

It interprets the emitted GLSL, and nothing else. The first box prints that GLSL beside the image
it produced, and the text is not a description of what the CPU did: it **is** what the CPU did, byte
for byte what a GPU is handed. The backend has no opinion about any StackSL word — it only knows
GLSL. Before it existed, a claim about what a word does could only be checked by eye against a
screenshot, or against a hand-written Kotlin twin of the same GLSL.

## Agreement with a GPU

Bounded by GLSL's own promises, which differ per program. Measured against the 31 committed gallery
stills: 24 of 29 comparable programs agree at **every** pixel to within one 8-bit level, `uv
saturate` is bit-identical across all 262144 pixels, and each outlier has a named mechanism.
`dev_docs/STACKSL_CPU_BACKEND.md` has the table and the reasons;
`dev_docs/assets/stacksl_cpu_backend/gallery_contact_sheet.png` is the picture.

## Speed

A tree-walking interpreter: **1.3–1.6 × 10⁴** pixel-shader invocations per second per core against a
GPU's 10⁹, and **4.4 × 10⁴** across ten cores, which is 3.3× the single-core figure rather than 10×.
`StackSLCpuThroughputMeasurements` is where those come from. It is a reference, not a renderer.

## Editing it

Change a number or a word in either box and run again. The first box's program is
`p 6 kaleido / 1.7 twist / 3.0 * / fbm / palette / saturate`; try `p 8 kaleido`, or `fbm` →
`noise`, or `palette` → `ryb-paint`. Every one of the 325 documentable StackSL words runs on this
backend, so anything the language accepts will render.

If you edit the loose `*_code_.kt` files rather than the boxes, note that Field prefers the loose
file over the copy inlined in `metadata.edn` — `StackSLCpuDemoBundleTest` compares the two byte
for byte and goes red when they part company.
