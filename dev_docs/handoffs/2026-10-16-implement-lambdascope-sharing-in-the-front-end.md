# Implement Lambdascope sharing in the λ front end

- **Handoff:** `b5b839c2-b939-422a-b91c-368e8c5b12e7` (project)
- **Filed from:** https://openendedgroup.com/field4/chain_lambda_sound/; also affects interaction_nets_examples.md
- **Filed by:** human, 2026-10-16 09:08
- **Close with:** `python tools/docs_notes.py handoff-close b5b839c2 "what happened"`

Decided 2026-10-16 (Marc, handoff 6ca49e9b; see `dev_docs/handoffs/2026-10-16-make-the-front-end-s-sharing-correct-choose-lambdascope-lamp.md`): make the λ front end's sharing correct with **Lambdascope** (van Oostrom, van de Looij, Zwitserlood, "Lambdascope: another optimal implementation of the lambda-calculus").

Today `LambdaTerms.compile` (`src/jvmMain/kotlin/afield/lang/lambda/lambdaterms.kt`) gives each `DUP` fan a fresh `Lambda.FAN_LABEL`. Copies of one fan carry the same label and later annihilate wrongly, which three of the four `LambdaSoundDocs.CONFLUENCE_PROBES` hit.

Work:

1. Replace the labelled-fan scheme with Lambdascope's agents (scope delimiters, one fan family) and its rules in `Lambda.rules()`, `compile` and `readback`.
2. Extend `LambdaSoundDocs.EVENT_KINDS` with the new interactions so the sonification covers them.
3. Check all four confluence probes and the corpus against the normal-order evaluator, pinned in a test.
4. Recapture the four sonified pages (audio and rule-tally readouts). Delete the aside on chain_lambda_sound.md ("Why the page measures the counts instead of citing Lafont") and the caveat on interaction_nets_examples.md ("what the sharing gets wrong").
