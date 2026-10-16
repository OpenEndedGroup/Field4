# Type FieldML callback parameters in named records and arity-only overloads

- **Handoff:** `03a17b85-d03d-4162-9a53-26b93f5a77d1` (project)
- **Filed from:** https://openendedgroup.com/field4/fieldml_host_bridge/
- **Filed by:** human, 2026-10-15 13:47
- **Close with:** `python tools/docs_notes.py handoff-close 03a17b85 "what happened"`

`public/docs/fieldml_host_bridge.md`, section "What it still will not do", lists gaps in callback typing that are fixable in the type checker rather than facts to document (rule 25):

> - A lambda inside a named record has untyped parameters, because parameter types only reach a lambda written as a positional argument. ... `hostStaticCall "defaultBuffer" (hostNull, "inData", fun p -> …)` types `p`, the named spelling does not.
> - A callback written inside a helper has untyped parameters. ... Use `hostAs "Vec3"` there.
> - Two overloads that differ only in how many parameters their lambda takes leave the callback untyped. `start` takes both `suspend Frames.() -> Unit` and `suspend () -> Unit` ...

Also the matching sentence in "Passing a function" ("You still need `hostAs` when the receiver is not known ...").

Fix in `afield.lang.fieldml.type.Inferencer` (`preInferHostSpine`) / `FieldMLHostTypes`:
1. Thread expected parameter types into lambdas inside a named-argument record (match record field to the parameter by name).
2. When overloads differ only in lambda arity, pick the candidate by the FieldML lambda's own arity and type its parameters.
3. (Harder, optional) for a callback inside a helper, defer/generalise so the host type flows in at the use site.

Extend `FieldMLLambdaExpectedTypeTest` for each, and in the same change delete the corresponding bullets (and the `hostAs` sentence / the "limits box" row in the lambda demo table if case 3 is fixed) from the page.
