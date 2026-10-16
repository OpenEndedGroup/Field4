# Remove obsolete point-in-steering error example from alife_script README

- **Handoff:** `17a1a6b6-7a31-46a8-b663-938b5b2883d6` (project)
- **Filed from:** https://openendedgroup.com/field4/alife_examples/
- **Filed by:** human, 2026-10-16 19:38
- **Close with:** `python tools/docs_notes.py handoff-close 17a1a6b6 "what happened"`

The alife_script one_agent teaching code now correctly explains that SteeringScope forwards point(...). Its README still presents using point inside moveFor as a receiver-resolution error. Replace that obsolete error example with current valid usage and explain the optional reusable target declared outside the repeating block. Source: simulation/agents/alife_script.kt forwards both point overloads in SteeringScope.
