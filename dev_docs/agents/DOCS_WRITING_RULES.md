# Writing the docs: rules from the notes

These rules are distilled from the notes Marc left on the published pages in the 2026-09-30 and
2026-10-01 revision passes (`tools/docs_notes.py`, `~/.afield/docs-annotations.json`), and from the
edits that answered them. Every rule below was broken somewhere on the site and asked for by name.
Read them before writing or revising anything under `public/docs/`. They apply to page agents
(`docs_notes.py revise`), handoff sessions, the documentation voice pass, and anyone else.

`AGENTS.md` section 8 has the house mechanics (`!!! note` vs `??? aside`, the build, the runner).
This file is about what goes on a page and how it reads.

## Who the page is for

1. **Write for the person using Field, not for us.** How a page was made, what an earlier version
   got wrong, why a guard exists, and internal mechanism a user never touches go in a
   `??? aside` at most, or in `dev_docs/`, or nowhere. The test: would someone who does not care
   how the page was made want to read it? ("Stop talking to Marc and start talking to our users.")
   The reverse also holds: setup a user has to do (enabling an optional SDK control, installing a
   tool) is for the reader, so it goes in an open `!!! note`, not a collapsed aside
   (`direct_lighting.md`, 2026-10-11). A contributor-only section that contains a code fence
   cannot become an aside without indenting the fence, which changes its pinned bytes; file a
   handoff to move it to `dev_docs/` and link it (`vision_geometry.md`, 2026-10-11).
2. **No lab-notebook voice.** Do not narrate an investigation and report findings as you make them.
   Say how the thing works and how to use it. The same goes for a "missing features" section: it
   says what is and is not supported, and what to use instead. Effort estimates ("a weekend of
   Kotlin"), roadmap guesses and who has not built what yet belong in `dev_docs/`.
3. **Lead with the common case.** Open on what people actually type (`loop {}`, then `forever {}`,
   with `frames {}` as the building block under both), and move mechanism, edge cases and clever
   variants to an internals section at the bottom.
4. **Cut what nobody would do.** Remove warnings about hypothetical misuse, admonitions that guard
   nothing, deprecated spellings kept "for safety", and security caveats that belong to the user's
   own network (a VPN or tunnel is theirs, not ours). An admonition must earn its box.
5. **No AI-speak or invented vocabulary.** "load-bearing", "seam", "carrier", "on the seam", and
   any term the reader has not met are out. Define a necessary term where it first appears, or
   use plain words. Rephrase anything that only parses on a second reading ("two ways out of
   where?"). The ban is on invented or metaphorical use, not on a field's own terms: a carrier
   wave in signal processing, or a `seam` that names a real signature (`sound_paths.md`), stays.
   "Seam" as a metaphor for a boundary or a join does not (`spatial_geometry_examples.md`,
   2026-10-11).

## Code on the page

6. **Introduce code before showing it.** One sentence says what the example builds, and, when it
   is longer than usual, why. A heading is never followed directly by a fence.
7. **Write box code the way a box is written.** The box is the implicit receiver. Use the
   `renderer` the preamble already gives you, not `val r: Renderer = fromContext()`. No
   `afield.stdlib.*` imports; fold the remaining ones. Use `print` (`_print` is gone). Use
   `markdown(...)` for output, and raw HTML only where the raw-HTML escape hatch is the subject.
8. **Hide the boring parts.** Long setup in an example uses the editor's fold facilities so a
   reader sees the idea first.
9. **Keep beginner examples short.** Code that is impressive but frightening goes at the end, in
   the internals section, or not at all.
10. **The code runs, or the code gets fixed.** A fence that does not run is a bug in the code or
   the runtime. Fix that; never reword the prose around a broken example.
11. **Fix the name, not the explanation.** If an API's name misleads, rename it in `src/` (there
   are no outside users yet) rather than writing a paragraph apologising for it. That is a handoff
   if it is more than a rename.

## Figures, video and sound

12. **A UI feature gets a real capture.** "Screenshot needed" was the most common note. A page
   about a panel, a tab or a gesture shows it.
13. **Look at every capture you make, at column width.** It is legible, not half size. There is no
   empty canvas or editor below the content (trim or clip it). The interesting state is showing
   (a selected version, not the current one; the panel open, not the window). It matches today's
   UI, including the command palette's current shortcut. Alt text and captions are prose, and
   they describe what the code draws today: when they disagree with the fence (a caption saying
   twelve wires over code that builds ten, from an older capture), fix the text to match the code
   (`sorting_networks_examples.md`, 2026-10-08).
14. **Captures come from the runner.** Videos come from a `capture="ui"` fence or an operation
   transcript (use `hide-toasts=true` when notifications cover the subject), never a hand
   recording, which goes stale silently. AGENTS.md section 3 forbids faking one.
15. **A page about sound has sound, made from real material.** Use the pinned Dirt-Samples
   (`./gradlew fetchDocsDirtSamples`), not a stand-in synth that sounds like noise. A page whose
   subject is audio and has nothing to listen to should not be a page yet. One exception: a
   pitched page whose own analysis listens back to its render (YIN pitch tracking on
   `chain_drawn_score.md`, `chain_drawn_morph.md`) may keep a plain synth voice such as
   `patVoice`, because the analysis needs a clean, detached, single-pitch signal (Marc,
   2026-10-03). A page that says its output cannot be captured ("realtime only", "needs
   hardware") has that claim checked against the build tasks and tests: if one already produces
   the output, publish it through the runner; if the claim is wrong, fix the page or the code
   rather than leave the gap (Marc, 2026-10-07; `pat_spatial.md` said this while
   `./gradlew headTrackedDemo` wrote a WAV).

## Where things live

16. **One topic, one page.** Merge duplicates, and fold thin pages into a neighbour. File a
   `navigation` handoff for it; do not improvise a merge inside a page edit.
17. **Place a page by its reader.** Internals stay out of *Start Here* and off the top of a
   section. A page that is really a graphics demo belongs with the graphics chains, not under
   *Working in a document*.
18. **Short titles that say what the page is for.** "How to make sound in Field", "File
   management", "Automating Field". The nav label must not wrap.
19. **Link whatever a reader can open.** A runnable document is a starting point on the welcome
   screen, linked from the page, never a workspace path. A source path links to its file on
   GitHub, spelled `https://github.com/OpenEndedGroup/Field4/blob/main/<path>`; that includes a
   `dev_docs/` note a user page points at, and if the note is not worth a link it is not worth
   naming. Links
   are plain text, not bold. Pages that relate say so with a "see also". Every saved document under
   `public/docs/assets/` and `dev_docs/agents/demos/` is a starting point, linked as
   `agent_transcripts.md#<name>`; `AgentDemoRegistrationTest` fails on one that is not, apart
   from a named backlog that may only shrink.
20. **A fact on a page stays true.** When code changes, the page changes in the same commit, and a
   number that can be derived (a count of call sites, pages, transcripts) is derived by its
   guard, not typed.

## When the note asks for more than an edit

21. **Larger work becomes a handoff.** A merge, a recapture, a code change or a rename across
   pages goes in a handoff document (`docs_notes.py handoff`), not an improvised edit.
22. **An answer that needs no edit closes the note.** Resolve it with the reason; do not rewrite a
   page to look responsive.

## Making the edit

23. **Re-read every edited sentence from its first word.** Checking only the replaced words is
   how a split or merged sentence ships half-joined ("…collection, In the saved lessons…",
   `afield_four_graphics.md`, 2026-10-11). Consolidation is where a prose pass most often
   introduces an error, factual or grammatical.
24. **Before renaming a heading, find everything that uses its text.** Grep `public/docs` for
   inbound `page.md#anchor` links, and grep `tools/` and `src/` for the heading string: a packaged
   page can share its turn titles with the transcript its packager writes
   (`stackauto_conversation.md` and `tools/package_stackauto_conversation.py`), and a guard can pin
   an anchor (`#sonicpi_pattern_demo` on `agent_transcripts.md`).

## What a page should not have to say

25. **A sharp edge the page has to warn about is a bug: fix it, do not document it.** If a
   paragraph exists only to explain an exception, a stale file, a workaround, a name that
   misleads, or "these two still do not…", the fix belongs in the code, data or tool, and the page
   then says nothing about it. A prose pass files a `code` handoff for each one, quoting the
   paragraph, and leaves the paragraph in place until the fix lands. The handoff says what to fix
   and that the paragraph is deleted in the same change (Marc, 2026-10-15: `agent_transcripts.md`
   documented two build scripts that no longer rebuilt their documents, and a test that listed
   them as known-stale "so an exception cannot outlive its reason").
26. **Material written for agents goes in an agents' aside.** Sometimes a page does need to tell an
   agent something a reader does not need: which guard pins a file, what not to hand-edit, which
   tool regenerates it. Put it in a collapsed `??? aside "Agents: <topic>"`, its title starting
   with `Agents:`, never in the body text. If the agent-facing note describes a hazard that a
   tool could prevent, it is also rule 25.

## Turning these into checks

`AGENTS.md` asks that every "do not" name the tool change that would retire it. These are the
cheapest first checks, none of them written yet:

| rule | check |
| --- | --- |
| 5 | a banned-vocabulary list over `public/docs/*.md` prose, outside fences and asides |
| 6 | a heading line followed, after blank lines only, by a fence |
| 7 | inside a runner fence: `fromContext<Renderer>()`, `import afield.stdlib.`, `_print(` |
| 13 | a capture whose bottom rows are a uniform background colour over some height |
| 16 | `chains.md` has a card for every page in its nav section (two were missing, 2026-10-11) |
| 19 | a `**[...](...)**` link; a GitHub link not spelled `OpenEndedGroup/Field4` |
| 20 | a page stating how many transcripts the welcome screen withholds agrees with `AgentTranscriptMaterializer.EXCLUDE` (stale twice) |

The documentation voice pass (`~/.claude/scheduled-tasks/documentation-voice-pass/`,
`score_voice.py`) already scores structural faults such as headings, slide labels, plan language
and methodology defences, and is the natural home for rules 1–5 when it runs again.
