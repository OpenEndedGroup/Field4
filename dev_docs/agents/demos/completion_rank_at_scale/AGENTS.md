# You're in a Field workspace — this one is a measurement, not a picture

This folder is a **Field document**: `metadata.edn` lists the boxes, and each box's `code` lives
beside it in `<box>_code_.kt`. Field owns `metadata.edn` — drive Field rather than hand-editing it.

Three Kotlin boxes, none of which needs a window, a GPU or a running sidecar:

- `corpus` reads the checked-in recording of 578 completion carets and reports the population.
- `classify` calls `afield.ai.KotlinSourceIndex.getCompletionCategoryForSymbol` and
  `afield.CommonMemberPrior.rankOf` — the live server-side ranking inputs — over one caret's rows.
- `sweep` shells out to `tools/rank_weight_lab.py` to re-run the whole weighting experiment.

Each box's last expression is a `String`, which is what the output panel shows. `corpus` also plots
into a `"plot"` channel rather than `"output"`, because `display` REPLACES a channel and the report
owns `"output"`.

If you change a ranking constant, do not trust a reading of the code: run box 3, or
`python3 tools/rank_weight_lab.py`, which measures it over the corpus in about a minute. The numbers
this folder exists to defend, and the falsification that keeps them honest, are in
`dev_docs/completion_rank_at_scale.md`.
