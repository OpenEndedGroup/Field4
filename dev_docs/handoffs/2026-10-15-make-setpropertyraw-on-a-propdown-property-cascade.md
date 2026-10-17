# Make setPropertyRaw on a propDown property cascade

- **Handoff:** `15af5a95-7cdd-494c-a298-137b6f8ddb69` (project)
- **Filed from:** https://openendedgroup.com/field4/reactive_properties/
- **Filed by:** human, 2026-10-15 13:42
- **Close with:** `python tools/docs_notes.py handoff-close 15af5a95 "what happened"`

`public/docs/reactive_properties.md` (section "A control reruns the boxes that read it") says:

> A `setPropertyRaw` of your own goes around the declaration and reruns nothing: assign the property instead.

That is a sharp edge (rule 25). `syncToProperty` was fixed to note a raw write to a `propDown` property as a change (`ReactivePropsTest.aRawWriteToADeclaredDownPropertyIsAChange`); make every `setPropertyRaw` on a property with a reactive declaration do the same (or route it through the delegate), so the escape hatch cannot silently skip the cascade. Delete the paragraph in the same change.
