# Rewrite changelog-voice comments in alife_examples fences

- **Handoff:** `306e7187-38fe-486b-b472-9c78f8fb581e` (project)
- **Filed from:** https://openendedgroup.com/field4/alife_examples/
- **Filed by:** human, 2026-10-11 12:54
- **Close with:** `python tools/docs_notes.py handoff-close 306e7187 "what happened"`

Several fence comments and printed strings on `public/docs/alife_examples.md` are written as a
changelog or as notes about how the page was made. The language pass cannot edit fences (they are
byte-pinned by `tools/completion-corpus/rank-carets.tsv` and change captures), so rewrite them and
recapture:

- section 11, "One core, four genomes" fence: "Field had two population searches before tonight",
  "FunctionEvolve.evolve no longer contains a search", "captured BEFORE the refactor".
- section 11, CA fence: "Field had exactly one other two-parent crossover before this",
  "the first version of this demo measured evolution winning by 3%".
- section 7 Physarum video fence: "The first cut of this clip guessed 30 and clipped the network".
- section 5 Lenia video fence: "which cost this family a figure once".
- section 1 video fence: "see the note above this clip, which measures why".

State each as a present-tense fact about the code, or cut it.

Also: section 12 tells readers the scripted-agent documents live at
`dev_docs/agents/demos/alife_script/` and `dev_docs/agents/demos/alife_walls/` (workspace paths).
These should be linked as starting points if they are registered as such.
