# Completion doc polish and the docs revision pass

Two tools have agents write documentation. Completion doc polish moves model-drafted KDoc into
the source. The docs revision pass applies the human's notes to the published docs. This page
compares them and recommends one shape.

## What each one is

| | Completion doc polish | Docs revision pass |
| --- | --- | --- |
| Entry points | `completion-doc-polish.html`, `CompletionDocPolishMonitor`, `./gradlew polishCompletionDocs` → `Ai4CompletionDocPolishCli` | `docs-pass.html`, `afield/server/docsPass.kt`, `tools/docs_notes.py pass …` / `revise` |
| What starts the work | A fast model drafts a doc for an undocumented declaration when it appears in completion (`Ai4CompletionDoc.resolveDoc`); the drafts collect in `.field/filestore/generated_completion_docs.json` | A human note on a rendered page, anchored to its Markdown **or KDoc** lines by `git blame` origin |
| Agent | One flagship-model thread (OpenAI bridge), with the whole cache and a manifest of `src/` | One agent per page or Kotlin file (`claude`/`codex`), in parallel, each given only the files it owns |
| Rules it follows | A dozen lines of prompt; since this change, also `DOCS_WRITING_RULES.md` | `AGENTS.md` §8, `DOCS_WRITING_RULES.md`, the page's memories, what was recently resolved there |
| Where it writes | Directly into the checkout's `src/`, uncommitted | A pass worktree on its own branch |
| Review | The Edits panel (now read from `[completion-doc-polish-edit]` lines); the human finds the diff with `git status` | The diff per page on `docs-pass.html`, a docs rebuild, then commit |
| Landing | None. The edits sit uncommitted in whatever checkout Field runs from | `tools/land.py`, like everything else |
| Outcome record | Removes cache entries it integrated; a summary in the log | `resolve` / `question` / `memory` / `handoff` per note, applied by the harness from a journal |
| Human in the loop | Only by reading the diff afterwards | Questions for the human, notes that reopen, memories |

## The gaps that matter

1. **No feedback reaches polish.** A note the human writes on a reference page lands on the KDoc
   lines polish wrote, and only the revision pass sees it. Polish has no memories, so the next run
   can repeat what the note objected to.
2. **Polish writes into a live checkout with no review or landing.** Today's run left five
   modified files in the primary checkout (one under `src/personal`) for someone to commit by
   hand. It runs alongside other sessions and the lander, with nothing to keep it out of their
   way.
3. **Two harnesses for one job.** Both drive an agent through some Kotlin, constrained to KDoc,
   held to the same rules. Only one has worktrees, partitioning, journals and landing.

## Recommendation (taken): polish becomes a source of notes for the revision pass

Keep the fast drafter as it is. It runs on demand inside completion and is cheap, and as of this
change it skips declarations outside `src/`. Replace the polish *agent run* with this:

- **Drafts become notes.** `docs_notes.py sync` (or a `import-drafts` step it calls) turns each
  in-tree cache record into a note with `kind: "kdoc-draft"`, anchored to the declaration's line
  in its Kotlin file. The anchoring is the same blame-origin anchoring a KDoc note gets now. The
  draft text is the note body. Author: `model:<name>`.
- **One queue.** `queue` / `brief` / `revise` already partition by owned Kotlin file. A Kotlin
  file can carry human notes and drafts together, and one agent handles both. Its brief carries
  the rules, the file's memories, and what was resolved there.
- **The same outcomes.** The agent resolves a draft when it has integrated it, and the harness
  then drops the cache record. It can `question` one whose behaviour it cannot confirm from
  source, or leave a `memory` (for example, "`laneGap` was described wrongly by the drafter").
  A memory left for a Kotlin file reaches the next run on that file through `page_memories`.
- **The same pass.** It runs in a pass worktree, shows its diff on `docs-pass.html`, rebuilds the
  reference pages it touches, and lands through `tools/land.py`.
- **Retire** `polishCompletionDocs`, `Ai4CompletionDocPolishCli`, the monitor and its page. The
  page can become a filter on `docs-pass.html` ("drafts only"), or a link to it.

What this costs: drafts move from the OpenAI bridge to the `claude`/`codex` agents the pass
already runs, and a draft note needs a declaration line where a human note has a quote. Both are
small. The store schema needs the new `kind`, declared on `DocsAnnotation` too
(`KotlinSchemaTest` enforces this).

The alternative is to bolt the pieces onto polish: feed it notes and memories, give it a worktree
and a land step. That rebuilds `docs_notes.py` inside a Gradle task, and leaves two harnesses to
drift apart.

## What was built (2026-10-04, chosen by Marc)

- `tools/docs_notes.py import-drafts` (and **Import completion KDoc drafts** on `docs-pass.html`)
  makes a `kdoc-draft` note for every cached draft whose declaration is under `src/`, still
  exists, and has no KDoc yet. The note id is derived from the cache key, so importing again adds
  nothing. `pageUrl` is the file's `reference/source/…` page, which `resolve_page_source` already
  maps back to the Kotlin. The draft's identity (key, symbol, model, cache path) is kept in
  `source.draft`, the free-form part of the note that the Kotlin store preserves.
- `sync_draft` follows a draft by blame, like any note. When blame loses it, it finds the
  declaration again by name.
- `REQUEST_KINDS` (Python) and `queuedDocsAnnotationKinds` (Kotlin) make a draft count as queued
  work, everywhere a human note does.
- The agent prompt has a "KDoc drafts" paragraph, and `render_note` labels a draft as not the
  human's words.
- After each partition, a draft resolved while its declaration still has no KDoc counts as
  declined. Its key goes into `generated_completion_docs.declined.json` beside the cache, and
  `GeneratedCompletionDocCache.isDeclined` keeps completion from showing or regenerating it.
  Python never writes the cache itself, because the server holds it in memory and rewrites it
  whole.
- Retired: `polishCompletionDocs` / `polishCompletionDocsDryRun`, `Ai4CompletionDocPolishCli`,
  `CompletionDocPolishMonitor` and its run store, `/completion-doc-polish.html`,
  `/api/completion-doc-polish`. The welcome card (`CompletionDocDrafts.summary()`) counts the
  drafts and opens `docs-pass.html`.
- Kept from the first step: declarations outside `src/` are never drafted
  (`Ai4CompletionDoc.isInOurTree`, checked before any model call).

Not done: `public/docs/ai_doc_polish.md` has no capture of the import button yet.
