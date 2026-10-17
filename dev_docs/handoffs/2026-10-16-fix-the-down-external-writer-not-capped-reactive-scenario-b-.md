# Fix the down_external_writer_not_capped reactive scenario (B never reruns)

- **Handoff:** `0789be83-4dfc-4b89-b5de-d648eacce19a` (project)
- **Filed from:** https://openendedgroup.com/field4/reactive_properties/
- **Filed by:** human, 2026-10-16 01:55
- **Close with:** `python tools/docs_notes.py handoff-close 0789be83 "what happened"`

Found 2026-10-16 while verifying handoff 48884767. `tools/reactive_props_scenarios.py` had not been runnable since the package move: three scenarios called `afield.ReactiveRuns` / `afield.thisSessionInfo`, and those names are fixed now. With that fixed, 34 of the 35 scenarios pass. `down_external_writer_not_capped` fails every time (3 of 3 runs), and it also fails with this branch's two reactive changes (the `setPropertyRaw` note and the pump) reverted, so it predates them.

The scenario: A declares `propDown<Int>(x)`, B reads `x` and sleeps 300 ms per run, and a control box writes `a.x = i` from a plain `Thread`, 20 times, 100 ms apart. It expects no cap and B's `result == 20`. Observed: B's `result` is null, and the dump in `build/reactive-scenarios/` shows no cascade events at all. Suspect: a write from a non-runloop thread is not reaching `Downstream`, or B's first plain run never set `result`. Run `uv run --project . --group backdoor python tools/reactive_props_scenarios.py --verbose down_external_writer_not_capped` to reproduce.
