# Type FieldML callback parameters in named records and arity-only overloads

- **Handoff:** `c0eeb1d5-be43-4faa-aca2-30b8366484b6` (project)
- **Filed from:** https://openendedgroup.com/field4/fieldml_host_bridge/
- **Filed by:** human, 2026-10-16 07:02
- **Close with:** `python tools/docs_notes.py handoff-close c0eeb1d5 "what happened"`

Refiled from the code batch on 2026-10-16 unchanged in scope; it is three separate pieces of type-checker work and was not started. Code map for whoever takes it:

- `Inferencer.preInferHostSpine` (afield/lang/fieldml/type/Inferencer.kt) types lambdas only for positional arguments. It builds `argumentTypes` with `placeholderFunction(arity)` and asks `FieldMLHostTypes.{constructor,static,instance}Parameters`, which go through `parametersOf(candidates, arguments)` and return one `HostParameter` per position.
- Named calls are resolved separately: `namedMethodResultType`, `namedStaticMethodResultType` and `namedConstructorResultType` via `resolveNamed(candidates, argumentNames, …)`. These return a result type, not per-parameter HostParameters. Case 1 needs a `namedParameters(…)` returning HostParameter by name, then threading each record field's ELambda through `inferLambda(..., expected)` the way the positional loop does. `hostCallShape`/`spreadExprs` would need to see the ERecord's fields.
- Case 2 (overloads differing only in lambda arity): `parametersOf` presumably returns null slots when more than one candidate survives. Filter candidates by `samParameterTypes(slot).size` against the lambda's arity before giving up.


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
