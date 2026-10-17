# Remove getPropertyRaw and manual close from OpenDlssNr study box code

- **Handoff:** `917aec9c-1b39-4954-a3fd-c1a07ef27ab5` (project)
- **Filed from:** https://openendedgroup.com/field4/open_dlss_nr/
- **Filed by:** human, 2026-10-15 13:48
- **Close with:** `python tools/docs_notes.py handoff-close 917aec9c "what happened"`

`open_dlss_nr.md` (both the cube and ink study fences, and the transcripts `open_dlss_nr.yaml` / `open_dlss_nr_cubes.yaml` they mirror) write box code with escape hatches:

```
prop<OpenDlssNrStudy>(lab)
(thisBox.getPropertyRaw("lab") as? OpenDlssNrStudy)?.close()
lab = OpenDlssNrStudy(owner, ..., vertex=thisBox.getPropertyRaw("vertex") as String, ...)
```

`getPropertyRaw` is an escape hatch, and closing the previous instance by hand is boilerplate every reader must copy. Fix in code: give the box a typed accessor for its `vertex`/`fragment` properties, and have a relaunch-safe owner (`once`-style or a lifecycle-released prop) close the previous study automatically. Then rewrite the fences and transcripts, and recapture (linux-nvidia profile). Fences are byte-pinned, so this is not a prose edit.
