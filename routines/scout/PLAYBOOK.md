# Idea scout: playbook

The `scout` routine follows this file top to bottom. `ROUTINE.md` beside it says how a run starts
and what it must never do; `tools/scout.py` does all the network and bookkeeping. You do the
reading and the judging. Nobody is awake, so never ask a question: make the call, note why in the
report, and carry on.

Read `dev_docs/scout/BACKFILL_2026-11-06.md` once if this is your first run on a machine (the
journal's `reports/` is empty). It is the year-long test this routine was designed from, and its
cards are the standard to write to.

## 1. Marc's verdicts

`python3 $REPO/tools/scout.py verdicts`

This reads every digest in `$JOURNAL/digests/` for `verdict:` lines Marc has filled in. `yes` goes
to `approved.md` (the self-improve loop's scout backlog), `no` is recorded, and `later` puts the link
in the watch pool. Each answer and its reason is added to `SCRATCHPAD.md` under "Marc's verdicts".
Read the new ones before you judge anything today. A `no: we have this` means the repo search
missed something; a `yes: with the drawing layer` tells you what kind of "What Field adds" lands.

## 2. Harvest

`python3 $REPO/tools/scout.py harvest`

It collects from `routines/scout/sources.json`: the HN front page above 50 points; HN title and URL
queries for topics Field already works on, with a one-point floor; lobste.rs tags; and a list of
feeds. Graphics Programming Weekly is expanded into the links each issue lists. Links are
deduplicated by normalized URL, and a repost adds a sighting to the existing link. A source that
fails is printed as `FAILED ...`; record any failures in the report and carry on. Two runs in a row
with the same failure belong in `SCRATCHPAD.md` as a sources fix for Marc.

## 3. The title pass: drop only the plainly off-domain

`python3 $REPO/tools/scout.py triage-list > $JOURNAL/titles.tsv`

The columns are key, title, domain and points, plus any topic tags. Write `$JOURNAL/drop.tsv` listing the keys
to **drop**, one per line, then run `python3 $REPO/tools/scout.py triage --drop $JOURNAL/drop.tsv`,
which queues everything else for reading. (`*.tsv` is not committed.)

Drop a title only when no page behind it could matter to a live-coding environment for graphics,
sound, simulation, geometry, visualization or creative tools: politics, business and funding, the
AI industry (model launches, safety papers, lawsuits), security advisories, operating system and
phone news, hiring, general programming-language internals with no media angle, and personal
essays about work. **When in doubt, keep it.** A headline tells neither Marc nor you whether a page
is a fit. TALA, Easel and MorphoHDL all looked like niche tools and all were real catches. Links
with topic tags are never dropped on their title (`triage` enforces this). Expect to drop about
two thirds of the HN front page and almost nothing from the feeds.

## 4. Fetch

`python3 $REPO/tools/scout.py fetch`

It fetches up to 40 queued pages (topic hits first, then by points) to `$JOURNAL/pages/<key>.txt`,
and runs any watch checks that are due. GitHub repos arrive as their README plus license, stars,
language and latest release from the API. A page that renders little text has its HN discussion
appended. Whatever stays queued is fetched tomorrow.

## 5. Read every page and write a card

`python3 $REPO/tools/scout.py reading-list` lists `key`, page path and title for each fetched page.
Read the whole page file, then write `$JOURNAL/cards/<key>.md`. Every page gets a file, even a drop.
A row marked `[watch: page changed]` is a watched link whose page moved: read the new page and
replace its old card.

Budget your reading: open each page's first 12,000 characters or so, and read the rest only when
the page is a candidate. If your harness has sub-agents, give each one about ten pages, the card
format below, and the safety rules from `ROUTINE.md`; otherwise read them yourself. Stop at 25 card
files in one run; the rest stay fetched for tomorrow, topic hits and high points first.

### Before writing a card: search Field

Find the nearest existing Field work before you decide what Field would add:
`git -C $REPO grep -l -i -E '<two or three distinctive terms>' -- src dev_docs public/docs`, plus
`ls $REPO/dev_docs $REPO/public/docs` for page names. Slug, Kalman and Forth were all already in
Field during the backfill, and a card that proposes something Field has wastes Marc's attention. If
Field already has it and the page adds nothing, drop it, giving the Field page as the reason. If the
page adds something to what Field has, write a `touches` card.

### Verdicts

- `card`: worth Marc's minute. There is something concrete to reimplement or bridge, and Field
  would add something the original lacks.
- `watch`: promising but not yet actionable. The repo has no code yet, the post promises a part
  two, the paper's code is "coming soon", or there is a release worth waiting for. Give
  `watch-for:` (what news would bring it back). The pool is finite (40) and checks back after 7, 14,
  28 and 56 days. A check that finds the page changed brings it back here for a new card. After five
  quiet checks it leaves, and when the pool is full the link that has gone longest without news is
  evicted.
- `drop`: one line saying why ("generic web framework", "Field has this: public/docs/slug_paths.md",
  "page is a product landing page with no method").

### Card format

```markdown
---
verdict: card
kind: new
type: reimplement
nearest: none (searched: eigenmode, Chladni, membrane)
license: non-commercial
---
**What it is.** A browser drum: draw an outline and hear its vibration modes.

**Method.** FEM eigenmodes of the drawn region (−∇²u = λu, zero at the edge; the generalized
eigenproblem Kφ = λMφ). A strike projects the mallet position onto the modes; Rayleigh damping.

**What Field adds.** The shape is drawn on the drawing layer, which `notation` already reads; the
modes go straight into the audio engine; an animated shape changes its timbre as it morphs.

**Figures.** The 16-mode list with each mode played alone; the mesh view; the Kac isospectral pair;
polar-equation input.

**License.** Source available, non-commercial: reimplement from the method, which is textbook,
and copy no code.

**Size.** A week.
```

Rules for the fields:

- `kind`: `new` for an idea Field doesn't have; `touches` for news that adds to Field work that
  already exists, in which case `nearest:` must name that work (a `dev_docs/` page, a
  `public/docs/` page, or a source folder).
- `type`: `reimplement`, `bridge`, `interaction`, `paper`, `dataset` or `tool`.
- `nearest`: the closest Field page or code, or `none` with the terms you searched.
- `license`: the SPDX id the page or the GitHub API gives, or `unstated`, `non-commercial` or
  `all rights reserved`. Field is AGPL-3.0-or-later, so MIT, BSD, Apache-2.0, GPL-3.0 and AGPL code
  can be copied with attribution. For anything else, say "build from the method", and never reuse
  figures.
- **What Field adds** is the test of the whole card. If you can't write one concrete sentence the
  original page couldn't, drop it.
- **Figures** are the acceptance tests: name three to five specific figures, demos or results from
  the page that a Field version must reproduce from real code. For a bridge, name the examples or
  programs that must run.
- **Size**: a night, a week, or a project.
- For a bridge, the Method section says how Field would host it: GraalWasm (as with Faust), Python
  through JEP, a child process (Sonic Pi), OSC, RemoteJS, or native code through Panama or JNI.
  A runtime that fits none of these costs more; say so.
- Write plainly, in the voice of `dev_docs/agents/DOCS_WRITING_RULES.md`: no hype, no invented
  vocabulary, and no claims the page doesn't support.

Then run `python3 $REPO/tools/scout.py ingest-cards`. It validates every card for a fetched page
and records it. Fix every `FAULT` line and run it again until there are none.

## 6. Digest

`python3 $REPO/tools/scout.py digest`

Once seven days have passed since the last digest and at least three cards are waiting, it writes
`$JOURNAL/digests/<date>.md`. New ideas come first, then news touching work Field already has, each
card under its title with links, points, license and nearest Field work, and an empty `verdict:`
line for Marc. Name the digest's path in the report. Marc answers by editing the file (or asking an
agent to), and step 1 reads it the next day.

## 7. Report and commit

Write `$JOURNAL/reports/<date>.md`. Its first line must be the outcome, which `/routines.html` shows:

```
Outcome: 312 links · 104 read · 9 cards · 3 watching · digest written
```

Then: sources that failed, how many titles the pass dropped, the cards written (title and one
line), anything dropped that was close and why, watch-pool changes, and the digest's path if
one was written. End with anything this run learned that `PLAYBOOK.md` should say. Never edit the
playbook yourself; Marc lands playbook changes on `main`.

Commit the journal: `git -C $JOURNAL add -A && git -C $JOURNAL commit -m "scout: <date>"`. The
`.gitignore` keeps `pages/` out of the commit, so the ledger, cards, digests and reports are what
the branch remembers.
