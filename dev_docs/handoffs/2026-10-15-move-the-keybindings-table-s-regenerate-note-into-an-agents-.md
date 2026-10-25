# Move the keybindings table's regenerate note into an Agents aside

- **Handoff:** `698ea053-dc81-482b-90fc-61be79943178` (project)
- **Filed from:** https://openendedgroup.com/field4/keybindings/
- **Filed by:** human, 2026-10-15 13:41
- **Close with:** `python tools/docs_notes.py handoff-close 698ea053 "what happened"`

`public/docs/keybindings.md`, inside the generated defaults block, says to readers:

> **133 rebindable commands**, 55 of them with a shortcut out of the box. Regenerate this table against a running Field with `tools/keybindings_defaults_table.py`.

The second sentence is for agents (rule 26), but it is written by `tools/keybindings_defaults_table.py`, so a prose edit would be overwritten. Change the generator to emit the regenerate instruction as a collapsed `??? aside "Agents: regenerating this table"` (body indented four spaces) after the count line, and regenerate the block.
