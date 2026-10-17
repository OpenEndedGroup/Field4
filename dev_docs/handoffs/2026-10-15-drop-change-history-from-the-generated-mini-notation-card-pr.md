# Drop change history from the generated mini-notation card prose

- **Handoff:** `8d3c27f0-8b7a-45ca-a8af-0f85d0eac1fb` (project)
- **Filed from:** https://openendedgroup.com/field4/pattern_language/
- **Filed by:** human, 2026-10-15 13:45
- **Close with:** `python tools/docs_notes.py handoff-close 8d3c27f0 "what happened"`

The mini-notation cards on `public/docs/pattern_language.md` are generated
(`<!-- BEGIN GENERATED: PatMiniNotation.markdownSection -->`) from the card prose in
`src/jvmMain/kotlin/afield/audio/synth/PatMiniNotation.kt`, so a prose pass cannot edit them. Three
of those strings break DOCS_WRITING_RULES rule 2 (no change history on a user page) or repeat the page:

1. The `[bd|sd|cp]` card: "(It did not until September 2026: `|` was reachable only from inside a
   `[…]`, and the bare form was a parse error.)" Delete the parenthesis; the sentence before it
   already says bare `bd|sd|cp` parses.
2. The `bd(3,8)` card: "It was not always: until September 2026 Field's `bjorklund` stopped one
   pairing late and returned a *rotation* of this figure ... `bd(3,8)` came out `x..x.x..`." Keep
   "This is Tidal's spelling, bead for bead, and so is every other `(pulses, steps)` pair." and the
   point that a rotation is a different groove against the downbeat; drop the history (it can live in
   the KDoc of `bjorklund` or the test that pins the Tidal agreement).
3. The `bd sd` card's note "Colour is per instrument throughout this page: ... so two bars that look
   different sound different." repeats the page's own intro paragraph above the generated block
   word for word in substance. Drop it from the card.

Regenerate the page section in the same change.
