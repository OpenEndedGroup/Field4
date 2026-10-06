# Reading Saved AI4 Chats

Every AI4 chat is saved with its document. That includes the turns you rewound away. A long chat can be hard to scroll through afterwards, so Field can read a saved chat back as a tree that a box or an agent can work with. An agent can then write a **lens**: a shorter chat that summarizes the long one, where every block says which stretch of the original it stands for. The lens viewer shows the two side by side, with a band from each lens block to the part of the chat it covers.

The examples on this page read a small, made-up chat that ships with the docs, `garden-shed`. It has three requests about sketching a shed, one rewound turn, a failed edit and three tool pictures. To use your own chats, point the same code at a document folder in your workspace.

## A chat as a tree

`ChatArchive.chats(folder)` finds every box in a document folder that has a saved chat. `ChatArchive.tree(chat)` reads one as a tree of turns. It only reads files, so you can point it at a document that is open in another Field. `ChatLogViews.outline(tree)` gives the tree as text, the way an agent first sees it. This box shows it in an output panel:

```diffstatic_runner {data-lang=kotlin screenshot="assets/ai4_chat_archive/outline.png" embed=true w="760" h="50" wait="1.5" timeout="90" name="chat-archive-outline" alt="The outline of the garden-shed chat: three turns with their requests, each reply as its first line, each run of tool calls as one round line with its time, failures, edits and looks, and the rewound branch listed at the end"}
import afield.chatlog.*
import java.io.File

val shed = File("public/docs/assets/ai4_chat_archive/chats/garden-shed")
val tree = ChatArchive.tree(ChatArchive.chats(shed).single())
display("outline", "<pre style='margin:8px'>" + ChatLogViews.outline(tree).replace("<", "&lt;") + "</pre>")
```

Each block has an **address**, `<turn>.b<index>`, such as `t-2.b5`. A turn's id is never reused, so an address stays valid as the chat grows, and blocks on a rewound branch (`t-2a` here) have addresses too. Tool calls are grouped into **rounds**, one line each, and named by what they do: `edit`, `look`, `build steps`, `note`. These are the same round lines the chat panel shows.

To read a stretch in full, give `ChatLogViews.read` its first and last address. `detail = "full"` includes each tool block's text as well:

```diffstatic_runner {data-lang=kotlin screenshot="assets/ai4_chat_archive/read.png" embed=true w="760" h="50" wait="1.5" timeout="90" name="chat-archive-read" alt="Turn three of the garden-shed chat read in full: the request, the failed sketch edit and its reason, the edit that succeeded, the look, the picture's caption and the final reply, each headed by its address"}
import afield.chatlog.*
import java.io.File

val shed = File("public/docs/assets/ai4_chat_archive/chats/garden-shed")
val tree = ChatArchive.tree(ChatArchive.chats(shed).single())
val page = ChatLogViews.read(tree, "t-3.b0", "t-3.b5", detail = "full")
display("turn 3", "<pre style='margin:8px'>" + page.text.replace("<", "&lt;") + "</pre>")
```

## Lenses

A lens is written in the chat's own markup, so the chat panel can draw it. Each block begins with a `<<source:first..last>>` line naming the stretch it summarizes:

```diffstatic {data-lang=text}
<<ai>>
<<source:t-3.b1..t-3.b5>>
The first edit clashed with the corner post; moving the post 50 mm in made room for a 900 mm window.
```

A block can cite several stretches, separated by commas. Pictures from the chat can be reused by their `ai4img:` reference. The only check is that every address exists, since a band can't be drawn to a missing block. Nothing checks whether a summary is accurate. `ChatLogViews.lensProblems` lists the bad citations (here there are none), and `lensStats` says how much of the chat the lens covers and how much shorter it is:

```diffstatic_runner {data-lang=kotlin screenshot="assets/ai4_chat_archive/stats.png" embed=true w="760" h="50" wait="1.5" timeout="90" name="chat-archive-lens-stats" alt="The box's output: no problems, then the lens's statistics: 6 blocks citing 20 of the chat's 24 blocks, 3.0 times shorter than what it cites"}
import afield.chatlog.*
import java.io.File

val assets = File("public/docs/assets/ai4_chat_archive")
val tree = ChatArchive.tree(ChatArchive.chats(File(assets, "chats/garden-shed")).single())
val lens = File(assets, "lenses/garden-shed/storyline.md").readText()
val problems = ChatLogViews.lensProblems(tree, lens).ifEmpty { listOf("none") }
val stats = ChatLogViews.lensStats(tree, lens).entries.joinToString("\n") { (k, v) -> "$k: $v" }
display("lens", "<pre style='margin:8px'>problems: ${problems.joinToString("; ")}\n$stats</pre>")
```

The chat has 24 blocks, and 4 of them are on the rewound branch, which this lens does not cite.

## Letting AI4 write lenses

Lenses are meant to be written by talking to AI4. Run this once in a box, and the document's AI4 agents can read the chats under the folders you name and save lenses:

```diffstatic {data-lang=kotlin}
import afield.chatlog.*
import java.io.File

registerChatlogTools(File("/path/to/a/workspace"))
```

The agents get eight tools:

| Tool | What it gives the agent |
| --- | --- |
| `chatlog.list` | every chat under those folders, with its turns, replies, tool calls, images and size in tokens |
| `chatlog.outline` | the outline above; agents are told to start here |
| `chatlog.read` | a stretch as text, by address, in pages |
| `chatlog.search` | blocks containing a phrase, optionally only one family of tool calls (`notes`, `design`, `builds`, ...) |
| `chatlog.images` | each tool picture: what the tool was looking for and what the agent concluded, and optionally the pictures themselves |
| `lens.write` | saves a lens; it refuses one with an address that doesn't exist and says which |
| `lens.read`, `lens.list` | the lenses saved so far |

The archive is only read. Lenses are saved in the registering document's `lenses/<chat>/` folder as `<name>.md`, beside a `<name>.json` record of the brief and the model that wrote it. Pass `lensDir =` to save them elsewhere. A chat is named after its document, or `<document>/<box name>` when the document has more than one chat.

Then ask, in that box's AI4 chat, for the lens you want, for example: "write a storyline lens over garden-shed: one block per request, keep the first picture". The brief is up to you. A lens can follow only the notes and recipe changes, only the failures and how they were fixed, or only the pictures.

## The lens viewer

`showChatLens(chat, lens)` shows a lens beside its chat in the box's `lens` output channel. The box's document needs to have run `registerChatlogTools` first, which this box does itself:

```diffstatic_runner {data-lang=kotlin screenshot="assets/ai4_chat_archive/viewer.png" capture="window" embed=true w="900" h="50" wait="4.0" timeout="120" name="chat-lens-viewer" clip="0,0,2210,2600" alt="The lens viewer: the storyline lens on the left, the garden-shed chat on the right with its tool calls folded into round lines, and curved bands between them joining each lens block to the stretch of the chat it summarizes"}
import afield.chatlog.*
import java.io.File

val assets = File("public/docs/assets/ai4_chat_archive")
registerChatlogTools(File(assets, "chats"), lensDir = File(assets, "lenses"))
showChatLens("garden-shed", "storyline", height = 600)
```

Both columns are drawn by the chat panel's renderer. Each lens block is labelled with the lens and its model. This lens was written by hand for the docs, so its model is `hand-written`. In the chat column, tool calls fold into round lines. A band that cites a block inside a closed round ends on the round's line.

- Hover over a block or a band to light up both ends.
- Click either end to bring the other end into view.
- With **follow** checked, the chat scrolls along as you scroll the lens.
- Blocks the lens doesn't cite are dimmer, so the lens also works as a filter.

The viewer fetches chats and pictures from Field over `/api/chatlog/lens` and `/api/chatlog/blob`. They answer only for documents that registered a chat archive, and only to the same machine, because chats can hold private work.

??? aside "How this page was made"
    The `garden-shed` chat and its storyline lens are fixtures written for this page, in the format a saved chat uses (`<box>_ai4_session_.jsonl`, `<box>_ai4_chat_history_.md`, `ai4_blobs/`). They are not a real AI4 conversation, and their pictures are simple drawings made by a script, not tool renders. Real chats can hold private work and never go on a public page. The lens record's statistics are the values `lensStats` printed in the capture above. Every box on the page runs against the real `ChatArchive`, `ChatLogViews` and `showChatLens`. The design and the measurements on long chats are in `dev_docs/ai/AI4_CHAT_PLAYGROUND_PLAN.md`.

The four boxes on this page are also a document, `public/docs/assets/ai4_chat_archive/demo`, which reads the same example chat when Field runs from a checkout.

See also: [AI Tool-Use History](ai_tool_use_history.md) for the per-call tool logs, and [Experimenting with AI4](ai4_experiments.md).
