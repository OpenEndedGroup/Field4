# Move dataframe.md's typed() internals to a closing section; decide on the DataFrame import in fences

- **Handoff:** `c743d9bf-ef95-4c98-a23a-0a7482bf3373` (project)
- **Filed from:** https://openendedgroup.com/field4/dataframe/
- **Filed by:** human, 2026-10-03 17:00
- **Close with:** `python tools/docs_notes.py handoff-close c743d9bf "what happened"`

dataframe.md still reads as an internals page for much of its length. The language pass (branch docs/language-20261003-1800) moved the compiler-mechanism prose into asides. Two things are left that a prose pass cannot do:

- The "Calling `typed()` on every run is cheap and safe" table (preamble vs inline eval, changed-line evaluation, replace-by-name) and the `runLaunchSetupBlocks` citations in the `//%-` section are maintainer detail. They belong in a closing "How it works" section, or folded into asides. The page carries a CitationContractTest floor of 8 anchored citations, so they have to move, not be deleted.
- The `:::static` fences up front all repeat `import org.jetbrains.kotlinx.dataframe.api.*`. Decide whether the preamble should supply that import, as box code normally would, and if so drop it from the fences and recapture.
