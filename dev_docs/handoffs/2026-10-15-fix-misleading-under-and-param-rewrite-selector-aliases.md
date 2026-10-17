# Fix misleading under= and param= rewrite selector aliases

- **Handoff:** `f6646bdc-6ba2-4327-8932-9a51af23df77` (project)
- **Filed from:** https://openendedgroup.com/field4/functions_examples/
- **Filed by:** human, 2026-10-15 13:43
- **Close with:** `python tools/docs_notes.py handoff-close f6646bdc "what happened"`

`public/docs/functions_examples.md` (selector reference table for `rewrite`/`previewRewrite`) has rows that exist only to warn about misleading selector names:

> | `param=frequency` | Selects the incoming frequency parameter. `param` is an alias for `role`, not the `param` node class. |
> | `under=sin` | Uses the same immediate-parent check as `parent=sin`. It does not mean any ancestor at any depth. |

Rule 25: `under=` reads as "any ancestor" and `param=` collides with the bare `param` node-class term. Fix in the selector parser (`afield.math.functions` rewrite selectors): make `under=` mean any ancestor (or remove it), and drop the `param=` alias for `role` (or reject it with a message pointing at `role=`). Update the table rows in the same change so they no longer carry the warning.
