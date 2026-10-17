# Make the Sonic Pi pattern demo a starting point

- **Handoff:** `4ccca1a9-8be0-4e7a-b45a-46d46d8b18c7` (project)
- **Filed from:** https://openendedgroup.com/field4/sonic_pi/
- **Filed by:** human, 2026-10-08 03:14
- **Close with:** `python tools/docs_notes.py handoff-close 4ccca1a9 "what happened"`

`public/docs/sonic_pi.md` ("A document to open") points readers at a workspace path,
`public/docs/assets/sonicpi_pattern_demo/metadata.edn`, rather than a starting point. Rule 19 of
`dev_docs/agents/DOCS_WRITING_RULES.md`: anything a reader can open in Field is a starting point
linked from the page.

Make the three-box Sonic Pi pattern demo a starting point on the welcome screen (as was done for
`faust_polyphony` and `pure_data_demo`), and link it from the page with an
`offline.html?file=...` link. `SonicPiPatternDemoDriftTest` asserts the page contains the
`assets/sonicpi_pattern_demo/metadata.edn` string and that the section's three fences match the
document; update the test with the move.

The demo's first fence also carries a stale comment pointing at `public/docs/sonic_pi.md` and
hard-coded counts ("66 synths, 42 FX, 206 samples"); trim it when the fences are regenerated from
the document.
