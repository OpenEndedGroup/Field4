# Python box parity — a document you can open

Open `metadata.edn` in Field (**File ▸ Open**, or `./field.sh <path to this folder>/metadata.edn`).
It is the saved form of `dev_docs/agents/transcripts/python_box_parity.yaml`; the written-up
version is [Python in a box](../../../../public/docs/python.md).

- **an error is marked where it happened** — run it: the red marker sits under line 6, inside
  `mean`, where the division by zero happened.
- **a slider and a checkbox, with Python callbacks** — run it, then move the slider or tick the
  box; the Python functions redraw the `main` panel. Run it again and the controls keep their values.

Prerequisite, as for `python_cells`: Field must have found a Python with JEP before it started
(`public/docs/python.md`, "Setting Python up").
