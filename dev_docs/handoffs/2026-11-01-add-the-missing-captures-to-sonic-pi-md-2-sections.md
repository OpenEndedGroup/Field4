# Add the missing captures to sonic_pi.md (2 sections)

- **Handoff:** `57dcf670-1f6c-43e7-a02b-728623d6cee2` (project)
- **Filed from:** https://openendedgroup.com/field4/sonic_pi/
- **Filed by:** human, 2026-11-01 13:52
- **Close with:** `python tools/docs_notes.py handoff-close 57dcf670 "what happened"`

From the 2026-11-01 punchline audit: each section of the site was checked for whether it makes a point or shows something that works, but the reader never sees the result. A skeptic pass re-checked every finding against the page. Each capture must come from the real code (AGENTS.md §3, "A capture exercises the real code"); if the real one can't be made, omit it and say why.

## Sections missing a punchline

### A box that is a piece of music (`public/docs/sonic_pi.md:24`, high, needs-capture-system-work)

- **Point made:** A #sonicpi live_loop in a box is a piece of music that plays while the box stays lit, but the page never lets you hear it or see the lit box.
- **Capture:** An audio= capture of the :bikes live_loop recorded from a real Sonic Pi, the same recording path the 'Which one keeps time' figure already uses, plus a screenshot-box of the lit, running box.

### Which line is making sound (`public/docs/sonic_pi.md:98`, high, needs-capture-system-work)

- **Point made:** The editor lights each line as Sonic Pi triggers it and fades it over half a second, so you watch the music move through the code. The page only describes this.
- **Capture:** A short record-box/editor video of the drums+bass example running against real Sonic Pi, with the editor open showing lines pulsing. A still can't show the fade, so it needs a video.


## Done when

Every section above shows its result through a runner capture (or reused committed media that exercises the real code), or this handoff records why that section stays without one.
