# Move stereo RTT regression-check fence into an Agents aside

- **Handoff:** `1425c219-b41d-4d0d-abb8-51a228f4ec47` (project)
- **Filed from:** https://openendedgroup.com/field4/stereo_rtt_composite/
- **Filed by:** human, 2026-11-02 10:30
- **Close with:** `python tools/docs_notes.py handoff-close 1425c219 "what happened"`

Rule 26 in public/docs/stereo_rtt_composite.md: the paragraph "The native regression check exercises
first-frame sampling, one render pass per eye and square-eye projection on a non-square window, with
Vulkan validation enabled:" and the following bash fence
(`tools/run_field_tests.py --id four.stereo-packing.smoke`) are addressed to maintainers, not users.
Moving them into a `??? aside "Agents: regression check"` would indent a byte-pinned fence, so this
needs a fence-aware move (update the pins). Same for the sentence before it, "Its automated headless
check compiles the boxes without opening a window".
