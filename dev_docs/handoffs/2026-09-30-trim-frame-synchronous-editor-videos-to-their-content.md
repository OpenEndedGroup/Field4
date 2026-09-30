# Trim frame-synchronous editor videos to their content

- **Handoff:** `5d06ea0f-b9ed-46cd-bcf9-f1a2803d1cd4` (project)
- **Filed from:** https://openendedgroup.com/field4/visualizers/
- **Filed by:** agent:claude, 2026-09-30 20:38
- **Close with:** `python tools/docs_notes.py handoff-close 5d06ea0f "what happened"`

## The notes that led here

- `5bae9763` (public/docs/visualizers.md:528): Video is great, but the editor has a lot of blank space in the bottom of it
  > Run
- `86422b78` (public/docs/visualizers.md:585): video is great, but again, too much blank space at the bottom of the editor
  > This

## What was asked

Two notes on `visualizers.md`, on the `inline-live-history` and `inline-pianoroll` videos (the
same applies to `inline-metrics`): "Video is great, but the editor has a lot of blank space in the
bottom of it."

## Why this is not a page edit

The fences already ask for an editor capture with `padding="10"`. No fence attribute sets the
height of an editor *video*. `public/docs_hooks.py` (`build_backdoor_command`, the `is_video`
branch around line 4935) passes `record-editor` only `--property --padding --duration --fps
--frame-synchronous`, and no `h=`/`clip-selector` reaches it.

The blank space comes from a deliberate choice in the runtime:

- `src/jvmMain/kotlin/afield/docs/screenshot.kt:1541`: for `frameSynchronous && editorOnly` it
  replaces the content-trimmed clip that `requestWindowScreenshot` returned (Main.kt
  `fitEditorPaneToContentAndNote` + `clipForRenderedEditorContent`, around `Main.kt:2557`) with
  `stableEditorVideoClip(...)`.
- `stableEditorVideoClip` (`screenshot.kt:1729`) clips to the full `.monaco-editor-container`
  bounding rect, meaning the whole editor pane including its empty area below the last line. The
  comment explains why: the trimmed clip changes height as inline view-zones arrive, so measuring
  it after warm-up made the movie geometry depend on timing.

Still screenshots trim to the content, so this only affects videos.

## Proposed approach

Keep the geometry deterministic, but take it from the code rather than from the pane:

1. Before recording, size the editor pane to the content height plus the tallest expected
   inline view-zone (or run the warm-up, let the zones settle, then measure the trimmed clip once
   and freeze it for every frame). A fixed clip is what gives determinism; that clip does not have
   to be the whole container.
2. Alternatively, add a fence attribute (e.g. `editor-height=` / honour `h=` for editor videos)
   that `docs_hooks` forwards to `record-editor` and `stableEditorVideoClip` uses to cap the
   bottom edge. This is cheaper but leaves every author to pick a number.
3. Alternatively, post-crop in the encoder: compute a union bounding box of non-background
   pixels across all frames, then crop. This is deterministic given the frames, and needs no
   layout change.

## Questions for a human

- Is option 1 (freeze the trimmed clip after warm-up) acceptable, given the concern recorded in
  the comment at `screenshot.kt:1542`?
- Should this apply to all frame-synchronous editor videos across the docs, or only behind an
  opt-in attribute?

## What the page needs afterwards

Nothing in the prose. Re-record the three editor videos (`FIELD_DOCS_RUNNER_MODE=always`, or
force those fences) and check the MP4s end just below the last code line.
