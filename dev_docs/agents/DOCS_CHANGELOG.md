# Writing the "What's new" page

`public/docs/changelog.md` is the public changelog: what someone who uses Field, or reads its
docs, would want to know has changed. It is linked from the front page. It is written from the
history of `main` with `tools/docs_changelog.py`, which does the bookkeeping and never writes
prose.

## The loop

```bash
python3 tools/docs_changelog.py status      # how far behind main the page is
python3 tools/docs_changelog.py brief       # the next window of ~10 landings, ready to write up
python3 tools/docs_changelog.py check       # structure, ranges and links; exit 1 on a fault
```

`brief` prints, for the next window: the marker line to paste, each landing on `main` with the
commits it merged, the docs pages it added, renamed, deleted or rewrote (with line counts and
their *current* names), and the source areas with the most churn. Write one entry from it (or
fold it into the entry above, or split it in two), put it at the top of the right month, and run
`check`. `brief --windows 5` prints five windows at once for a catch-up. Draft a batch in a
separate file and check it with `check --file draft.md --from <first sha of the batch>`.

The page should be brought up to date whenever it is a week or so behind; `status` says.

## The rules `check` enforces

- Each entry opens with `<!-- changelog: <first>..<last> -->`, naming the first and last
  first-parent commits of `main` it covers (the `brief` marker line). The markers run newest
  first and tile `main`'s history from its root with no gap and no overlap. A stretch with nothing
  worth telling a reader about still belongs to an entry: widen the neighbouring entry's range
  over it. That is what makes "covered" mean something.
- After the marker comes a `### ` heading, and the entry sits under the `## Month YYYY` heading of
  its last landing's date.
- Each entry links at least one docs page, every `.md` link names a page under `public/docs`, and
  every `#fragment` is a heading on that page.

## What goes in an entry

Shape:

```markdown
<!-- changelog: 2a8c5fe673..46e37dc010 -->
### Screenshots at the size Field draws them

*3–4 November 2026*

Captures on every page now display at 1.5× the size Field drew them, so text in a screenshot
reads at the same size as the text around it, on any screen. [Box output](box_output.md) and
the [Tour](funFeatures.md) show the difference most.

- **Moving drawing starter**: ... [The first drawing](first_steps.md#...).
```

- **The heading names the biggest change in plain words**, sentence case, as a reader would
  search for it: "Notation you can read from code", not "Notation improvements" and not a branch
  name. One heading per entry.
- **A date line** in italics: the day, or the span of days, of the landings covered.
- **Then a sentence or two, or two to five bullets, each leading with the thing.** Say what a
  person can now do, see or read that they could not before, and link the page where it is
  described: a page that is new, a page that was rewritten, or the section of an existing page
  that documents the feature (link `page.md#heading`). Prefer the section anchor when the page is
  long. Around 40–120 words an entry.
- **New and rewritten pages are news.** A page the brief marks `NEW` or `REWRITTEN` gets a
  mention unless the rewrite was mechanical (a rename, reformatting, regenerated reference).
- **Fold, don't pad.** A window of prose polish, capture fixes, CI and guards becomes one clause
  in a neighbouring entry ("and the Physics and OSC pages were tightened") or nothing; widen the
  neighbour's range over it. A window with two serious features can become two entries.
- **Link what exists now.** `brief` shows each page's current name; a page that is `[gone now]`
  is not linked; link its successor if there is one. Before linking a section, find the heading
  (`grep -n '^#' public/docs/page.md`).

## How it is displayed

Write the entry as plain markdown; the docs build passes the page through
`docs_changelog.render`. That puts the italic date line and a GitHub link to the entry's last
commit (once it is on GitHub) on the right of the heading, and puts a crop of the first picture
on the first linked page (from the linked section onward, when the link has an anchor) behind
the heading row. Of five candidate crops, the one with the highest entropy wins. An entry whose
links reach no picture gets none. To choose the picture, put
`<!-- changelog-image: assets/some/capture.png -->` in the entry; `<!-- changelog-image: none -->`
drops it. Only pictures that already exist in `public/docs/assets` qualify. Never make one for
the changelog.

## What to leave out

Leave out, always: commit hashes, branch names, the lander, nightly or agent runs, worktrees,
guards, tests, refactors, internal tools, docs-build machinery, and anything a reader never
touches. A build or install change counts when the reader would notice it (a new platform, a
faster start, a launcher flag). Follow `DOCS_WRITING_RULES.md` for voice: no lab-notebook
narration, no invented vocabulary, no superlatives the commits do not support. Never claim more
than the commits say; when unsure what a feature does, read the page that documents it.
