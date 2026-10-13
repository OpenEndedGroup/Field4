# Regenerate rank-carets.tsv after the morpho page split

- **Handoff:** `cc43b360-c59a-4d7f-8263-1bbd16c212c7` (crosscutting)
- **Filed from:** https://openendedgroup.com/field4/morpho_examples/; also affects morpho_search.md
- **Filed by:** human, 2026-10-11 22:34
- **Close with:** `python tools/docs_notes.py handoff-close cc43b360 "what happened"`

`tools/completion-corpus/rank-carets.tsv` pins each docs page's Kotlin fences by ordinal and by
their bytes up to the caret. On 2026-10-11 `morpho_examples.md` was reordered ("Using it" moved up)
and split (the search fences went to `morpho_search.md`, with their comments rewritten), so its
`morpho_examples#N` rows now point at the wrong fences, or at fences that are no longer on the page.

Regenerating with `python3 tools/scrape_completion_corpus.py --wide --out tools/completion-corpus/rank-carets.tsv`
rewrote 614 rows, mostly from edits other pages had made since the last scrape, so it was left out
of that branch. Regenerate it on its own branch, check that the churn is only re-harvesting, and
re-run `tools/record_rank_corpus.py` and `FieldSuggestPresentationTest` if the rank spec depends on it
(`dev_docs/completion_rank_at_scale.md`, "The pipeline"). No check reads the file, so nothing is red
in the meantime.
