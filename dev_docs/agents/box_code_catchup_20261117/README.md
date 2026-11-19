# Teaching-code catch-up, 17 November 2026

Scope is frozen in `scope.json`: changes since the prompt-ready house style landed
(`de264457d`), ending at `2a57863d67`. Of 42 changed authored paths, 22 contain 114 new or
changed supported code bodies: 103 Kotlin, five GLSL and six PostScript. Metadata/preamble and
historical-file exclusions follow the existing source-aware inventory. The scanner predates public
PostScript fences, so five more bodies on `postscript.md` were reviewed separately in
`additional-language-review.json`: 119 bodies across 23 files in total.

Reviewed for editable lesson content, typed inherited properties, resource ownership, collection
literals, comments/units, ordinary output APIs, and direct reference links for retained helpers.
`review.json` records every frozen body and its domain decision. Most new examples already expose
their actual transforms; native algorithms and reusable resource APIs remain library calls.

Changes include the missing CUDA seed kernel/function handle in the public walkthrough, collection
literals, private FM1 simulator cleanup, shader buffer comments, actual import/ktfmt/GLSL formatting,
and curated class/method reference links. The explicit CUDA seed resets the reused buffer on rerun;
the text now distinguishes buffer reuse from that intentional reset.

`run.py` reuses the prior catch-up's source-span inventory and real formatter. Run `inventory`
once; `format` visits only frozen changed Kotlin/GLSL spans and preserves string contents,
fragment markers and folds. `revise.py` records the reviewed manual edits; `link_details.py` selects
real generated reference anchors. `verify.py` compares before/after FM1 sample/trace/screen hashes
at defaults and edited inputs, and compiles all five GLSL properties through shaderc.
`recapture.py` uses the normal docs runner for three algebra figures and the FM1 MIDI roll.

Generated preambles are unchanged. CUDA execution and Linux/NVIDIA figures remain under their
hardware profile; the repaired compiler-input string is identical to the canonical runnable source.
No new helper API is needed for this delta. See `checkpoint.json` for verification and delivery.
