# Replace getPropertyRaw in kits.md's Rules box fence

- **Handoff:** `1127f38d-88d2-49af-9690-dddb2674bed2` (project)
- **Filed from:** https://openendedgroup.com/field4/kits/
- **Filed by:** human, 2026-10-15 13:49
- **Close with:** `python tools/docs_notes.py handoff-close 1127f38d "what happened"`

`public/docs/kits.md`, section "Make a kit from a document": the Rules box fence reads its rules
with the escape hatch

> val rules = thisBox.getPropertyRaw("roomRules", "").toString().lines().filter { "=" in it }

`getPropertyRaw` is not how box code is written; docs and demo box code use `prop<T>(name)` and then
the bare property name. The fence is byte-pinned (`diffstatic`), so this needs a code change, not a
prose edit: rewrite the Rules box to declare `roomRules` with `prop<String>(...)` (or the nullable
form if it can be missing) and read it by name, update the kit fixture/document the page is built
from to match, and recapture the page's figures and outputs that depend on it (the kit revision hash
`dad7f4dc`, quoted in several outputs and figure captions on the page, will change with the role code).
