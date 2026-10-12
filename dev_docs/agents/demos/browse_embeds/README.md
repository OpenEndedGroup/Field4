# tree(), column() and logs() — a document you can open

Replay `dev_docs/agents/transcripts/browse_embeds.yaml` into a fresh Field, then run boxes 1, 2 and 4 and **launch** box 3 (it loops):

```bash
uv run --project . --group backdoor python tools/field_backdoor.py --json --scratch --with-playwright --port 9477 \
  --command 'macro write browse_embeds --file dev_docs/agents/transcripts/browse_embeds.yaml' \
  --command 'macro run browse_embeds' \
  --command 'run tree_box' --command 'run column_box' --command 'launch live_box' --command 'run logs_box'
```

| box | what to notice |
| --- | --- |
| 1 — `tree()` | every row shows a type and a summary; `many` opens 200 rows at a time, then "show more — 200 of 5000"; `byKey` keeps `1` and `"1"` apart and opens the object key to key/value; `ring → next → next` is marked `↺` |
| 2 — `column()` | Finder columns with the same rows; `/` filters the active column, `c` copies |
| 3 — live | open `readings`: its size and rows grow every half second with no re-run |
| 4 — `logs()` | only the `demo` channel; the block id `job (1)` (parentheses) groups correctly; type in find |

Notes on the model are in [`dev_docs/INSPECTOR_EMBEDS.md`](../../../INSPECTOR_EMBEDS.md).
