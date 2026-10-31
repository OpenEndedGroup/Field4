# Build field_polyvector on Windows

- **Handoff:** `a7cd9c45-dfbb-4011-bd8d-60cc7084126c` (project)
- **Filed from:** https://openendedgroup.com/field4/polyvector/
- **Filed by:** human, 2026-10-27 18:21
- **Close with:** `python tools/docs_notes.py handoff-close a7cd9c45 "what happened"`

`public/docs/polyvector.md`, note "The library is optional", ends with:

> Windows is not supported yet.

This is a rule-25 sharp edge: a platform gap documented in prose. Fix in code/build: make
`./gradlew buildPolyvectorNative` (and the welcome page's Geometry extensions panel and
`./field.sh --setup` / `field.ps1`) build `field_polyvector` on Windows (OpenCV, Boost, Eigen via
vcpkg or similar), or have the panel report the unsupported platform itself. Delete the sentence
from the page in the same change.
