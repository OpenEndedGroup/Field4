# Regenerate function_conversation downloads in current list-literal spelling

- **Handoff:** `ac8be367-9467-4eaa-b9b8-546d63f0c04d` (project)
- **Filed from:** https://openendedgroup.com/field4/function_conversation/
- **Filed by:** human, 2026-10-16 09:17
- **Close with:** `python tools/docs_notes.py handoff-close ac8be367 "what happened"`

Refiled from the code batch on 2026-10-16, not started. Regenerating the downloads and saved revisions changes revision hashes the page quotes (r9-82456e1e48ac etc.), so every quoted hash needs re-checking. That is a careful data pass rather than a code fix.


Rule 25 sharp edge on `public/docs/function_conversation.md` (lines 14-16):

> Kotlin listings on this page use Field's current `[…]` spelling for single-line
> list constructors. Saved conversations, source revisions and downloadable documents
> retain their recorded spelling; this presentation update is not a new execution.

The page and its downloads disagree about the source text, so the page has to apologise. Fix it in
data: either regenerate the downloadable documents (`function_conversation.zip`,
`function_conversation_example.zip`, `final.kt`) and the saved revision sources with the current
collection-literal spelling (recheck each revision, since hashes such as `r9-82456e1e48ac` are
quoted on the page), or render the page's listings from the recorded bytes. Delete the paragraph in
the same change.
