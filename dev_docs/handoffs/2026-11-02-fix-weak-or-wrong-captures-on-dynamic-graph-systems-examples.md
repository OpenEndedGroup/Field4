# Fix weak or wrong captures on dynamic_graph_systems_examples.md (six_systems.png)

- **Handoff:** `b5bca74f-6bdd-42b6-a784-2fb645ffd49c` (project)
- **Filed from:** https://openendedgroup.com/field4/dynamic_graph_systems_examples/
- **Filed by:** human, 2026-11-02 14:19
- **Close with:** `python tools/docs_notes.py handoff-close b5bca74f "what happened"`

Found in a 2026-11-02 audit of every image on the published docs (eight reviewers, each image looked at against its alt text, caption, code and prose). Each item below was seen in the committed asset; fix the code, the capture hook or the framing — never hand-edit a capture (AGENTS.md §3).

## `assets/dynamic_graph_systems_examples/six_systems.png` — legibility, medium

**Problem.** The bottom-right 'alpha-event: 351e 100%tri' panel draws its edges in a dark indigo that almost disappears into the background. It reads as a field of dots, not the 'fully triangulated' graph that its label and the alt text claim. The hysteresis and routing panels are also dim compared with the top row.

**Fix.** Draw the edges of the dim systems with a minimum brightness or alpha (or normalise the colour ramp per panel) so the triangulation in the alpha-event panel is visible at column width.

Recapture with `FIELD_DOCS_RUNNER_MODE=always ... build_zensical_docs.py --page dynamic_graph_systems_examples.md` and look at each new asset at column width before closing.
