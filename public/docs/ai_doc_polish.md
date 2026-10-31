# AI documentation polish

When you encounter a Kotlin declaration without KDoc, Field can ask a fast model for a short
description and show it in the completion popup. Field keeps these drafts in a cache, where they
can be reviewed and turned into KDoc in the source. Once there, the descriptions also reach the
reference pages. The work happens in the same [documentation revision pass](docs_notes.md) that
handles your notes on the docs.

## Polishing the drafts

The **KDoc drafts** card on [`/welcome`](history_overview.md) counts the drafts for
declarations under `src/`, and how many files they are spread over:

```diffstatic_runner {data-lang=kotlin capture="ui" region="window" clip-selector="#docs-kdoc-drafts-card-crop" screenshot="assets/ai_doc_polish/kdoc_drafts_card.png" padding="0" wait="0" timeout="120" embed=true hidden=true name="kdoc-drafts-card" alt="The KDoc drafts card from the AI panel on the Welcome page: the number of drafts waiting and the files they are in, with a Review in docs pass button."}
// /welcome is its own page: load it into a frame, open the AI panel the way a click on its summary
// does, and mark the KDoc drafts card.
ui.eval(
    """
    document.getElementById("docs-welcome-frame")?.remove()
    const f = document.createElement("iframe")
    f.id = "docs-welcome-frame"
    f.src = "/welcome"
    f.setAttribute("style", "position:fixed;left:0;top:0;width:1700px;height:2000px;border:0;background:#111;z-index:2147483000")
    document.body.appendChild(f)
    return true
    """
        .trimIndent(),
)
var state = ""
for (i in 0 until 80) {
    ui.pause(250)
    state =
        ui.eval(
                """
                const d = document.getElementById("docs-welcome-frame")?.contentDocument
                const panel = d && d.getElementById("ai-settings-details")
                if (!panel) return "loading"
                if (!panel.open) panel.querySelector("summary").click()
                const s = d.getElementById("completion-doc-polish-state")?.textContent || "checking"
                return /checking/i.test(s) ? "pending" : "ready"
                """
                    .trimIndent(),
            )
            .toString()
    if (state.contains("ready")) break
}
check(state.contains("ready")) { "the welcome AI panel did not finish loading: " + state }
ui.pause(1000)
ui.eval(
    """
    const f = document.getElementById("docs-welcome-frame")
    const card = f.contentDocument.getElementById("completion-doc-polish-state").closest(".ai-docs-panel")
    card.scrollIntoView({ block: "start" })
    const r = card.getBoundingClientRect()
    const o = f.getBoundingClientRect()
    document.getElementById("docs-kdoc-drafts-card-crop")?.remove()
    const m = document.createElement("div")
    m.id = "docs-kdoc-drafts-card-crop"
    m.setAttribute("style", "position:fixed;pointer-events:none;background:transparent;border:0;z-index:2147483001;" +
        "left:" + (o.left + r.left - 8) + "px;top:" + (o.top + r.top - 4) + "px;width:" + (r.width + 16) + "px;height:" + (r.height + 12) + "px")
    document.body.appendChild(m)
    return [r.width, r.height]
    """
        .trimIndent(),
)
ui.pause(300)
```

**Review in docs pass** opens `/docs-pass.html`. There, **Import completion KDoc drafts** adds
each draft as a note on its declaration's line, with the tag `@kdoc-draft`. Once the import
finishes, the queue counts the drafts among its open notes:

```diffstatic_runner {data-lang=kotlin capture="ui" region="window" clip-selector="#docs-pass-drafts-crop" screenshot="assets/ai_doc_polish/docs_pass_imported.png" padding="0" wait="0" timeout="240" embed=true hidden=true name="docs-pass-imported-drafts" alt="Step 2 of the documentation revision pass page, Drain the queue, after Import completion KDoc drafts: the chips count the open notes and how many of them are KDoc drafts, above the agent, tag and page filters and the drain, stop and import buttons."}
// Load /docs-pass.html into a frame, press Import completion KDoc drafts, and wait until the
// queue's counts include the imported drafts. Then mark step 2.
ui.eval(
    """
    document.getElementById("docs-pass-frame")?.remove()
    const f = document.createElement("iframe")
    f.id = "docs-pass-frame"
    f.src = "/docs-pass.html"
    f.setAttribute("style", "position:fixed;left:0;top:0;width:900px;height:1400px;border:0;background:#111;z-index:2147483000")
    document.body.appendChild(f)
    return true
    """
        .trimIndent(),
)
var state = ""
for (i in 0 until 240) {
    ui.pause(250)
    state =
        ui.eval(
                """
                const d = document.getElementById("docs-pass-frame")?.contentDocument
                if (!d || !d.getElementById("posture")) return "loading"
                if (/Loading/.test(d.getElementById("posture").textContent)) return "posture"
                if (!d.getElementById("counts")?.children.length) return "counts"
                return "ready"
                """
                    .trimIndent(),
            )
            .toString()
    if (state.contains("ready")) break
}
check(state.contains("ready")) { "the revision pass page did not finish loading: " + state }
ui.eval(
    """
    document.getElementById("docs-pass-frame").contentDocument.getElementById("import-drafts").click()
    return true
    """
        .trimIndent(),
)
for (i in 0 until 600) {
    ui.pause(250)
    state =
        ui.eval(
                """
                const d = document.getElementById("docs-pass-frame").contentDocument
                const running = /running/.test(d.getElementById("job-panel").className)
                const drafts = /KDoc drafts/.test(d.getElementById("counts").textContent)
                return !running && drafts ? "imported" : "importing"
                """
                    .trimIndent(),
            )
            .toString()
    if (state.contains("imported")) break
}
check(state.contains("imported")) { "the drafts did not appear in the queue's counts" }
ui.pause(800)
ui.eval(
    """
    const f = document.getElementById("docs-pass-frame")
    const s = f.contentDocument.getElementById("counts").closest("section").getBoundingClientRect()
    const o = f.getBoundingClientRect()
    // Down to the buttons: the help paragraph under them runs wider than the frame.
    const b = f.contentDocument.getElementById("import-drafts").getBoundingClientRect()
    const r = { left: s.left, top: s.top, width: Math.min(s.width, f.clientWidth - s.left), height: b.bottom + 10 - s.top }
    document.getElementById("docs-pass-drafts-crop")?.remove()
    const m = document.createElement("div")
    m.id = "docs-pass-drafts-crop"
    m.setAttribute("style", "position:fixed;pointer-events:none;background:transparent;border:0;z-index:2147483001;" +
        "left:" + (o.left + r.left) + "px;top:" + (o.top + r.top) + "px;width:" + r.width + "px;height:" + r.height + "px")
    document.body.appendChild(m)
    return [r.width, r.height]
    """
        .trimIndent(),
)
ui.pause(300)
```

The terminal equivalent of the import is:

```diffstatic {data-lang=bash}
:::static
uv run python tools/docs_notes.py import-drafts
```

From there, a draft is handled like any other note. **Drain the queue** runs one agent per Kotlin
file; enter `@kdoc-draft` in the tags box to work only on drafts. The agent receives the draft,
the declaration, the writing rules and relevant notes, including any note you left on the same
KDoc. Your note takes precedence.

The agent checks the draft against the declaration and its callers, then writes or corrects the
KDoc. If the declaration needs none, it resolves the draft without adding text. If it cannot work
out what the code does, it asks under *Questions for you*. You can then review the diff and finish
the pass.

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
