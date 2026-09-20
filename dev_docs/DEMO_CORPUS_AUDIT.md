# Auditing the demo corpus by opening it

Written 19 September 2026, after promoting the nineteenth demo document and then opening all
nineteen. The point of the exercise was not the promotion — that is mechanical — but the finding
that keeps recurring here: **opening an artifact finds what reading it does not.** Sixteen documents
were promoted at speed on the evening of the 18th and nobody had run them. Running all nineteen cost
128 s of machine time and one night of reading, and corrected five published sentences.

This page is the method and the numbers, so the next person can recompute rather than trust.

## The population, and how to recount it

Every number below has a command. None of them is copied from prose.

| what | number | how |
| --- | --- | --- |
| demo documents | 19 | `ls -1d dev_docs/agents/demos/*/ \| wc -l` |
| boxes across them | 65 | count `create` ops in the 19 transcripts |
| transcripts in the corpus | 136 | `ls -1 dev_docs/agents/transcripts/*.yaml \| wc -l` (top level only; the 8 under `docs_capture/` are a separate population) |
| replayed end to end | 79 | `DemoTranscriptExecSmokeTest.EXECUTE.size` |
| built and compiled | 50 | `COMPILE_ONLY.size` |
| compiled to JavaScript | 7 | `KOTLINJS.size` |
| demos shipping a `build.yaml` | 9 | `ls dev_docs/agents/demos/*/build.yaml \| wc -l` |

The three set sizes are also printed by the harness itself on every run — the last line is
`DemoTranscriptExecSmokeTest ok — N demos verified (79 executed, 50 compiled, 7 kotlinjs)` — and
79 + 50 + 7 = 136 is the arithmetic the gallery's totals paragraph states. **Record the definition,
not only the number:** two branches can each add a transcript on the same night and git will merge
the registries silently and wrongly, leaving a totals paragraph that is off by one with no conflict
to look at.

## What the audit actually did

**One filtered replay for all nineteen.** `DEMO_TRANSCRIPT_ONLY=<comma list>` with
`AFIELD_DEMO_TRANSCRIPT_VERBOSE=1`, through `tools/run_field_tests.py --id
examples.demo-transcript.exec`. 128 s of wall clock, and the captured stdout lands in
`build/field-tests/results.json` under `results[0].backdoorResult.data.stdout` — not under
`results[0].stdout`, which is the runner's own transcript of the JSON reply and will not parse the
way you expect.

**A backdoor pass for the boxes that draw rather than print.** The verbose replay reports a display
output as `ok: ok` and nothing more, so `logging_layer`, `midi_clip` and `pattern_condense` came back
looking like six, four and three boxes that said nothing at all. They are the ones with the most to
say. `tools/field_backdoor.py --json --scratch --port <yours> --command 'macro write …' --command
'macro run …' --command 'output <box> --kind all'` returns the rendered payload, and stripping the
tags out of it gives you the table the box drew.

**`--with-playwright` for anything that opens a channel.** `python_cells`' `fig.show()` emits *zero*
output events headless and one `output-control` on the `browser` channel with a browser attached.
Without that second run the honest report would have been "the last cell does nothing", which is
false.

**A real screenshot for the one visual claim that had to be checked.** `screenshot-box` with
`--with-playwright` produced a 3128 x 1892 PNG of `confluence_grid`'s Three.js panel, and cropping
the four cells of one row side by side shows them as the same constellation. A `--port`-managed
server *without* `--with-playwright` returns `ok: true` and a 48 x 48 placeholder, which is the trap.

## Traps worth knowing

- **`wait-output --kind` takes `inline`, `display` or `all`.** Not `normal`, which is what the
  verbose replay calls the kind, and the mismatch fails the whole backdoor invocation.
- **Box attribution in the verbose replay is not recoverable.** The engine creates every box's
  script engine before any output is dispatched, so segmenting the log on
  `create KotlinScriptEngine … filePrefix=<box>` attributes all of a demo's output to its last box.
  Read at demo granularity, or use the backdoor's per-box `output`.
- **A `--port`-managed backdoor server shuts down when its commands finish.** You cannot `lsof` it
  afterwards to prove whose checkout it was. Prove it from
  `.field-backdoor/logs/*.managed-server.log`, which prints the classpath — the first entries are
  your worktree's `build/classes/kotlin/jvm/main`.
- **`RECORDED_OCCURRENCES` in `tools/check_doc_offline_links.py` is a floor, and its test asserted
  equality.** That suite had been red in `check` since the sixteen-demo sweep took the tree from 137
  `offline.html?file=` references to 156. A guard that fails for the one reason its own subject line
  says is fine is worse than no guard: it trains people to ignore the runner.

## The structural finding

Nine of the nineteen demos ship a `build.yaml` beside the saved document, and each script's own
description says some version of *"this is what built this document; re-running it rebuilds it."*
Nothing checked that. Seven of the nine are still true. Two are not:

| demo | boxes in the script | boxes that still match the document |
| --- | --- | --- |
| `frame_history_reprojection` | 4 | 2 — `rp_bundle` is 40 lines against 52, `rp_chain` 42 against 108 |
| `logging_layer` | 6 | 3 — `log_channels` 23 against 27 (the older code has lost an import and does not compile), `log_mute_is_console_only` 26 against 27, `log_blocks` 47 against 48 |

Five drifted boxes out of 31 coded boxes across the nine scripts. Both demos were found by hand
during the promotion sweep; this reproduces that reading as `tools/test_demo_build_scripts.py`, which
compares each `create` op's `code` against the committed `<box>_code_.kt` and lists the five in
`KNOWN_STALE` **box by box**, so a new drift in `logging_layer` still fails and a repaired box also
fails until its exception is removed. It costs a file read; the replay-and-compare version costs a
JVM and is `checkAgentTranscripts`' job.

What it does not check: frames, names, graph topology or the property set. Only the Kotlin — which is
where every drift found so far has been, and the part that stops compiling.

## What the audit corrected, and what it could not reach

Nineteen of nineteen were opened. Fourteen ran end to end; five are `COMPILE_ONLY` because they open
a GLFW window or need an interpreter the build cannot assume, and of those five only `python_cells`
could be driven by hand here — the four path-tracing and reprojection scenes need a GPU window and
were **not** run. Say that rather than letting a table of nineteen green rows imply it.

Across the 65 boxes: 46 executed, 15 compiled, 4 neither (the Python cells, skipped by name because
they are not Kotlin — before `ad06001ac` they were fed to K2, which reported every word as an
unresolved reference, 150 diagnostics for one document).

Five published sentences were corrected, all in `public/docs/agent_transcripts.md`, and all of the
same kind: a claim that was true of the demo's *intent* and not of its behaviour. The full list is in
the commit that made them. The pattern worth carrying forward is that four of the five were about the
**last** box of a scene — the one a writer describes from the plan and a reader never reaches.

## Postscript, 20 September 2026: the twentieth arrived unverified

Every number above is as measured on the 19th and is left alone. What happened next is the reason
the page exists.

`skinned_characters` landed from a sibling branch on the same day this audit was written, taking the
corpus from nineteen documents to twenty. It has no `dev_docs/agents/transcripts/skinned_characters.yaml`
and no entry in any of `DemoTranscriptExecSmokeTest`'s three registries, so six Kotlin box bodies
are compiled by nothing. The only thing in the tree that reacted was the corpus-count equality in
`tools/test_demo_build_scripts.py`, which reports that a demo arrived and nothing about whether it
is checked.

`src/jvmTest/kotlin/afield/docs/AgentDemoRegistrationTest.kt` now asserts the missing half —
every demo document maps to a registered transcript or sits in a named `UNVERIFIED` list with its
reason and its fix — and `skinned_characters` is the one entry on that list. Writing the transcript
is the fix; it belongs in `COMPILE_ONLY`, because its boxes open a Window and a Renderer.

Both corpus assertions are now named sets rather than counts, so the next arrival is reported by
name. See `dev_docs/GUARD_CORPORA.md` for why, and for what each guard in the tree actually looks at.
