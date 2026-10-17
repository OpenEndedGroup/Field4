# Fix keyframe module-pointer targets to address any uniform member

- **Handoff:** `5cf2b6c5-84b5-4534-96c8-c0363f3776e6` (project)
- **Filed from:** https://openendedgroup.com/field4/keyframer_examples/
- **Filed by:** human, 2026-10-15 13:44
- **Close with:** `python tools/docs_notes.py handoff-close 5cf2b6c5 "what happened"`

Rule 25 sharp edge on keyframer_examples.md, aside "The module-pointer target overloads can only address one member per block":

The `target(module: ModulePointer, name)` / `ComputeModulePointer` / `MeshModulePointer` overloads use one `@`-suffixed string both to select the structure in `liveUniformStructures` (keyed by uniform BLOCK name) and as the member name inside it, so they can only address a member whose name equals its block's name. The page tells readers to use the plain `Structure` overload instead.

Fix in `afield/animation/keyframes/keyframer.kt`: split the name into block and member (e.g. `"block.member"` or `member@block`), or deprecate/remove the overloads. Delete the aside in the same change.
