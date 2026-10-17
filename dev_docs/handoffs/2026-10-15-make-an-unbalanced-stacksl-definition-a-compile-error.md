# Make an unbalanced StackSL definition a compile error

- **Handoff:** `7c9daaf8-d385-467f-8f64-97cac573bdda` (project)
- **Filed from:** https://openendedgroup.com/field4/stacksl_cheatsheet/
- **Filed by:** human, 2026-10-15 13:47
- **Close with:** `python tools/docs_notes.py handoff-close 7c9daaf8 "what happened"`

The StackSL cheatsheet (Definitions section) carries this paragraph:

> **A definition must therefore be net zero on the stack: consume its input, leave its result.** It is not an error: an unbalanced definition compiles, the surplus is pruned, and everything downstream silently takes the wrong operand. Open a definition with `>name`, which *moves* the top, and not `=name`, which *copies* it:

This is a sharp edge (rule 25): a definition that is called and is not net zero silently produces wrong operands. Fix in the compiler (`src/jvmMain/kotlin/afield/lang/stacksl/StackSL.kt`): make an unbalanced called definition a compile error (or at minimum a `StackSLSeverity` error-level diagnostic with the source location of the definition and the `=name` that caused it, suggesting `>name`). Delete the "It is not an error..." sentence and the warning framing from `public/docs/stacksl_cheatsheet.md` in the same change, keeping the plain statement of the rule.
