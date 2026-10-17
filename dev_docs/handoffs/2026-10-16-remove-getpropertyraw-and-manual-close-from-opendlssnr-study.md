# Remove getPropertyRaw and manual close from OpenDlssNr study box code

- **Handoff:** `c40cc5b7-57ec-4772-86be-8a98d9f294f5` (project)
- **Filed from:** https://openendedgroup.com/field4/open_dlss_nr/
- **Filed by:** human, 2026-10-16 07:48
- **Close with:** `python tools/docs_notes.py handoff-close c40cc5b7 "what happened"`

Refiled from the code batch on 2026-10-16. OpenDlssNr runs only on NVIDIA, and this session was on macOS, so neither the change nor the linux-nvidia recapture could be verified. Take it in a Linux/NVIDIA session.


`open_dlss_nr.md` (both the cube and ink study fences, and the transcripts `open_dlss_nr.yaml` / `open_dlss_nr_cubes.yaml` they mirror) write box code with escape hatches:

```
prop<OpenDlssNrStudy>(lab)
(thisBox.getPropertyRaw("lab") as? OpenDlssNrStudy)?.close()
lab = OpenDlssNrStudy(owner, ..., vertex=thisBox.getPropertyRaw("vertex") as String, ...)
```

`getPropertyRaw` is an escape hatch, and closing the previous instance by hand is boilerplate every reader must copy. Fix in code: give the box a typed accessor for its `vertex`/`fragment` properties, and have a relaunch-safe owner (`once`-style or a lifecycle-released prop) close the previous study automatically. Then rewrite the fences and transcripts, and recapture (linux-nvidia profile). Fences are byte-pinned, so this is not a prose edit.
