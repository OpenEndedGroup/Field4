# Make an active venv win over the interpreter /welcome saved

- **Handoff:** `f4dfd73b-5106-478a-be5d-bca2c8608e4b` (project)
- **Filed from:** https://openendedgroup.com/field4/python/
- **Filed by:** human, 2026-10-15 13:48
- **Close with:** `python tools/docs_notes.py handoff-close f4dfd73b "what happened"`

Rule 25 sharp edge on python.md ("Setting Python up"):

> Activating a venv may not be enough to switch interpreters: the one saved by `/welcome` ranks above `VIRTUAL_ENV`. Set `FIELD_PYTHON_VENV` or `FIELD_PYTHON_EXECUTABLE` to be sure.

Fix in code (afield/lang/python interpreter discovery): rank an explicitly active `VIRTUAL_ENV`/`CONDA_PREFIX` above the saved `~/.afield/python.properties`, or have the status line say which saved choice overrode the active venv. Delete the paragraph (and update the ranked list) in the same change.
