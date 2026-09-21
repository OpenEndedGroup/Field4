# MARF field clocks — a refusal you can watch fire

Three pure-CPU boxes on `MarfFieldClock`. No `Window`, no `Renderer`, no audio device, no external
asset. The point is the pair: one box whose patterns are **refused at construction** with the new
message printed in its own output, and one whose patterns are **accepted and drawn**.

## Replay it

```bash
uv run --project . --group backdoor python tools/field_backdoor.py --json --scratch --with-playwright --port 9101 \
  --command 'macro write marf_field_clocks --file dev_docs/agents/artifacts/marf_field_clocks/marf_field_clocks.yaml' \
  --command 'macro run marf_field_clocks'
```

Drop `--with-playwright` if you only want to read the output events; the boxes run either way, but
the backdoor is headless and `screenshot-box` returns a 48×48 placeholder with `ok: true` when no
client is attached, because `displayOpen(...)` sends an output-control message to a client and with
nobody there it has nowhere to land. Or open a real Field and paste the three box bodies in.

## What each box shows

| box | what it does |
| --- | --- |
| `marf_clocks_refused` | Five address spellings under `STAGES`. Four are refused; the fifth reads. Then the same five under `CYCLES`, which is exempt — and that second list is the *old* behaviour, so you can read the silence next to the refusal. |
| `marf_clocks_steps` | Four lanes of line art over one bank, twelve cycles. Lane 1 is the one spelling that was always read right; lanes 2 and 3 are spellings that used to collapse onto it; lane 4 is lane 2's address against `duration = "<1 2>"`, so its boundaries fall elsewhere while its addresses match lane 2 exactly. |
| `marf_clocks_ledger` | All ten spellings × all three clocks as one table, with the counts computed live rather than typed. |

![the refusal firing, and the same five spellings read silently under CYCLES](marf-clocks-refused.png)

![four lanes: STAGES, two STEPS readings, and a STEPS reader with its own duration grid](marf-clocks-steps.png)

![the ten spellings against CYCLES, STAGES and STEPS](marf-clocks-ledger.png)

Those three PNGs were captured from this transcript on 2026-09-21 at 05:56 with
`--with-playwright` on port 9101 — 576×2348, 2176×1286 and 676×914. Every box was executed
individually (backdoor `run <box>`) as well as through the macro, and all three returned
`kind: ok` with no error events.

## Where the material comes from

Not invented. `dev_docs/agents/demos/marf_demo/operations.yaml` records thirty-eight edit-and-run
cycles on one box between 03:38:58 and 03:42:10 UTC on 2026-09-21, about five seconds apart, working
through these ten address spellings. Under `STAGES` as it then was, eight of the ten produced a
sequence byte-identical to something strictly simpler than what had been typed — `"1 <0 3>"` was the
constant `1`, `"<0 3>*2"` was the constant `0` — and nothing raised, warned or logged. The `CYCLES`
column of the last box is that behaviour, still there, still correct for that reading.

## Why this is not in `dev_docs/agents/transcripts/`

**A deliberate deferral, not an oversight.** Registering a transcript means three set-equality or
count edits — a name in `DemoTranscriptExecSmokeTest`'s `EXECUTE`/`COMPILE_ONLY`/`KOTLINJS`
partition, an entry plus totals on `public/docs/agent_transcripts.md`, and possibly a materialized
starting-point bundle. On the night this was written a sibling branch was adding `marf_demo.yaml`
and therefore incrementing exactly those same three, and two branches each going `N → N+1` on a set
equality is a merge that conflicts textually or, worse, merges cleanly into a wrong total. The
2026-09-19 Z5 precedent is to land the scene first and promote it in its own change.

**To promote it**, move the yaml to `dev_docs/agents/transcripts/marf_field_clocks.yaml` and follow
the five steps in `dev_docs/agent_transcript_index.md`. It belongs in `EXECUTE`: every box is
pure-CPU, self-contained, and finishes on its own.
