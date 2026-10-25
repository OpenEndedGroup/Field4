# Refresh generated RectSL material shaders on rerun

Frozen findings: `4e40faf4c597-01`, `e8fc29a1999a-01`.

Four editable RectSL examples now assign the generated fragment source on each run while retaining the constant vertex shader's once allocation and every //%- expansion boundary. Comments explain why generated material source must refresh before moduleHere expands.

All four complete figure bodies compiled and ran on the actual private Vulkan renderer. With clocks synchronized to the same 1.5-second frame, each before/after capture was pixel-identical and the installed fragment matched the current RectSL compiler output. Editing the playground's material expression and adding a new dataviz material both changed actual shader sources and visible native pixels on rerun. Musicality and plot-axes also passed same-source rerun comparisons. Actual shader text and images were inspected; no public captures were changed.

The initial fixture only initialized clock variables, which did not provide a deterministic comparison; retained diagnostic captures differed. The successful fixture explicitly synchronized time, push data and generated geometry before capture and verified unchanged generated shader text. This is a fixture correction, not a claimed renderer defect.

Actual editor import/ktfmt covered all eight Kotlin bodies; parsed containers, raw strings, shader properties and fragment boundaries were checked. Generated documents remain the lander's responsibility.

Checkpoint: 352/405 addressed, 53 open. Mechanical coverage: Full corpus receipts in batch 56 and history repair in batch 57; eight batch 62 Kotlin bodies formatted and verified. Full guards are delegated to the lander.
