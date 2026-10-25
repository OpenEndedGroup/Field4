# Stop the docs runner stripping a bare ::: inside code

- **Handoff:** `378789ad-8d68-4064-8362-19bd7d2c87da` (project)
- **Filed from:** https://openendedgroup.com/field4/visualizers/
- **Filed by:** human, 2026-10-15 13:43
- **Close with:** `python tools/docs_notes.py handoff-close 378789ad "what happened"`

Rule 25 sharp edge on `public/docs/visualizers.md` (section "Markdown for captions and tables"). The page carries this aside:

> A bare `:::` also closes a row in a live box, but on a docs page the runner treats a line that is exactly `:::` as one of its own fence-section markers — the same family as the `:::static` at the top of every runnable example — and strips it out of the code before running it. The row then never closes, and swallows the rest of the card in the built page only.

The docs runner (`public/docs_hooks.py` fence-section parsing) should only treat `:::` as a section marker where it actually opens/closes a runner section (e.g. paired with a preceding `:::static`/named section, or only at section boundaries), and leave a bare `:::` inside the code body alone, so docs code behaves exactly like a live box. Add a test with a `markdown(...)` fence closing a row with bare `:::`. In the same change delete the aside "Agents: why the example closes its row with `:::end`" from visualizers.md (the fence can then keep `:::end` or use `:::`; either is fine).
