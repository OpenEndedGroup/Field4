# Make the λ front end's sharing correct (choose Lambdascope, Lamping levels or closed reduction)

- **Handoff:** `6ca49e9b-86ad-48b8-811a-07402055cc6d` (project)
- **Filed from:** https://openendedgroup.com/field4/chain_lambda_sound/; also affects interaction_nets_examples.md
- **Filed by:** human, 2026-10-16 00:29
- **Close with:** `python tools/docs_notes.py handoff-close 6ca49e9b "what happened"`

Refiled from 32bfca0b's neighbour (original id in the close note), 2026-10-16. The aside on `chain_lambda_sound.md` ("Why the page measures the counts instead of citing Lafont") and the caveat on `interaction_nets_examples.md` ("what the sharing gets wrong") still stand.

Why this is a project rather than a fix: `LambdaTerms.compile` (`src/jvmMain/kotlin/afield/lang/lambda/lambdaterms.kt`) gives each `DUP` fan a fresh `Lambda.FAN_LABEL`, and `Lambda.rules()`'s `fan-fan` annihilates equal labels and commutes different ones. Copying a fan copies its label, so two copies of one fan can later meet and annihilate wrongly. That is the known failure of fan-only sharing on terms whose duplications nest, and three of the four `LambdaSoundDocs.CONFLUENCE_PROBES` hit it. No relabelling scheme fixes it in general. A correct front end needs one of:

- **Lamping / Asperti–Guerrini levels:** fans indexed by level, plus bracket and croissant agents and their rules (the "oracle"), with readback that understands levels.
- **Lambdascope** (van Oostrom, van de Looij, Zwitserlood): scope delimiters, one agent family, and a simpler rule set. Probably the smallest correct option.
- **Closed reduction** (Mackie): not optimal, but correct with a small change to when β may fire.

Each of these changes `Lambda.rules()`, `compile` and `readback`, and so the event vocabulary `LambdaSoundDocs` sonifies (`EVENT_KINDS`). The four sonified pages' audio and the rule-tally readouts would all change. Pick a target (optimality, or just correctness), then implement it with the four probes plus the corpus checked against the normal-order evaluator, and delete the aside and caveat.
