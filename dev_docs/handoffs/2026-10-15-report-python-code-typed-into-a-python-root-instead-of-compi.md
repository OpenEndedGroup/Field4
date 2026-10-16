# Report Python code typed into a Python root instead of compiling it as Kotlin

- **Handoff:** `f88ad9f1-e5a9-4afd-a258-6459acb645a6` (project)
- **Filed from:** https://openendedgroup.com/field4/python/
- **Filed by:** human, 2026-10-15 13:48
- **Close with:** `python tools/docs_notes.py handoff-close f88ad9f1 "what happened"`

Rule 25 sharp edge on python.md:

> **The root itself is not Python.** Python code typed into the root box is compiled as Kotlin, and fails.

Fix: when a box marked as a Python root fails Kotlin compilation and its code parses as Python, say so ("this is the Python root; put Python in its children"), or make the root accept Python. Delete the bullet in the same change.
