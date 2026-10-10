# Gaussian-process instruments — a document you can open

Open it with `./field.sh dev_docs/agents/demos -d gp_instruments` (or **File ▸ Open** on
`metadata.edn`). Run the boxes left to right.

| box | what it does | what to notice |
| --- | --- | --- |
| **1 — Tune a curve to a target** | a `GpOptimizer` over three dials (`wobble`, `phase`, `squash`) minimising the misfit to a hidden target curve, thirty tries | left: the target (grey) under the five space-filling first tries (blue); right: the winner (red) on the target. The `convergence` chart is log10 misfit after each try. Observed 2026-10-10: misfit 4.3e-4 at wobble 0.323, phase 1.013, squash 0.814 (target 0.32, 1.1, 0.8) |
| **2 — A or B? (you choose)** | a `GpPreference` over a rosette's petals, depth, curl and hue, with two buttons in `controls` | click **A is better** or **B is better**; the pair redraws and `status` shows the favourite so far. Re-running restarts the questioning |
| **3 — A or B? (a simulated judge)** | the same loop, 24 answers from a judge whose ideal is a deep, barely curled, blue five-petalled rosette | the favourite after 6, 12, 18 and 24 answers, then the ideal in grey. Observed: utility −0.392 → −0.111 → −0.031 → −0.010 (a random rosette averages −0.654) |

The source is `dev_docs/agents/transcripts/gp_instruments.yaml`; the loose `*_code_.kt` files are
what Field loads and `metadata.edn` carries inline copies (`./gradlew checkAgentDemos`). The user
page is `public/docs/gaussian_process_optimization.md`; design notes `dev_docs/GP_INSTRUMENTS.md`.
