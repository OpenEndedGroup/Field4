# Read-only mode, and what it looks like from the inside

Two boxes, no GPU, no network, no setup. The point of this document is to be opened **twice**.

## Open it read-only

```bash
./field.sh dev_docs/agents/demos --read-only -d read_only_session
```

or, for one session inside an otherwise normal Field:

```bash
python3 tools/field_backdoor.py --port <p> --workspace dev_docs/agents/demos \
    --document read_only_session --read-only --with-playwright
```

Read-only is also `AFIELD_READ_ONLY=1` or `-Dafield.readOnly=true` process-wide.

## What to look for

1. **An amber pill at the top of the window** — `READ-ONLY`, and in plain words that nothing you
   change here will be saved. It is there from the moment the document attaches, before you have
   done anything to lose.
2. **Type in a box.** The pill turns into a running count: *"3 changes so far will be discarded."*
   Moving the cursor around does **not** count — Monaco's view state is not part of the document,
   and counting it made the number about four times too big.
3. **Try to close or reload the window.** The browser asks you to confirm. Field has no explicit
   save, so without this dialog a read-only session's only feedback would be the work being gone.
4. **Run the `saved{} counter` box.** It prints a number that climbs while the session lives and
   then forgets, because the write is refused. `~/.afield/logs/save_reload.log` gets a
   `write-refused-read-only` line naming the property, so "why didn't my value persist" has an
   answer.
5. **Drop a file onto the canvas.** Refused with a reason, rather than silently copying the file
   into a document that is never going to be written.

## Then open it normally

```bash
./field.sh dev_docs/agents/demos -d read_only_session
```

No pill. Run the counter box a few times, quit, reopen: the count carries over, because
`saved {}` really is per-box persistence — `<document dir>/<box id>/runs.json`. That contrast is the
whole demo. The read-only session is not a broken session; it is the same session with the writes
switched off, and now it says so.

## What this document is committed without

`runs.json` and its `backups/` are deliberately absent, so a fresh clone starts the counter at 0.
So are `operations.yaml`, `transcript.edn` and `history_metrics.edn` — all derived, all `.gitignore`d,
and `DocumentWriteBackLedgerTest.noNewDerivedArtefactIsCommitted` fails if one of them ever lands
here. Opening this document read-only writes none of them, which is the other half of the point.

Background, the three writers that used to ignore all of this, and how each refusal was measured:
`dev_docs/document_write_back.md`.
