# Publish the scene-to-light demo as a starting point and link it

- **Handoff:** `e6b9a9bc-ee24-489e-9840-dca7ca971a87` (project)
- **Filed from:** https://openendedgroup.com/field4/chain_scene_to_light/
- **Filed by:** human, 2026-10-03 00:00
- **Close with:** `python tools/docs_notes.py handoff-close e6b9a9bc "what happened"`

`public/docs/chain_scene_to_light.md` used to name its live demo by workspace path (`public/docs/assets/chain_scene_to_light_demo/`): a sender box that animates the scene onto the rig, a receiver box that draws the rig from what arrives, and a box that repeats the page's measurements. The language pass removed the path (rule 19: never a workspace path).

Publish that document as a welcome-screen starting point, then add a "Running it live" paragraph to the page linking it, in the way `chain_drawn_score.md`'s "Drawing your own" section names drawn_score: what each box does, and "edit the scene in the first box and launch it again to see the change reach the second".
