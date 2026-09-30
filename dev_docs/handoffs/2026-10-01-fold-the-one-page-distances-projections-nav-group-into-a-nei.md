# Fold the one-page 'Distances & Projections' nav group into a neighbour

- **Handoff:** `9e4cc0d4-5539-4a23-bdb6-8262dfa13ade` (crosscutting)
- **Filed from:** https://openendedgroup.com/field4/projection_examples/; also affects flow_matching.md
- **Filed by:** agent:claude, 2026-10-01 00:34
- **Close with:** `python tools/docs_notes.py handoff-close 9e4cc0d4 "what happened"`

## The notes that led here

- `89889ad1` (public/docs/projection_examples.md:1): Not sure this deserves it's own parent category
  > Projecting a distance matrix

**Asked.** On `projection_examples.md` the human wrote: "Not sure this deserves its own parent category." The page is the only entry in the nav group "Distances & Projections" (`public/zensical.yml:107-108`).

**Why it is bigger than a docs edit.** The fix is in the navigation (`public/zensical.yml`), which the page agent does not own, and it touches the group next to it. "Clouds & Deformations" (`public/zensical.yml:112-113`, only `flow_matching.md`) is also a one-page group, and it already has an open handoff: "Fold the one-page 'Clouds & Deformations' nav group into a neighbour" (`dev_docs/handoffs/2026-09-30-fold-the-one-page-clouds-deformations-nav-group-into-a-neigh.md`). The two should be decided together.

**Approach options.**
1. Merge both single-page groups into one group, e.g. "Shapes of Data" or "Geometry of Data", containing projection_examples.md and flow_matching.md. The yml comments at lines 105-106 and 109-111 already define each page by contrast with the other.
2. Move projection_examples.md into an existing data/analysis group (wherever `dataframe.md` / `chart.md` live), and deal with flow_matching separately under the existing handoff.
3. Leave it where it is and delete the justifying comment if the human decides the grouping is fine.

**Questions for the human.** Which group should it go in? Should this be handled together with the Clouds & Deformations handoff (recommended)?

**Page afterwards.** Nothing, since the page has no breadcrumbs or text that names its group. Update the yml comments at lines 105-111 and 235 to match wherever the pages end up.
