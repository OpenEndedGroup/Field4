# Add the missing captures to faust.md (6 sections)

- **Handoff:** `9bae42b1-20b7-48f4-b289-84b609d268ea` (project)
- **Filed from:** https://openendedgroup.com/field4/faust/
- **Filed by:** human, 2026-11-01 13:52
- **Close with:** `python tools/docs_notes.py handoff-close 9bae42b1 "what happened"`

From the 2026-11-01 punchline audit: each section of the site was checked for whether it makes a point or shows something that works, but the reader never sees the result. A skeptic pass re-checked every finding against the page. Each capture must come from the real code (AGENTS.md §3, "A capture exercises the real code"); if the real one can't be made, omit it and say why.

## Sections missing a punchline

### A box that is a signal processor (`public/docs/faust.md:11`, high, docs-runner)

- **Point made:** A Faust region turns into a running, sounding box with three sliders that the `hslider` declarations generate, and moving one changes the sound live.
- **Capture:** A runner capture="box" with open-outputs of the real faust(...) box, showing the generated freq/cutoff/gain control group, plus an audio= offline render of the sawtooth through the low-pass, the way the poly-chord section below already does it.

### The block diagram (`public/docs/faust.md:296`, high, docs-runner)

- **Point made:** diagram = true draws the program's signal-flow block diagram in the box's output.
- **Capture:** Convert the fence to diffstatic_runner capture="box" open-outputs=true wait-output=true so the real SVG block diagram Faust produces is shown.

### Errors land on the line (`public/docs/faust.md:42`, medium, docs-runner)

- **Point made:** A Faust compiler error appears as a marker on the box's own `process` line, after the line numbers are shifted back past the stripped `#faust` marker.
- **Capture:** A diffstatic_runner capture="editor" outputs="inline" expect-error=true of the misspelled sawtoothX box, showing the 'undefined symbol : sawtoothX' marker on the process line (the same recipe as clojure.md's error captures).

### Completion knows the standard library (`public/docs/faust.md:64`, medium, docs-runner)

- **Point made:** Typing `os.` offers Faust oscillators with their signatures, and hovering shows the docs.
- **Capture:** A capture="ui" clip-selector=".afield-suggest" screenshot of the completion list after typing `os.` inside a #faust region, using the same recipe as the clojure.md completion capture.

### The controls are the program's own (`public/docs/faust.md:84`, medium, docs-runner)

- **Point made:** Faust UI declarations (sliders, checkbox, button, log scale, groups, units) become an ordinary Field control group.
- **Capture:** A box screenshot of the gate/freq voice, or of a program that declares a slider, a checkbox, a button and an hgroup, with its control area open so the qualified labels and units show. Audio of the gate press is optional.

### Effects, not just synths (`public/docs/faust.md:316`, medium, docs-runner)

- **Point made:** A Faust program with inputs is an effect: detuned sawtooths go through zita reverb.
- **Capture:** An audio= offline render (audio-renderer like the poly-chord fences) of the dry pair through the reverb; a dry-versus-wet pair would be best.


## Done when

Every section above shows its result through a runner capture (or reused committed media that exercises the real code), or this handoff records why that section stays without one.
