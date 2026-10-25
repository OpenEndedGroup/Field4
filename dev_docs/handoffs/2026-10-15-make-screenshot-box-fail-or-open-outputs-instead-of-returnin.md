# Make screenshot-box fail or open outputs instead of returning ok:true on a collapsed box

- **Handoff:** `90f5a9ee-6f2d-4030-97cf-b6a5e3883829` (project)
- **Filed from:** https://openendedgroup.com/field4/automating_field/
- **Filed by:** human, 2026-10-15 13:41
- **Close with:** `python tools/docs_notes.py handoff-close 90f5a9ee "what happened"`

`public/docs/automating_field.md` ("Driving Field from a script or agent") carries this paragraph:

> Some captures fail without saying so. A `screenshot-box` of a box whose outputs were
> never opened comes back `ok: true`, clipped to the collapsed box rather than to the output
> you wanted. [What a Capture Can and Cannot Show](capture_limits.md) lists these cases and
> the reply field that tells you when you have hit one.

This is a sharp edge (DOCS_WRITING_RULES rule 25): a capture that did not capture what was asked
reports success. Fix it in `tools/field_backdoor.py` / the screenshot path: either open the box's
output area before clipping, or return `ok: false` (or a hard error) when the clip falls back to
the collapsed box, rather than relying on a `captureWarnings` field the caller has to know about.
Audit the other silent cases listed on `capture_limits.md` the same way.

Delete the paragraph above from `automating_field.md` in the same change (and shrink
`capture_limits.md` to match).
