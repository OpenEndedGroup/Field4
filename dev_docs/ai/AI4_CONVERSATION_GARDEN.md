# AI4 conversation garden: first working slice

Open a box's existing AI4 chat and choose **Garden**. This opt-in view imports the current
linear transcript once; the ordinary panel, transcript, archived branches and provider
continuation remain available. A garden is saved with the box as `ai4_garden` in a JSON sidecar.

## Reading and replying

Hover a paragraph and choose **Reply**, or select text within one passage and choose
**Reply to selection**. The composer shows its attachment. **Through this paragraph** gives
the agent the source's ancestry ending at that paragraph. **Whole response** includes the
source's entire response as it existed when the reply was queued. A selection records the
quotation inside its source paragraph; the context boundary is the paragraph, not the last
selected character. **New root** starts an independent path.

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
- Option–Return or **Queue** saves without dispatching.
- **Run** starts one queued entry; **Play queue** runs saved entries serially, in creation order.
- **Pause queue** holds further dispatch. It does not cancel an active agent. A failed run also pauses the queue.
- Closing the garden pauses the viewer's queue dispatcher. Entries stay saved; an active agent
  keeps running. Reopen and press Play to continue the queue.

This slice deliberately runs one agent per owner box, including the ordinary chat, using the
existing `ai-assist` harness. Incoming linear-chat messages cannot steer a garden agent.
Each garden node gets a separate provider thread and agent scratchpad. Its frozen ancestry is
seeded through the existing bounded saved-conversation context (64,000 characters, image bytes
excluded); unrelated siblings are available only through an explicit tool read. The live box
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
