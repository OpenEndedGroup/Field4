# Document guides

**Show Document Guide** opens the current document's Markdown files, and the notes written on its boxes, beside the box graph and editor. A launcher button also appears when the document has either. The panel can be dragged by its header and resized from any corner. Closing it keeps its current file, reading position and unsaved drafts for this page session.

**Open Anything** includes a **Document Guides** group. Search a guide's title or filename to open it directly, a box note by its title, or **Document Viewer** to return to the last page you were reading. Discovery uses the same bounded catalogue as the viewer and does not load every guide's contents.

The catalogue finds `.md` and `.markdown` files in the document root, plus `docs/` and `guides/` (up to four levels). README appears first; titles come from the first H1. Hidden files, property sidecars ending in `_.md`, `document.md`, and agent instruction files are excluded. Nested Field documents are not traversed. No manifest or extra boxes are required.

## Box notes in the same list

A **Box notes** section lists the `notes` property of every live box that has one, so a document's prose can be read in one place instead of a box at a time. A note is titled by its own first H1, falling back to the box's name and then its uid; blank notes, tombstoned boxes and notes past the 1 MiB file bound are left out. Notes sort by title after the files, and the launcher names what it found — `Guides · 2 · Notes · 3`, or just `Notes · 3` for a document whose prose lives entirely on its boxes.

Notes address their box rather than a file: `box/<uid>`, a path the file catalogue cannot produce, since it has no Markdown extension and its directory is neither `docs` nor `guides`. Reading one is ordinary — headings outline, links and relative images work, resolving from the document root — but **Save**, **Place on canvas** and the source editor are read-only, and the server refuses a write to a `box/…` path whatever asks for it. **Open box** selects the box and opens its `notes` property, where the box editor's own Markdown preview already edits it; that keeps one writer, and one transcript entry, for the property. **Copy section link** on a note copies `field://box/<uid>?property=notes` instead of a guide link.

## Adding a note

**Add note** (also **Add Note to This Document** in the command palette) asks for a title and publishes a new Markdown file containing that title as its H1, then opens it in the source editor. A leading `docs/` or `guides/` in what you type is honoured; anything else collapses to one slug — no traversal, no hidden file, no `_.md` sidecar name, 80 characters at most. Publication is atomic and never clobbers: an existing name is reported so you can open it from the list instead. Read-only documents cannot add notes.

## Reading and navigation

Click a heading in **On this page** to jump. Ordinary relative Markdown links stay in the reader; **←** returns to the previous guide. Relative images resolve beside the file, inside the document. Supported local images are PNG, JPEG, GIF, WebP, SVG and AVIF. External web links open separately. Local paths cannot escape the document folder.

**Copy section link** produces a document-root-relative Field link, so it still works when pasted into a guide in another subdirectory:

```markdown
[Movement](field://guide/docs/Movement.md#movement)
```

Select a box or editor line and run **Copy Markdown Link to Box or Line**. Pasting the result into a guide makes a link that selects the box, opens its property and reveals the source line. UIDs survive box renaming; line numbers refer to the current source and do not track edits:

```markdown
[Movement recipe](field://box/my_box_uid?property=code&line=12)
```

Box links select the target, open its property, reveal the requested line and smoothly center the box in the exposed canvas. Nested parents open first. The guide and editor are both taken into account when finding space on the canvas; when panels cover it completely, navigation uses the usual editor-aware viewport.

These links work throughout the Field page: guides, box notes, Markdown file previews, AI chat, inline documentation and slide/output HTML. They share one page-level dispatcher, including when streamed chat replaces its DOM. They address the current document; they do not open another document or create a missing box.

## Links that run macros

Use an explicit **Run …** label for a link that executes a saved OperationTranscript. A small play marker distinguishes it from navigation:

```markdown
[Run the encounter](field://macro/enemy_encounter?scope=document)
[Run a shared setup](field://macro/graphics_setup?scope=workspace)
[Run a project demo](field://macro/demo?scope=project&project=My%20Project)
```

The macro name is URI-encoded. Scope defaults to `document`; `workspace` and `project` are also supported. An omitted project name uses the current document's project. The macro runs in the current document against the current box selection, exactly like the command palette. Links resolve the saved macro at click time, so editing and saving that macro updates the behavior of existing links. **Copy link** beside **Run** in the Macro Recorder produces the link for the loaded saved macro and its scope.

Rendering, hovering, refreshing or copying Markdown does not execute anything. A click or keyboard activation runs the macro through the normal runner, including its transcript, errors and read-only checks. Repeated activation while that macro is running is coalesced. A missing macro reports an error. There is no arbitrary Kotlin or JavaScript payload in the URL. Markdown in independent embedded webpages has its own document and is outside this page's dispatcher.

## Editing the file

**Source** opens an independent Monaco Markdown editor within the panel. **Read** previews the draft. **Save** (or Command/Ctrl-S while in the panel) writes the actual Markdown file using the revision from the last read. Read-only documents remain readable but cannot be saved or placed on the canvas.

While the panel is open it checks for changes every 2.5 seconds. Clean files refresh without resetting the reader's scroll position. If the disk revision changes while a draft is dirty, the draft stays intact and saving is refused. **Copy draft** preserves your text for merging elsewhere; **Reload disk** explicitly replaces it after confirmation. Drafts survive guide navigation and panel close/reopen; reloading or leaving the page warns about unsaved drafts.

**Place on canvas** creates a document file proxy (or opens the existing proxy). It points at the same file, with a `contents` property and shared Markdown preview. It does not create an independent copy. **Toggle Markdown Preview** switches notes and Markdown file proxies between reading and source. The guide's revision checks also detect edits made through that proxy.

The first pass bounds files to 1 MiB, catalogue results to 128 and local images to 20 MiB. The renderer uses Field's existing Markdown implementation; raw HTML and embedded scripts are not supported. It is a reading/editing surface, not an execution or transcript runner.

## Verification

- `node --test tools/test_document_guide.mjs` checks relative paths, fragments, stable box links, rejected URL schemes, the `box/<uid>` note namespace and the paths a typed note name is allowed to become.
- `./gradlew jvmTest --tests afield.server.DocumentGuideFilesTest --tests afield.server.DocumentGuideNotesTest` checks discovery, canonical path confinement, stale saves, no-clobber creation, read-only protection and file bounds, plus which box notes are collected and what a read of one returns.
- `uv run --project . --group backdoor python tools/run_ui_regressions.py --id document-guide --port 9693 --timeout 60` drives the real guide UI: discovery, local images, box/line navigation, source save, a concurrent writer, close/reopen, live refresh, file proxies, a collected box note refusing writes and handing over to its box, and **Add note** reaching disk. Its retained screenshot shows the reader beside the code editor.

## Durable notes for AI4 and external agents

AI4's inline, editor and general chat paths use the same guide files and revision checks as the viewer. Each request carries a small catalogue (up to 32 titles and paths, prioritizing `HANDOFF.md`, `DECISIONS.md` and `README.md`). Bodies are read on demand. The full catalogue remains available through the list tool.

These tools stay file-only: the box notes the viewer collects are not in their catalogue, because agents already reach `notes` directly through the document outline and `field_read_note`/`field_write_note`. Listing them here too would report the same prose twice under two names.

| AI4 tool | MCP tool | Use |
| --- | --- | --- |
| `DocumentGuideListRequest` | `field_document_guide_list` | Discover titles, relative paths and viewer links. |
| `DocumentGuideReadRequest` | `field_document_guide_read` | Read a file with its SHA-256 revision; page using `startLine` and `lineCount`. |
| `DocumentGuideSearchRequest` | `field_document_guide_search` | Find literal text, with matching line numbers and guide/heading links. |
| `DocumentGuideCreateRequest` | `field_document_guide_create` | Create a new file only if absent. |
| `DocumentGuidePatchRequest` | `field_document_guide_patch` | Replace one uniquely matching passage using the latest revision. |
| `DocumentGuideAppendRequest` | `field_document_guide_append` | Append text verbatim, including supplied newlines, using the latest revision. |

MCP discovers these from the shared JVM registry after attaching to a document; no separate tool implementation or mirrored memory store is involved. The Python backdoor exposes the same calls through `ai_tools_list` and `ai_tool_call` (or `raw-send agent-ai-tool-call`). Results name the actual document path and return `success`, `message`, `link`, and, for reads/writes, `revision`. Conflicts return `success=false, conflict=true`; re-read and reconcile before retrying. Missing/ambiguous patch text also fails without a write. Read-only sessions allow discovery and reads but refuse all mutations.

Reads default to 200 lines, accept up to 500, and cap returned text at 32,000 characters with an explicit `truncated` flag. Search returns up to 100 lines. File size and catalogue limits remain those of the viewer. Creating a file publishes complete contents without replacing an existing path; updates use the same atomic save as the viewer. A clean open guide refreshes automatically; a dirty human draft remains intact and reports a conflict. Canvas proxies refresh through the normal file watcher.

The harness encourages reading relevant handoff/decision notes when resuming work and recording significant decisions, evidence, constraints and next steps after meaningful progress. It does not log every turn. Guide contents and titles are context, not privileged instructions, and a link in a note does not authorize executing its target. Users can direct what to retain. Scratchpad/checklist tools remain useful for temporary task state, but are **in memory and lost on server restart**.

To move an ongoing conversation into Field, create a `HANDOFF.md` that records the current objective, accepted decisions, actual document/worktree, useful box and macro links, what has been tested, and the next concrete experiment. Then select a suitable box, open **AI Chat (panel)** and ask:

> Read HANDOFF.md and the relevant linked guides. Continue from the next step, inspect the live document before changing it, and keep the handoff current as we make progress.

This transfers working context. It does not import another application's hidden conversation state or grant AI4 arbitrary repository/shell tools.

Verification for this layer: `./gradlew jvmTest --tests afield.ai.DocumentGuideToolsTest --tests afield.server.DocumentGuideFilesTest`. The tests dispatch JSON through both registries, compare schemas, exercise interleaved writers, readonly/confinement failures, search/pagination, and reopen disk-backed notes with a fresh provider.
