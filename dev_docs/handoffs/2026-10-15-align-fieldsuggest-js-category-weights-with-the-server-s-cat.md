# Align fieldSuggest.js category weights with the server's category vocabulary

- **Handoff:** `dbf0117a-65e2-4fba-8132-6664dc23572a` (project)
- **Filed from:** https://openendedgroup.com/field4/completion_ranking/
- **Filed by:** human, 2026-10-15 13:50
- **Close with:** `python tools/docs_notes.py handoff-close dbf0117a "what happened"`

Rule 25 sharp edge on public/docs/completion_ranking.md, "When a row is not where you expect", item 6:

> **The category weights predate the server's category vocabulary.** Most of the categories the Kotlin side sends score nothing at all. That is a known defect; a characterization test pins the current behaviour so that fixing it shows up as a change.

Fix: align the category weight table in `src/commonMain/resources/fieldSuggest.js` with the category names `KotlinSourceIndex.getCompletionCategoryForSymbol` actually emits (ideally generate one from the other, or assert both sides agree as BASE/STEP already do), update the characterization test, and delete item 6 from the page in the same change.
