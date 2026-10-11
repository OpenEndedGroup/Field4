# AI documentation polish

When completion shows a Kotlin declaration that has no KDoc, Field asks a fast model for a short
description and shows that in the completion popup. These drafts are kept in a cache. Polishing
turns them into real KDoc in the source, so they reach the reference pages and stop depending on
a model. The work happens in the same [documentation revision pass](docs_notes.md) that handles
your notes on the docs.

## Polishing the drafts

The **KDoc drafts** card on [`/welcome`](history_overview.md) counts the drafts for
declarations under `src/`. **Review in docs pass** opens `/docs-pass.html`. There, **Import
completion KDoc drafts** adds each draft as a note on its declaration's line, with the tag
`@kdoc-draft`. The terminal equivalent is:

```diffstatic {data-lang=bash}
:::static
uv run python tools/docs_notes.py import-drafts
```

From there a draft is worked like any other note. **Drain the queue** runs one agent per Kotlin
file in the pass's worktree. Put `@kdoc-draft` in the tags box to drain only the drafts. The agent
gets the draft, the declaration, the writing rules (`dev_docs/agents/DOCS_WRITING_RULES.md`),
the memories earlier agents left on that file, and any note of yours on the same KDoc, which
takes precedence over the draft.

The agent treats a draft as a suggestion. It reads the declaration and its callers, keeps the
parts that hold up, corrects the rest, and writes the KDoc. When a declaration needs no KDoc, it
resolves the draft without writing any. Completion then stops showing that draft. When it cannot
settle what the code does, it asks you a question, which appears under *Questions for you*. You
review the diff, rebuild, commit and land as in any pass.

Only declarations in this repository are drafted, in any source set under `src/`, including
`src/personal`. A declaration from a dependency's sources jar is never sent to the model and never
imported.

## The draft cache

The cache is `<checkout>/.field/filestore/generated_completion_docs.json`: one per checkout,
seeded on first use from the machine-global `~/.afield/filestore`, and relocated by
`AFIELD_FILESTORE_DIR` or `-Dafield.filestore.dir`. Each record carries the declaration's name,
path, line and source hash alongside the draft, the model that wrote it, and when.

A record's key hashes the prompt version together with the declaration name, source path, line
and source hash, so a record survives only while all of those still match. Editing the
declaration retires its record, and bumping `COMPLETION_DOC_PROMPT_VERSION` retires every record
at once. Drafts the pass declined are listed by key in
`generated_completion_docs.declined.json` beside the cache.

??? aside "Design notes"
    Why polishing runs inside the revision pass, rather than as a task of its own, is in
    `dev_docs/agents/COMPLETION_DOC_POLISH_DESIGN.md`.
