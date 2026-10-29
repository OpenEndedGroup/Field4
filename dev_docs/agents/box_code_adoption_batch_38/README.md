# Functions gallery: visible derived values and readable drawing helpers

Baseline `ae3d87d5b`. Three frozen findings addressed:

- `17291ebbeb49-01`: remove the unused rosette xFn expression that maps its result to zero.
- `17291ebbeb49-02`: display the actual 1,400-point converted FLine; draw the 32 requested samples and actual sampled local maxima as markers over their source curves. The displayed counts come from the returned data. Negative screen-Y scaling makes mathematical maxima appear higher, explained in each independent snippet.
- `17291ebbeb49-04`: thirteen rectangular frames use rect, the 220-segment circular guide uses circle, and the two sampled parametric paths use breakNext to start their first point. Styles, sample order, editable values and existing fragment/fold boundaries are retained. Rect/circle explicitly close the contours.

A fresh-box baseline rehearsal also exposed four ambiguous lines([…]) calls. They now use the existing vararg overload. For the original rosette coordinate comparison, the test adapts only that already-broken presentation call; it does not claim the unmodified original snippet compiled. Both actual parametric/rosette coordinate hashes match before and after. New-result probes verify all 32 samples equal actual evaluation, the converted line has 1,400 nodes, and every returned maximum has a marker and its measured value.

Actual import/ktfmt handling covers all 247 Kotlin states on the page. No extra literal conversions occur; the page collection/idiom audit is clean. Preservation checks cover reviewed tokens, surrounding prose, folds, raw strings and fragment boundaries. No GLSL is edited. Runtime coverage is the changed figures and explicit probes, not all 247 independent states.

Published captures are refreshed through the actual docs hook, using its normal verification environment, Field runner, output validation, atomic capture transaction and metadata/cache handling. The harness selects only figures whose runnable source changed. Fourteen stills and the convergence video are genuinely regenerated. After visual inspection exposed inverted graph presentation, only the three affected graph captures rerun; twelve unchanged figures hit their exact source-aware cache. Zero failures or environment omissions remain.

All final figures are inspected, including the genuine convergence video from iteration 0/error 0.4440 to iteration 320/error 0.0044. Private contact sheets and extracted review frames are inspection aids only, excluded from the evidence archive and all published assets. Actual stills and video are committed at their existing documentation paths; no picture is fabricated or substituted for code execution.

Reusable formatting, token/container preservation, real runtime comparison and selective docs-capture harnesses plus evidence.tar.gz retain logs and exact coverage. The full corpus sweep remains incomplete.
