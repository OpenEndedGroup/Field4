# Mark the canonical graphics LESSONS index as for agents and link humans to the graphics page

- **Handoff:** `53c92bea-a9a9-4155-bd66-e92778fcad52` (project)
- **Filed from:** https://openendedgroup.com/field4/assets/canonical_graphics/LESSONS/
- **Filed by:** agent:claude, 2026-10-31 14:18
- **Close with:** `python tools/docs_notes.py handoff-close 53c92bea "what happened"`

## The notes that led here

- `0e14cc15` (assets/canonical_graphics/LESSONS.md): This is a silly page without an offline viewer preview. All those UUID? come on, this is surely for agents (so we should mark it as for agents)

## What was asked

Note 0e14cc15 on `public/docs/assets/canonical_graphics/LESSONS.md` called the page silly for human readers: it is all box UIDs and has no offline-viewer preview. In the note thread, the human decided to **mark it as "for agents" and link humans to a better page**.

## Why this is not a docs edit

LESSONS.md is generated. Its header says "Do not hand-edit: regenerate with that script", and `tools/test_canonical_graphics_index.py` (run by `checkPythonTools`) compares the committed file with the generator's output. So a hand edit would fail that guard and be lost the next time the page is regenerated. The text comes from `render()` in `tools/gen_canonical_graphics_index.py` (the title is appended around line 222, and the intro paragraph follows it). The docs-pass agent owned only the generated file, not the generator.

## Proposed approach

In `render()`, put a banner straight after the title, for example:

```
!!! info "For agents"
    This index is reference material for agents writing box-level graphics code: it maps lesson names and API symbols to the uid-named files in this folder. If you are a person learning Field's graphics, read [Field 4 Graphics](../../afield_four_graphics.md) or the [GPU graphics tour](../../tour_gpu_graphics.md) instead.
```

Then regenerate the page and commit it together with the generator change.

Alternatives:
- Leave `assets/` pages out of the published nav or search, so humans never land on this page. This touches the site config.
- Give each row an offline-viewer preview link (`offline.html?file=...`). This only works if the canonical_graphics snapshot is published as an offline-viewable document, and that has not been checked.

## Questions for the human

- Which page should the banner send humans to: `afield_four_graphics.md`, `tour_gpu_graphics.md`, or both?
- Do you also want offline-viewer previews per lesson, or is the banner enough?

## What the page needs afterwards

Nothing more by hand. Once the generator change lands and the page is regenerated, close the note.
