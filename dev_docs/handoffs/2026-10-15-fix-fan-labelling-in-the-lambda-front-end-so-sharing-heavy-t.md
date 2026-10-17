# Fix fan labelling in the lambda front end so sharing-heavy terms reduce correctly

- **Handoff:** `21444ef3-e377-4fc4-82e7-8808228532f0` (project)
- **Filed from:** https://openendedgroup.com/field4/chain_lambda_sound/
- **Filed by:** human, 2026-10-15 13:42
- **Close with:** `python tools/docs_notes.py handoff-close 21444ef3 "what happened"`

Rule 25 sharp edge on `public/docs/chain_lambda_sound.md`, aside "Why the page measures the counts instead of citing Lafont":

> The fan labelling in the λ front end is wrong on some terms, so confluence is not guaranteed here. `LambdaSoundDocs.CONFLUENCE_PROBES` runs four terms ... Three of the four answers are wrong, and each of those three reports that it is.

A λ front end that computes wrong answers is a bug. Fix the fan labelling in `src/jvmMain/kotlin/afield/lang/lambda/lambdaterms.kt` (e.g. proper levelled/labelled fans, or an oracle-based abstract algorithm) so the four probes reduce correctly; then the page can cite Lafont, and the aside (plus the matching caveat on interaction_nets_examples.md, "what the sharing gets wrong") is deleted in the same change.
