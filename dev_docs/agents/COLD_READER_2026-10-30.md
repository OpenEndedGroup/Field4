# Cold-reader review, 2026-10-30 (phase 1: findings only)

Each page was read once, alone, in rendered order, the way a newcomer reads it: no source and no
linked pages. Findings use the categories in the brief and are limited to what
`DOCS_WRITING_RULES.md` supports (rules 1-9). 149 raw findings were cut to 134. The 15 dropped were
weak, style-only or wrongly categorised.

All 134 are open notes in the docs-notes queue, tagged `cold-reader-2026-10-30` plus their
category. They were created through `tools/docs_notes.py`'s `Store` API and anchored with
`docs_notes.py sync`: 133 are `exact`, and one (`drawing_and_notation.md`, the figure-description
note) is unanchored. The store (`~/.afield/docs-annotations.json`) is not tracked, so the findings
are also committed beside this file as `COLD_READER_2026-10-30.findings.jsonl`, one row per note
id, to rebuild from.

## Pages reviewed (21)

Priority group 1 is done except for `tour_interface.md` and `keyframer_examples.md`, which were
skipped because another branch is rewriting them. Group 2 is the nav's *Start Here* section, all of
it. The long pages (`fline_examples.md`, `kotlin_overview.md`, `funFeatures.md`,
`animation_time_model.md`, `motion_recordings.md`) were read closely in their first half or first
third and skimmed after that. Group 3 (the 25 most-linked other pages) and the *Long Way Round*
chain pages other than `chains.md` were **not** reviewed.

## Findings per page per category

| Page | unparseable | undefined-term | page-meta | mechanism-first | dense | fence-unintroduced | broken-markup | ceremony | total |
|---|---|---|---|---|---|---|---|---|---|
| `staging.md` | 3 | 2 | 4 |  | 3 |  |  |  | 12 |
| `funFeatures.md` | 3 | 1 | 4 |  | 3 |  |  |  | 11 |
| `kotlin_overview.md` | 2 | 2 |  | 1 | 2 |  |  | 3 | 10 |
| `sound.md` | 1 | 3 | 2 | 2 | 1 | 1 |  |  | 10 |
| `animation_time_model.md` |  | 3 | 2 |  | 2 | 1 |  |  | 8 |
| `motion_recordings.md` | 1 | 3 | 2 |  | 2 |  |  |  | 8 |
| `fline_examples.md` | 1 | 1 | 1 | 1 | 2 | 2 |  |  | 8 |
| `mobile_controllers.md` | 2 | 3 |  |  | 1 | 1 |  |  | 7 |
| `index.md` | 2 | 1 | 2 |  | 1 |  |  |  | 6 |
| `survivalGuide.md` | 2 | 2 | 1 |  | 1 |  |  |  | 6 |
| `installation.md` |  | 3 |  | 1 | 1 | 1 |  |  | 6 |
| `automating_field.md` | 3 | 1 | 1 |  | 1 |  |  |  | 6 |
| `keybindings.md` | 2 | 1 |  |  | 3 |  |  |  | 6 |
| `drawing_and_notation.md` | 2 | 2 | 1 |  | 1 |  |  |  | 6 |
| `first_steps.md` | 1 | 3 |  |  | 1 |  |  |  | 5 |
| `chains.md` | 1 | 2 | 1 | 1 |  |  |  |  | 5 |
| `tour_gpu_graphics.md` |  | 2 |  |  |  | 1 |  | 1 | 4 |
| `tour_data.md` |  | 2 |  |  | 1 |  |  |  | 3 |
| `completion_doc_browser.md` |  | 2 |  |  | 1 |  |  |  | 3 |
| `tour_connecting_boxes.md` | 1 | 1 |  |  |  |  |  |  | 2 |
| `privacy.md` |  | 1 |  |  | 1 |  |  |  | 2 |
Totals: undefined-term 41, dense 28, unparseable 27, page-meta 21, fence-unintroduced 7,
mechanism-first 6, ceremony 4, broken-markup 0. No markup script was written: a newcomer reading
in render order found no broken asides on these pages.

## The five worst pages

1. `staging.md` (12). Invented names ("Long Way Round", "docks, islands and tethers"), and a false
   self-reference: "through the transcript-driven renderer parent described at the foot of this
   page" (nothing at the foot describes it).
2. `funFeatures.md` (11). The page that says what Field is keeps describing how the docs are made:
   "That's also how this documentation gets made: the docs build runs the examples in a real Field".
3. `kotlin_overview.md` (10). Still teaches `_print`, and says "whatever is printed arrives at the
   bottom of the text editor", which contradicts the Tour.
4. `sound.md` (10). A wall of API names before any idea: "A pattern answers a query with a list of
   events" (neither *query* nor *arc* is defined).
5. `animation_time_model.md` (8). False page-meta, "The video at the top of this page" (the video
   comes after two examples), plus "pull-based generator layer".

## Ten representative quotes

- `index.md` 15 (page-meta): "you are reading the nightly test suite right now"
- `index.md` 11 (undefined-term): "comprehensive, reflexive and self-improving set of tools"
- `first_steps.md` 48 (undefined-term): "The drawing is made of `FLine` paths drawn into the box's output"
- `survivalGuide.md` 80 (page-meta): "# Three things you'll want to know" (the page has four sections)
- `installation.md` 117 (dense): "The menu covers Python/JEP, computer vision and voice; Vulkan/OpenXR and shader tools;"
- `tour_connecting_boxes.md` 126 (unparseable): "...draws with that renderer without..." / "the next stop depends on it"
- `automating_field.md` 97 (page-meta): "Many of this site's figures are made this way."
- `keybindings.md` 223 (unparseable): "The `?` card is the third route, and the shortest one" (there are two routes)
- `motion_recordings.md` 259 (unparseable): "the walking-route trials below slide"
- `fline_examples.md` 1971 (unparseable): "### Filtering + interpolation (Gaussian process / EMA / One Euro / alpha-beta / driver pur..."

## Phase 2 (not started)

Run this once the `tour_interface` / `keyframer_examples` branch has landed:

```bash
uv run python tools/docs_notes.py revise --tag cold-reader-2026-10-30 --dry-run   # check the plan
uv run python tools/docs_notes.py revise --tag cold-reader-2026-10-30 --agent claude --jobs 4 --commit
```

The partitioning is the tool's default, one partition per page. The dry run reports
`134 note(s) in 21 partition(s), 4 at a time`, which means 21 pages and 21 agent runs, with 4
running at once.
