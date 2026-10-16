# Silence Smile t-SNE INFO logging during smileTsne fits

- **Handoff:** `5b7e51f3-066e-4b68-81cc-e42448f9ed3b` (project)
- **Filed from:** https://openendedgroup.com/field4/projection_examples/
- **Filed by:** human, 2026-10-15 13:44
- **Close with:** `python tools/docs_notes.py handoff-close 5b7e51f3 "what happened"`

`public/docs/projection_examples.md`, "Sharp edges":

> - **t-SNE logs every tenth iteration to slf4j at INFO**, which is ~75 lines per fit. Harmless, and loud if you are watching a log.

Fix in `src/jvmMain/kotlin/afield/math/statistics/smile_mds.kt` (`smileTsne` / `smileTsneDistances`): quiet Smile's `smile.manifold.TSNE` logger for the duration of the fit (or route it through a Field log channel that is muted by default), and delete this bullet in the same change.
