# Cold-reader review, 2026-10-31

Continues `COLD_READER_2026-10-30.md` (Start Here and the Tour) down `public/zensical.yml`. Same
method and categories: each page read once, cold, in rendered order, with findings limited to what
`DOCS_WRITING_RULES.md` supports. The findings are in `COLD_READER_2026-10-31.findings.jsonl`, one
row per finding with its page, line at review time, category and exact text. They were not written
to the docs-notes store; each was fixed in this branch, one commit per page
(`docs: revise <page> from N cold-reader finding(s)`).

## Which pages

Every page in *Long Way Round* and nearly every page in *Working in a Document* and *Seeing
Values* was touched in the 48 hours before this pass (bulk sweeps `362458eb2`, `ac38d7c39`,
`520a8d920`), so they were skipped. Walking the nav from `why.md`, the untouched pages reviewed
were the seven below. `gaussian_processes.md` (903 lines) and `paint_sweeps.md` (4135) were
skipped for length; `learned_pen.md`, `paint_canvas.md`, `morse_smale.md`, `pat_spatial.md` and
the sound pages after them are next.

| Page | unparseable | undefined-term | page-meta | mechanism-first | dense | total |
|---|---|---|---|---|---|---|
| `geometry_review.md` |  | 5 | 1 |  | 2 | 8 |
| `presentation_studio.md` |  | 2 | 4 |  | 1 | 7 |
| `patchy.md` | 1 | 2 | 1 | 1 | 1 | 6 |
| `demo_document.md` |  | 3 | 2 |  |  | 5 |
| `layers.md` | 1 |  |  |  | 1 | 2 |
| `matrix_profile.md` |  | 1 |  |  |  | 1 |
| `contour_trees.md` |  | 1 |  |  |  | 1 |

Totals: undefined-term 14, page-meta 8, dense 5, unparseable 2, mechanism-first 1 (30).

## Patterns

- **Changelog voice** on `presentation_studio.md`: sentences that compare with an earlier design
  the reader never saw ("retain their existing behavior", "remains available").
- **Undefined internal vocabulary** on `geometry_review.md`: "canonical solids", "review
  revision", "insertion state". The page's agent-facing sections are still long; a later pass
  could move most of the MCP/backdoor detail to `FIELD_MCP.md`.
- **Build internals first** on `patchy.md`: the toolchain and cache came before what Patchy does.
- The two topology/statistics pages (`matrix_profile.md`, `contour_trees.md`) already read well.

No fence's code changed, so none was re-run and no capture was retaken.
