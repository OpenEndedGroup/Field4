# Drawing with Gaussian processes — a document you can open

Open it with `./field.sh dev_docs/agents/demos -d gp_drawing` (or **File ▸ Open** on
`metadata.edn`). Run each box (select it, then run).

| box | what it does | what to poke |
| --- | --- | --- |
| **1 — A closed loop through six pins** | `gpCurve` with `GpKernels.periodic(period = 1.0, ...)`: the orange mean and six blue draws, every one closed with no seam | drag any **pin** control in the `pins` panel and the loop redraws live; the **length scale** slider makes the loop stiffer or wobblier. Running the box puts the pins back at `start`, so edit `start` to keep a shape |
| **2 — Four measurements, two sliders** | `gp1D` conditioned on four values: five draws, the mean and the 95% pointwise interval | **length scale** (stiff vs. wiggly functions) and **noise of the third measurement** (the draws pull away from that dot as it rises) |
| **3 — A field over the plane** | `gp2D` pinned at four points: mean, two whole-field draws on one colour scale, and the standard deviation | change the four values, the kernel's two length scales or the seeds, and run again |

The source is `dev_docs/agents/transcripts/gp_drawing.yaml`; the loose `*_code_.kt` files are what
Field loads and `metadata.edn` carries inline copies. The user page is
`public/docs/gaussian_processes.md`.
