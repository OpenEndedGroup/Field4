# AI4 chat playground: compressed chats linked to their source (plan)

Goal: take a long AI4 chat (first corpus: the house-machine chats, `~/house-machine/workspace/house-machine-{2,3,6..14}`) and
let AI4 agents write **lenses**: shorter, filtered versions of the chat, drawn in the same style as
the chat panel, with lines running across from each summary block to the stretch of the original
it stands for. Lenses can be stacked, so the hierarchy is a lens of a lens.

Status (2026-10-06): phase 1 built (section 8); phases 2–5 planned. Revised after Marc's notes: we trust the chat (it made a house), so
there is no fidelity checking. Tool records count only for which tool ran, whether it failed and
how long it took. What matters is the shape of the chat and the moments the machine itself
changed: notes, the house playbook, recipes, searches and stabilization.

## 1. What the chat already holds

The chat markdown (`<box>_ai4_chat_history_.md`) is enough. We no longer need to join it with the
tool I/O logs.

- Blocks are `<<you>>`, `<<ai>>` (with `<<model:…>>`) and `<<progress>>`. A progress title reads
  `<Tool> finished (N ms)` or `failed`, or is a build or status line. house-machine-10 has 13 user
  turns, 80 ai blocks and 455 progress blocks.
- **Tool output images are inline**, inside ai blocks, each with a caption that says what it was
  for: `_Output of box 'Plan · rooms and openings' (main), 888x810 … looking for: Check first
  three-bedroom plan, …_`, then the image, then the agent's own account of what it saw.
  **Input images** are the user's attachments in `<<you>>` blocks.
- Every tool image is saved, including those taken inside a tool program (`RunToolProgramRequest`).
  Checked on house-machine-10: 16 tool images in the tool I/O logs. 15 are inline in the chat
  markdown; the 16th belongs to a turn that was later rewound, and the session tree still holds it.
- The session tree (`<box>_ai4_session_.jsonl`) is the fuller record. It adds a timestamp, model
  and token usage per turn, and keeps **rewound branches**, with their images as `ai4img:<sha>`
  refs into `<doc>/ai4_blobs/`. The chat markdown is only the current branch. house-machine-10
  has three rewound nodes, including the turn where the model connection dropped mid-build. So
  lenses read the session tree (`renderRaw` per node), and abandoned attempts are available as
  source material (the *Trouble* lens wants them).

Tool-name families give the machine-change track for free (names carry a hash suffix, which we
strip):

| Family | Tools (counts from a first grep; section 8 has the real counts) |
| --- | --- |
| notes / playbook | `DocumentGuideCreate` 20, `…Append` 49, `…Patch` 2; `ChecklistUpdate`, `ScratchpadUpdate` |
| recipes / search | `_field_recipe_candidate` 12, `_test` 16, `_inspect` 12, `_apply` 2, `_proposeRebaseline` 2, `_retainFixture` 2 |
| design edits | `_field_sketch_edit` 57, `_field_services_edit`, `SetBoxProperty` |
| builds / stabilization | `StartStudyBuild`, `WaitStudyBuild`, the `Build … · Check` status lines |
| looking | `ViewBoxOutput`, `InspectGeometryView` (the image producers) |

The family table is a small, editable data file (one entry per tool-name family), not a
hardcoded list.

## 2. Source addressing

Every block has an address, `<nodeId>.b<index>`: the session-tree node that added it and its
position in that node's markdown (`t-f65450f4-0ff.b12`). Node ids are written once and never
reused, so an address stays valid as the chat grows, and a block on an abandoned branch has one just
as a block on the current branch does. User blocks on the current branch also carry their turn
number. Transcript text the tree has not recorded yet (a turn that was interrupted or is still
running) gets the node `unrecorded-tail`. The addresses come from parsing alone: no export step, no
copy of the data.

A small derived index (not a second copy of the chat) lists each block's address, kind, tool
family, tool name, ok/failed, ms, and its images with their captions. The agents and the filters
work from this index.

## 3. A lens is a chat

A lens is written in the same markup as a chat, so `aiChatHistoryToHtml` renders it unchanged,
plus one new marker line that ties a block to its source:

```
<<ai>>
<<source:t-f65450f4-0ff.b12-t-f65450f4-0ff.b47>>
Laid out the first plan: three bedrooms north, open living south. The first check found the
bathroom 300 mm short for a bath; the hall wall moved and the second check passed.
![plan after the fix](ai4img:2e830bad36f29af5.jpeg)
```

- `<<source:a-b[,c-d]>>` works the way `<<model:…>>` does. Spans may be discontiguous: a lens that
  pulls together every roof decision cites several stretches.
- A lens of a lens cites addresses in the lens below (`L1:b008-b011`). Lines then chain down to
  the raw chat, which gives the hierarchy: series overview → house storyline → turn digest → chat.
- Images are referenced by blob hash (`ai4img:`, as the session tree already does), so a lens can
  reuse the chat's pictures without copying base64.
- Kept checks are structural only: the cited addresses exist (otherwise the lines can't be
  drawn). Nothing checks whether a summary is true to its source.
- Lenses are saved next to the chat as `lenses/<name>.md` plus a few lines of front matter: the
  lens prompt, source chat, model and date.

Lenses to start with, all written by talking to AI4:

- **Storyline**: one block per episode, with the decisive picture and moments of insight.
- **Machine changes**: only the notes, playbook, recipe, search and stabilization moments, with
  why each happened.
- **Trouble**: failures, round caps, rewinds and recoveries, each with what fixed it.
- **Images**: every tool image in and out: the producing tool, what it was looking for (from the
  caption), the image, and what the agent concluded. Most of this can be extracted without a
  model: caption, tool, the next ai text, its address. An agent adds one line on what each image
  is doing in the story. Without the agent it is a filter, with it a lens.

## 4. Viewer

It is drawn in the chat's own style (`field.css`, the `.ai-chat-*` rules, the existing fence and
image handling):

- **Two or three columns**: lens on the left, source chat on the right, a lens of a lens further
  left. Each column is ordinary `aiChatHistoryToHtml` output.
- **Lateral lines**: an SVG layer between columns draws a band from each lens block to the span it
  cites. Bands follow scrolling, as merge tools do, and discontiguous spans fan out. Hovering a
  band highlights both ends; clicking a lens block scrolls the source to its span.
- **Compressed source**: in the source column, runs of progress blocks fold to one line per round
  ("7 tool calls · 3.4 s · 1 failed · 2 note writes"), coloured by family. This is the same code as
  the live-panel improvement below.
- **Filters** on the source column, by family and by failure and image, so you can show only the
  note writes, for example. A filter is simply a lens that needs no model.
- **Offline**: one self-contained HTML file (CSS, the exported renderer functions, mermaid,
  images) so a lens can be sent or presented without Field.

## 5. The playground document

A starting point, `ai4_chat_playground`:

- **Corpus box**: choose a document and chat box (the house documents are opened read-only or as
  copies; never point a mutating session at one Marc is using).
- **Tools for agents**, registered with `aiTool{}`:
  - `chatlog.index(filter)` and `chatlog.read(span, maxChars)`: paged, with images as captions
    unless asked
  - `chatlog.images(span)`
  - `lens.write(name, markdown)` (checks addresses), `lens.read(name)`, `lens.list`
- **Lens boxes**: one per lens. Each box's AI4 chat is where you make and tune that lens ("shorter",
  "keep every roof decision", "write it for someone who has never seen Field"). The lens prompt
  lives in the box, so lenses are easy to copy and compare.
- **Viewer box**: displays the columns and lines in an output channel, and exports the offline
  file.

## 6. Phases

1. **Parse and index**: commonMain parser, block addresses, family table, and the index for houses
   2–12. Report per house: turns, rounds, family counts, failures, images, rewound branches.
2. **Live-panel fold**: group each round's progress blocks, in the panel we use every day. This
   ships on its own.
3. **chatlog/lens tools and one lens by hand**: an AI4 agent writes a *Storyline* for
   house-machine-10. Read it before building more.
4. **Viewer**: two columns, lateral lines, filters; then the offline export.
5. **Stacking and the other lenses**: lens of a lens, a series-level storyline across all houses,
   and the Machine-changes, Trouble and Images lenses.

## 7. Where things live

The machinery (parser, index, tools, viewer, starting point) lives in field4. House chats and
their lenses live in `~/house-machine`, and never in the field4 repo or the docs pipeline. A file
for investors gets a redaction pass (paths, names, provider ids) before it leaves the machine.

## 8. Phase 1: what was built and what it found

Code:
- `src/commonMain/kotlin/aiChatBlocks.kt`: `parseAiChatBlocks`, moved from the panel renderer, so the
  panel and the archive share one parser. It also has `toolCall()` (the progress title),
  `programCalls()` (a tool program's `- ✓/✗` lines) and `images()` (with captions).
- `src/commonMain/resources/ai4-chat-tool-families.json`: the family table.
- `src/jvmMain/kotlin/afield/chatlog/ChatArchive.kt`: reads a document folder's chats as trees,
  read-only, with no Field server or box involved. `ChatArchiveCli.kt` reports on them.
- Tests: `ChatArchiveTest` (JVM). The chat UI regressions (`ai-chat-scroll`, `ai-chat-resize`,
  `ai-chat-fence-renderers`) cover the panel's switch to the shared parser. They caught a pattern
  the JVM accepts and the browser does not: Kotlin/JS regexes are unicode-mode `RegExp`s, where a
  bare `]` is a syntax error. Nothing on the JVM side can catch that, so run them after any change
  to `aiChatBlocks.kt`.

Run it (read-only):

```
uv run --project . --group backdoor python tools/field_backdoor.py --json --command "run-jvm-main afield.chatlog.ChatArchiveCli --arg=--workspace --arg ~/house-machine/workspace --arg=--match --arg 'house-machine-*' --arg=--out --arg <dir>"
```

The house corpus on 2026-10-06 (12 chats in 11 houses; houses 4 and 5 have none; 13 and 14 store
theirs inline in `metadata.edn`):

| | turns | rewound turns | replies | calls | inside programs | failed | tool images | tokens in |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| total | 127 | 7 | 623 | 1,898 | 686 | 122 | 142 | 131 M |
| largest (house-machine-10) | 13 | 2 | 90 | 343 | 151 | 23 | 16 | 25 M |

- Calls by family across the corpus: computing 683, reading 635, builds 536, looking 325, design
  162, notes 121, recipes 69, tooling 53. No call falls outside the table.
- No chat has a user attachment: every image is a tool image. None is missing from `ai4_blobs/`.
- Three chats (houses 10, 11, 12) have rewound branches. Houses 12–14 end in turns the tree has not
  recorded yet. Several older chats end in one unrecorded status line.
- Tool time includes calls that hung: house-machine-10's 10.5 h includes `ViewBoxOutput` and
  `InspectGeometryView` calls of 49–50 minutes from the night the connection dropped.
