# Recapture the document-history figures: non-current version selected, plus the in-editor diff preview

- **Handoff:** `a479ff63-8ba5-40f8-a08e-08da7cbe8013` (project)
- **Filed from:** https://openendedgroup.com/field4/document_history/
- **Filed by:** agent:claude, 2026-09-30 20:43
- **Close with:** `python tools/docs_notes.py handoff-close a479ff63 "what happened"`

## The notes that led here

- `5acef3d9` (public/docs/document_history.md:3): This image would be more exciting if we selected something that wasn't the current version
  > ust a few boxes.
- `5eefd551` (public/docs/document_history.md:60): We should have a screenshot of the history visualization in the editor
  > Blame a line — see which version last changed a particular line.

## What was asked

Two notes on `public/docs/document_history.md` ask for new pictures:

1. Line 5, the lead figure `assets/history-panel.png`: "This image would be more exciting if we
   selected something that wasn't the current version."
2. Line 60 ("Blame a line"): "We should have a screenshot of the history visualization in the
   editor."

## Why this is bigger than a docs edit

Neither figure is a docs-runner capture. `assets/history-panel.png` (and its sibling
`assets/history-lens.png` on `history_lens.md`) are static PNGs committed by hand; their last
change is 36e61332d9 (2026-07-11), and no fence or hook regenerates them. Per AGENTS.md §3 ("A
capture exercises the real code, or it does not exist") a new picture has to come from a real
Field session, and this one needs state the runner does not build today: a document with several
saved versions in `backups/` so there is something non-current to select.

The editor visualization exists: when a version is selected in the history iframe and the
selected box has a text property, `showEditorPreviewForProperty` in
`src/commonMain/resources/history.html` (around the `aidiff.apply(editor, targetText, { monaco,
readOnly: true })` call) pushes that version's text into the main Monaco editor as a read-only
`AIDiff` inline diff. The **Line** button (`#line-blame-btn`, "Filter to versions that affected
the current editor line") filters the rail to versions touching the editor's cursor line. A
screenshot of either needs the history iframe *and* the editor open together, with a box
selected. I added one paragraph of prose describing the preview; the picture is still owed.

## Proposed approach

Add a capture path that builds real history and then drives the history view:

- create a document through the backdoor, run/edit one box's `code` a few times with saves in
  between (so `backups/metadata_backup_*.edn` holds genuine versions);
- select that box, open its editor, run `Show Document History`;
- click a non-current version (e.g. `#2`), which also triggers the in-editor preview;
- `screenshot-window` for the whole scene (timeline with a non-current selection + editor diff);
  optionally a second shot with **Line** pressed.

`tools/run_ui_regressions.py` already has a `history-panel` regression (`HISTORY_PANEL_OPEN_JS`,
`screenshot-window .../history-panel-history.png`) that is the natural starting point; a
transcript macro (`dev_docs/agents/transcripts/`) or a docs fence with a `drive` script are the
alternatives. Making it a runner capture would also stop the figure going stale.

## Questions for a human

- One combined figure (timeline + editor diff) replacing the lead image, or keep the lead as the
  timeline and add a second figure under "Search, blame and named versions"?
- Should this become a docs-runner capture (reproducible) or a one-off real capture committed as
  an asset like today?
- Is "the history visualization in the editor" the AIDiff preview described above, or the
  History Lens (`history_lens.md`, already pictured there)?

## What the page needs afterwards

Swap `assets/history-panel.png` for the new capture (alt text naming the selected version), and
add the editor figure next to the paragraph beginning "If a box is selected…" in "What the
timeline shows", or beside the **Blame a line** bullet.
