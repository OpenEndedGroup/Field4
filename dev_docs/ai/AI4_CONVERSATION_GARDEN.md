# AI4 conversation garden: first working slice

Open a box's existing AI4 chat and choose **Garden**. This opt-in view imports the current
linear transcript once; the ordinary panel, transcript, archived branches and provider
continuation remain available. A garden is saved with the box as `ai4_garden` in a JSON sidecar.

## Reading and replying

Release the pointer over prose to reply: selected words become the quotation; with no selection,
the reply includes the whole agent response. A human prompt stays an independent target. The
draft opens at its source and grows as you type. A custom reply pointer replaces reply buttons.
Right-click a paragraph for Field's marking menu: reply, copy a paragraph reference, focus its
path, or collapse/expand its replies. Left disclosures match output-area triangles. Shift shows a focus mark and click toggles that
path solo; Alt shows a red × and click deletes the branch and its descendants. Code edits are
retained; a branch with a running response must finish before deletion. Folds survive streaming updates and refreshes in the open panel.

Each running response has a quiet animated ellipsis at its own output position; concurrent
branches keep separate placeholders. Reduced motion uses static dots.
Tool activity is hidden initially. The monochrome tool button in the title bar reveals wrapping
cyan activity rows at half opacity. Dispatch options appear only on Option–Return.
Collapsed branch labels keep the source typography and alignment, with reduced opacity.

Disclosures rotate when folded (90 degrees in the gutter, 180 degrees at a quotation). New
and disappearing text blocks fade; unchanged prose stays steady during streaming. Reduced
motion disables these transitions. Command-drag a disclosure to move its entire branch:
a line previews attachment below a paragraph, or insertion before/after another branch near
the edge of its prompt. Escape cancels; the reading area scrolls at its edges. A branch cannot
be dropped inside itself or moved while one of its responses is running. Moves preserve the
original turn context and all written responses; future replies follow the destination ancestry.

Reading prose and drafts use self-hosted variable Noto Serif at 90% width, with normal and italic
faces; controls and code retain Iosevka. The reading width is set by `--garden-reading-width`.
Agent cyan and human amber use the shared chat colour tokens; panel surfaces and one-pixel
hover rules use Field panel and border tokens, including the light-theme palette on main.

Human prompts and agent responses have independent paragraph addresses. A copied reference
opens the exact source paragraph; replying to a human prompt through that point excludes its
agent's response. Existing reply anchors and frozen context retain their original meanings.

The draft appears at its attachment, with no persistent send controls. Return dispatches it;
Option–Return opens its reply options. A quoted reply has an inline fold mark at the start of
the selected words: hover reveals the quotation, click folds or unfolds the branch. Selection
offsets are pinned to the source revision. Both inline marks and branch-gutter disclosures keep
their hit targets in the same place when opened and closed.

A normal reply gives the agent the source's ancestry ending at that paragraph. A selection
records the quotation, while the context boundary remains the paragraph, not the last selected
character. **Whole document** explicitly includes all current Garden branches. **New root**
starts an independent path.

**Reading** inserts replies at their attachment points; **Outline** makes the hierarchy compact.
**Focus** shows an entry's path to root, omitting sibling replies and later source paragraphs
for a through-paragraph reply. **Path to root** toggles that focus. A phrase filters paths by
text; `#tag` matches paths containing an entry with that hashtag, retaining its ancestry and
descendants. Tags are case-insensitive and stored on human entries.

Paragraph identities persist as their streaming text grows. Anchors pin content revisions:
a later edit cannot silently change what a queued reply will receive. If a source paragraph
was replaced before the server could accept the reply, the server refuses the stale anchor.
Earlier queued replies keep the text they already pinned.

## Dispatch

- Return saves a reply and starts its agent if the box is idle.
- Option–Return opens a small keyboard menu: **Queue** saves without dispatching; **Queue and play** saves and starts the serial queue; **Whole document** sends using a frozen snapshot of every current Garden branch, deduplicating their shared ancestry.
- A queued entry’s hover **Run** starts it; **Play queue** in the reply menu runs saved entries serially, in creation order.
- **Pause queue** in the reply menu holds further dispatch. It does not cancel an active agent. A failed run also pauses the queue.
- Closing the garden pauses the viewer's queue dispatcher. Entries stay saved; an active agent
  keeps running. Reopen and choose Play queue in the reply menu to continue.

This slice deliberately runs one agent per owner box, including the ordinary chat, using the
existing `ai-assist` harness. Incoming linear-chat messages cannot steer a garden agent.
Each garden node gets a separate provider thread and agent scratchpad. Its frozen ancestry is
seeded through the existing bounded saved-conversation context (64,000 characters, image bytes
excluded); default replies can read unrelated siblings only through an explicit tool read.
Whole-document dispatch freezes the current contents of all branches instead, preserving which
context was actually submitted even when a branch later changes. The live box
code is read at dispatch. Browsing or changing conversation paths does not restore box state.

The agent receives `ReadConversationGardenRequest` for a bounded outline, search or paged node
read across the garden. The tool reads only this owner's conversation. It does not silently
add other branches to the run's ancestry.

## Code inside the conversation

The native box tools record actual new-box source, property writes, text patches and writes
made by RunBox/LaunchBox. Each card offers **Change**, **Then** (the resulting historical text)
and **Now** (open the current box property in the editor). Text is shared by content hash;
subsequent live edits cannot rewrite a card's history. Tool execution results remain in the
same run's activity passages. Other write routes, including repository-file edits and arbitrary
Kotlin evaluation, are not yet represented as code cards.

## Structure and verification

`aiGardenModel.kt` contains shared wire/storage types. `AiConversationGarden` owns mutations,
revision checks and immutable ancestry; paragraph and code text are content-addressed and
unreferenced streaming revisions are pruned. `conversationGarden.js` renders the independent
view mounted from `outputareas2.js`. Requests use `ai-garden` in the existing document session.
The store fails on unsupported versions or malformed data rather than replacing them.

Verification:

```
./gradlew fastTest -PfastTestRoots=src/jvmFastTest --tests 'afield.ai.conversation.ConversationGardenTest'
uv run --project . --group backdoor python tools/run_ui_regressions.py --id conversation-garden
```

The UI fixture is a test document, not a published capture or an agent-produced conversation.
It makes no model requests. Storage tests cover ancestry isolation, streaming snapshots,
whole-response context, code revisions, interruption and sidecar/portable-document round trips.

## Next slices

The current structure can grow into explicit organising parents, moves and transclusions;
comparison columns; synthesis runs with several parents and deduplicated shared ancestry; and
parallel dispatch with shared-property revision checks. These operations are not exposed yet.
The first slice tests the paragraph-reply experience against the existing harness before those
operations become part of everyday chat.
