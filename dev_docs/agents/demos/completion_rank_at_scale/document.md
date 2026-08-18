# Field Document: completion_rank_at_scale

A narrative mirror of `metadata.edn`, written beside it for code and AI tools. The canonical storage
format is EDN.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 3}
```

## 1. corpus — "1 - the corpus"

Reads `src/jvmTest/resources/fieldSuggest/rank-corpus.tsv.gz`: 578 completion carets recorded from the
code-use semantic sidecar's own `/complete`, 39 221 payload rows over 6 679 distinct ones. Reports
carets per caret form, how many are *contested* (send more rows than the popup's 80), and the receivers
people are really completing on. Plots payload size per caret into a `"plot"` channel.

## 2. classify — "2 - category + prior"

For one recorded caret, calls the real `KotlinSourceIndex.getCompletionCategoryForSymbol` with the
recorded `declaredIn` and the real `CommonMemberPrior.rankOf`, and marks which categories
`fieldSuggest.js`'s `CATEGORY_WEIGHT` scores above zero — the table's keys are parsed out of the shipped
file rather than copied. Change `RECEIVER` and re-run.

## 3. sweep — "3 - re-run the decision"

Runs `tools/rank_weight_lab.py`, which re-scores all 578 carets through the shipped `fieldSuggest.js`
with one ranking constant rewritten in a temp directory. Needs
`build/completion-corpus/rank-spec.json`, which `FieldSuggestPresentationTest` drops.
