# Make RectSL fitParameters defaults converge or say they did not

- **Handoff:** `fbe294ee-2a51-47e9-a546-f4bd99029521` (project)
- **Filed from:** https://openendedgroup.com/field4/rectsl_inverse_animation/
- **Filed by:** human, 2026-10-15 13:48
- **Close with:** `python tools/docs_notes.py handoff-close fbe294ee "what happened"`

Rule 25 sharp edge on rectsl_inverse_animation.md ("Declaring an unknown"):

> The defaults are `iterations = 300` and `lr = 0.05`, and each step moves a parameter by about `lr`, so a `bias` that has to travel from 0 to -8 cannot get there in 300 steps. The default call stops with a residual near 0.1 ... With 2000 iterations it lands on the exact answer.

and the "Check the residual" note. Fix in afield/lang/rectsl/RectSLFit.kt: an adaptive step / stop-on-convergence default, or a result that flags non-convergence loudly. Delete the paragraph and note in the same change (and update the fence that fits twice).
