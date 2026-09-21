# Prose detectors for `public/docs`

Thirteen read-only analysers that measure the documentation tree's *voice*: the rhetorical
moves that make a page read as written-by-a-machine rather than as documentation. They were
built one at a time by the nightly `documentation-voice-pass`, each one added the night a
firing found a fault the existing set could not see.

None of them changes a file. Every one prints a board and exits 0.

## The one decision that is still open

Until this directory existed the detectors lived in `~/.claude/scheduled-tasks/`, where nothing
in the repo could run them. Committing them here fixes that much: they are now version
controlled, reviewable, and `tools/test_docs_voice_detectors.py` runs their self-tests inside
`checkPythonTools`, which `docsGates` and `check` already depend on.

What that test gates is **capability, not content** — that each detector still fires on the
faults it was built for. It does not assert anything about any page's score.

The open question is whether a *content* threshold should block a commit, and it is a real
question rather than an oversight:

- `score_voice.py --calibrate` is green today, but it measures Marc's own four pages, and the
  highest of them scores 8. A gate at zero is not reachable and never was.
- A gate at "no page above N" would have to be ratcheted, and a ratchet on a subjective
  measure is a standing invitation to edit prose to satisfy a regex — which is the exact
  failure the predecessor task (`documentation-language-improvement`) shipped thirty-five
  pages of before it was retired.
- Several nonzero scores are recorded, deliberate keeps: a heading that states a limitation, a
  figure caption baked into a committed PNG, a column header whose comma clause is a subject.

So the honest default is report-only, which is what this is. Raising any of these to a blocking
threshold is a call about the docs tree, not a call a test should make quietly.

## What each one measures

| script | surface | what it finds |
|---|---|---|
| `score_voice.py` | page prose, headings, admonition titles, bullet runs | the main board: heading faults, slide labels, plan language, methodology defences, oversized always-open admonitions, measurement mass, em-dash density |
| `table_cells.py` | markdown table cells | the same families inside a table, which `score_voice` routes past `prose` and never scores |
| `alt_text.py` | `alt="..."` in capture-fence openers | the same families in figure alt text, which is dropped with the fence opener line. Also reports alt coverage |
| `in_fences.py` | code comments and headings *inside* fences | prose a reader sees in a code sample. A fix here is three coupled edits, not a prose edit |
| `embedded_prose.py` | prose emitted by box code | strings a box prints into its own output panel |
| `fence_labels.py` | fence labels and notes | the same families in a figure's label |
| `moves.py` | whole-page shapes | one rhetorical move repeated once per section — invisible sentence by sentence |
| `self_narration.py` | page prose | a page describing its own drafting or reporting on the work of writing it, rather than describing its subject |
| `test_census.py` | page prose and tables | a page enumerating what its tests assert, which belongs in `dev_docs/<SUBJECT>_MEASUREMENTS.md` |
| `section_shape.py`, `shape_census.py` | page structure | how much of a page is verification rather than use |
| `check_anchors.py` | `](#slug)` links | in-page links to headings that no longer exist |
| `check_links.py` | relative links | targets that do not resolve |
| `check_fences.py` | Kotlin `diffstatic` fences | that no fence moved between two revisions. `tools/completion-corpus/rank-carets.tsv` indexes them **by ordinal** |
| `check_kdoc_mirror.py` | generated reference pages | that a page still matches the KDoc it mirrors |

## Running them

All take a docs root and default to `public/docs`:

```bash
python3 tools/docs_voice/score_voice.py public/docs
```

Three surfaces, matching `score_voice.collect()`, on the detectors that support them:

- default — hand-written pages
- `--generated` — reference pages. **The fix is in the KDoc**, not the `.md`; editing the page
  is reverted by the next `./gradlew generateCodebaseReference`
- `--assets` — hand-authored markdown under `public/docs/assets`

Two more modes worth knowing:

- `--calibrate` asserts that Marc's own four pages stay near zero. **If it fails, the detector
  is broken, not the page** — a detector that fires on the target voice is measuring the wrong
  thing.
- `--why <page>` prints the hit list for one page.
- `--live-fire`, on the detectors that have it, scores a sample built from faults Marc named
  directly, and asserts they fire. A detector that reports zero has to be shown able to report
  one; this is what the unit test runs.

## Reading a board

A score is a **targeting tool and not a verdict**. Every firing that has trusted one without
reading the hits has shipped a false positive. Recorded families that have produced keeps
rather than fixes are noted in the detectors' own docstrings, which are where the reasoning for
each pattern lives — `alt_text.py`'s explains why heading patterns are deliberately not applied
to alt text, after a first version that scored 216 points of pure false positive.

What no detector can see, and what still has to be read for:

- a page whose structure buries the API under the verification of the API
- prose that is fine sentence by sentence and unmistakable in bulk
- a caption that contradicts its own committed figure
- a number that a test prints and does not assert
