# Move neural particles smoke-test section to dev_docs

- **Handoff:** `983e4b01-a131-4518-a74f-4114e32aa582` (project)
- **Filed from:** https://openendedgroup.com/field4/neural_particles/
- **Filed by:** human, 2026-10-15 13:49
- **Close with:** `python tools/docs_notes.py handoff-close 983e4b01 "what happened"`

`public/docs/neural_particles.md`, "Running it", is maintainer-facing (which smoke tests to run after changing the machinery) and contains a byte-pinned fence, so it cannot become an `??? aside "Agents: ..."` without re-indenting the fence:

> The four transcripts are the intended entry point. The four GPU smokes are the same machinery driven headlessly, and are what to run after changing any of it: (fence of four `tools/run_field_tests.py --id ...` lines) All four need a GPU and an awake display: Field 4 has no headless mode...

Fix: move the section (with its fence) into `dev_docs/NEURAL_PARTICLES_MEASUREMENTS.md` or a test note, and replace it on the page with a collapsed `??? aside "Agents: smoke tests"` linking there. Also resolve the contradiction "driven headlessly" vs "has no headless mode". The "awake display" requirement is itself a rule-25 sharp edge (GPU smokes needing a display) worth tracking separately if fixable.
