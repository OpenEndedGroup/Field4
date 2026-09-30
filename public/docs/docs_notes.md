# Notes on the Documentation, Revised by Agents

When you read these pages inside Field, you can leave a note on any sentence. The notes form a
queue. An agent, or several running in parallel with one per page, works through that queue,
edits the Markdown behind each note, and records what it did. You can read what happened later,
answer any questions the agents asked, and run the queue again.

This page covers writing notes, what an agent sees when it picks one up, and how to run the
agents.

## Writing a note

Open the documentation pane in Field (`Cmd+/`), or go to `https://localhost:9092/field-docs/` on
the machine Field is running on. A **DOC NOTES** panel sits beside the page.

- Select some text and press `Ctrl+Shift+A`, type the note, and press `Enter`. With nothing
  selected, the note is pinned to the part of the page you are looking at.
- Put `@tags` in a note to group it: `@voice`, `@accuracy`, `@example`. You can run the agents
  on a single tag.
- Select the sentence itself, not the heading or the navigation around it. A note on the site's
  header or menu does not belong to any one page's text, and the panel records it as such.

Each note in the panel shows its state, where it now sits in the source (for example
`public/docs/sound.md:42`), and its thread: your replies, and the agents' questions,
resolutions and memories.

| state | meaning |
| --- | --- |
| **open** | waiting for an agent |
| **agent asks** | an agent needs a decision from you. Type an answer in the reply box and press `Enter`, and the note goes back in the queue |
| **resolved** | an agent (or you) dealt with it; the thread says how, and **reopen** puts it back |
| **memory** | something an agent wrote down for the next agent on this page. Shown when **show resolved notes and memories** is ticked |
| **handoff** | a larger task an agent filed instead of editing; see [Handoffs](#handoffs) |
| **waits on a handoff** | held until that handoff is closed, then back in the queue |

Notes are kept in `~/.afield/docs-annotations.json`, or in whatever file
`AFIELD_DOCS_ANNOTATIONS_STORE` names.

## Running the agents

Everything below runs from the root of a Field checkout.

```diffstatic {data-lang=bash}
:::static
# what is waiting, grouped the way the agents will divide it up
uv run python tools/docs_notes.py queue

# the plan, without running anything
uv run python tools/docs_notes.py revise --dry-run

# one agent per page, four at a time, then one commit per page
uv run python tools/docs_notes.py revise --jobs 4 --commit
```

`revise` takes a few options:

- `--agent claude` (the default) or `--agent codex`. `--agent-command` runs anything else, and
  replaces `{prompt_file}` in it with the path of a file holding the prompt.
- `--tag voice` or `--page sound.md` to run only part of the queue.
- `--commit` makes one commit per page once every agent has finished. Each commit has a
  `Docs-Note:` line for each note it resolves. Without `--commit` the edits stay in your working
  tree for you to review.

The **Improve annotated docs** button on the welcome page runs the same thing using Codex, and
never commits. It is off unless Field was started with `AFIELD_DOCS_IMPROVEMENT=1`, because it
starts coding agents with write access to your checkout.

A run keeps each agent's prompt, complete log and recorded outcomes in
`.field-backdoor/docs-notes/runs/<run>/`.

## What an agent is given

Each agent is given one page, or several pages when they would have to edit the same file,
which happens with reference pages generated from the same Kotlin file. The agent is told which files
it may edit, and it gets a brief. You can print the brief yourself:

```diffstatic {data-lang=bash}
:::static
uv run python tools/docs_notes.py brief public/docs/sound.md
```

For each open note, the brief has the note, the quoted text, the section it is in, the current
lines of source with their line numbers, and the thread so far. Then it lists the memories left
on the page and the most recent resolutions there, so an agent does not undo what the previous
one did.

The agent must record an outcome for every note it is given: **resolve** it with a sentence
saying what changed, **question** it when the note needs a decision from you, **hand it off**
when it is bigger than the page (see [Handoffs](#handoffs)), or leave it open.
It can also leave a **memory**, either on a note or on the page as a whole. A memory is for
whatever the next agent would otherwise have to work out again: a constraint, a trap, the reason
a sentence is worded the way it is.

Generated pages carry a `DO NOT EDIT` header, and the reference section is one of them. A note
on one of those is traced back to the KDoc it was generated from, and the agent edits the Kotlin.
At the end, the run prints the command that regenerates those pages.

## Handoffs

Some notes ask for more than a page agent should do on its own. **Merge this page into that
one**, **this example needs a new function in the library**, **the figure is wrong because the
renderer is**. For those, the agent files a *handoff* instead of an edit. A handoff is a document
in `dev_docs/handoffs/` that states what was asked, why it is bigger than a docs edit, the
evidence with file and line references, a proposed approach, and the questions only you can
answer. The notes it came from are listed at the top, with their quotes.

A handoff has one of two scopes:

- **project**: real engineering, such as a code change, a new tool, a bug in Field, or a capture
  that cannot come out until the runtime is fixed.
- **crosscutting**: anything that reaches past one page's files, such as merging, splitting,
  moving or renaming pages, reorganising the navigation, or a change of terms across several
  pages.

A handoff can also **block**. That means the rest of the page should not be polished until the
handoff is dealt with, for example because the page is about to be merged away. The agent stops
as soon as it has filed a blocking handoff. Every open note on the page then waits, including
notes written later, and `revise` skips the page. A handoff that does not block holds only the
notes it came from, and the agent carries on with the rest.

Handoffs are meant to be worked through separately, in an interactive session where the
questions can be answered as they come up:

```diffstatic {data-lang=bash}
:::static
uv run python tools/docs_notes.py handoffs                       # blocking ones first
uv run python tools/docs_notes.py handoff-close 7f3a91c2 "merged into sound.md in 1234abc"
uv run python tools/docs_notes.py handoff-close 7f3a91c2 --state dropped "keeping both pages"
```

Closing a handoff releases the notes it held. The next `revise` picks them up with the closing
sentence in the page's brief, and so brings the page in line with whatever was decided.
`dev_docs/handoffs/README.md` covers the interactive side.

## How a note keeps its place

A note is written against the page as it was rendered, but agents edit Markdown, often long
after the note was written and after other edits to the same file. Two things keep a note on
the right sentence.

**Finding it.** Every documentation build records the commit it was built from, and the panel
stores that commit with each note. `docs_notes.py sync` looks for the quoted text in that
version of the page's Markdown, which is the text you actually read. Link syntax, emphasis and
code markup do not get in the way. If the quote appears more than once, the text around your
selection and the heading above it decide which one you meant. A quote of one short word whose
surroundings match nothing in the source is not placed at all.

**Following it.** Once found, a note is tied to its lines by what `git blame` says about them:
the commit and line each one came from. A line keeps that identity however the lines around it
are edited, moved or added to, so later syncs find it again by identity rather than by searching
for the text. That matters when a sentence appears twice: the note stays on the copy you
annotated. It also follows a paragraph moved within its file, and a file that has been renamed.

`sync` reports how each note fared:

| status | meaning |
| --- | --- |
| `anchored`, `exact`, `moved` | the note's lines are intact, possibly at new line numbers or in a renamed file |
| `edited` | some of its lines were changed; it covers the ones that were not |
| `reanchored` | all of its lines were rewritten, but the quote can still be found in the new text; the old text is kept for the agent |
| `orphaned` | the lines and the quote are both gone; where it last was is kept |
| `unanchored` | never placed. Usually the quote came from a figure or output that the documentation build captured, which is not in the Markdown, or from the site's header |
| `uncommitted` | found only in edits that have not been committed yet; the next sync after a commit places it properly |

`revise` syncs before it starts and again at the end, so resolved notes follow the rewritten
text too. Run `sync` on its own after editing pages by hand. If a note has been placed on the
wrong lines, `sync --reanchor <id>` discards its history and places it again from the quote.

## Answering from the command line

The panel's controls are also commands, for when you would rather not open a browser:

```diffstatic {data-lang=bash}
:::static
uv run python tools/docs_notes.py show 4159b698          # a note in full (an id prefix is enough)
uv run python tools/docs_notes.py reply 4159b698 "keep Field·4 in prose"
uv run python tools/docs_notes.py resolve 4159b698 "left as is"
uv run python tools/docs_notes.py reopen 4159b698
uv run python tools/docs_notes.py memory --page sound.md "the second example is pinned by a test"
```

## Limits

- **Nothing is verified.** A run neither builds the documentation nor recaptures its figures.
  Build the pages it touched before trusting them:
  `tools/build_zensical_docs.py --page <page>`.
- **Agents share your checkout.** Each agent is told which files it may edit. After a run, any
  change to a file no agent was given is listed, and it is never committed.
- **Tables and HTML-heavy passages** are matched approximately, and sometimes not at all. Check
  the `sync` output for `unanchored` notes before a long run.
- **Each agent is limited to a set of tools.** Claude may read and edit files, run the notes
  command, and run read-only `git` and `grep`. Codex runs in its `workspace-write` sandbox.

??? aside "Where the parts live"

    The panel is `src/commonMain/resources/docsAnnotations.js`, the store and its routes are
    `src/jvmMain/kotlin/afield/server/docsAnnotations.kt`, and everything on this page that
    runs from a terminal is `tools/docs_notes.py`, with its tests in `tools/test_docs_notes.py`.
    The server and the tool both rewrite the same JSON file, so any field the tool writes must
    also be declared on the Kotlin side or the server drops it; `KotlinSchemaTest` checks that.
    The security posture of the welcome-page button is `dev_docs/DOCS_IMPROVEMENT_AGENT.md`.
