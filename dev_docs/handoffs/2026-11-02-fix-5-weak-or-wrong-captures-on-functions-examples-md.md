# Fix 5 weak or wrong captures on functions_examples.md

- **Handoff:** `b97f3c30-9685-4ced-bccc-ca8b09226d0c` (project)
- **Filed from:** https://openendedgroup.com/field4/functions_examples/
- **Filed by:** human, 2026-11-02 14:19
- **Close with:** `python tools/docs_notes.py handoff-close b97f3c30 "what happened"`

Found in a 2026-11-02 audit of every image on the published docs (eight reviewers, each image looked at against its alt text, caption, code and prose). Each item below was seen in the committed asset; fix the code, the capture hook or the framing — never hand-edit a capture (AGENTS.md §3).

## `assets/functions_examples/reference/mark-marker.png` — broken-or-error, high

**Problem.** The 'timeline' plot is empty. The header reads 0/1024 samples and the axes run 0.7 to 1.7 with no curve. The code places sin(2.0) after moveToMark("intro", 0.2), but nothing gets plotted.

**Fix.** Find out why the placed chunk samples nothing over its own domain (a timeline sampling bug or a plot-domain bug), fix it, and recapture so the sine shows up starting at 0.7.

## `assets/functions_examples/reference/timelinegraph-asfn-tap.png` — broken-or-error, high

**Problem.** The 'echoTap' plot is empty: 0/1024 samples, domain 0.12 to 1.12, no curve. The code taps a timelineGraph of saw(0.2) with offset 0.12, so a delayed saw should appear.

**Fix.** Fix the tap/plot sampling so the delayed saw renders, then recapture.

## `assets/functions_examples/reference/moveto-movetomark-advance-rewind.png` — not-what-it-says, high

**Problem.** The code places pulseTrain(0.12) at mark 'drop' + 0.15, but the plot shows a flat line at 1.0 from 0.15 to about 1.15, with no pulses.

**Fix.** Find out why the placed pulseTrain flattens to a constant (it may be evaluated in normalized chunk time, so it sits inside one high phase). Fix that, or pick parameters where the pulses show, then recapture.

## `assets/functions_examples/reference/fn-rewrite-language-plot.png` — weak-illustration, medium

**Problem.** The before, after and delta panels are all dense aliased noise past x≈0.5. The rewrite's visible effect (saw→triangle, depth-scaled amplitudes) only shows in the first ~0.4 units. At column width the three panels look identical.

**Fix.** Use a much lower base frequency or a shorter domain (e.g. 0..0.5) so the saw→triangle change and the amplitude scaling can be read.

## `assets/functions_examples/fit_convergence.mp4` — weak-illustration, medium

**Problem.** The video opens on the converged iteration-320 frame. The actual descent (iterations 0 to 24) passes in about 0.3 s, and the remaining ~2.5 s are visually identical overlapping curves. The alt text promises a curve sliding into place over successive iterations.

**Fix.** Sample iterations on a log scale or concentrate frames in 0 to 40, hold each early frame longer, and start on iteration 0 rather than the final state.

Recapture with `FIELD_DOCS_RUNNER_MODE=always ... build_zensical_docs.py --page functions_examples.md` and look at each new asset at column width before closing.
