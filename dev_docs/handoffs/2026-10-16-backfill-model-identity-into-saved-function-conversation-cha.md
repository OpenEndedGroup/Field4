# Backfill model identity into saved function_conversation chat turns

- **Handoff:** `a40d0ff4-235b-4b95-86a1-39a08854efab` (project)
- **Filed from:** https://openendedgroup.com/field4/function_conversation/
- **Filed by:** human, 2026-10-16 09:17
- **Close with:** `python tools/docs_notes.py handoff-close a40d0ff4 "what happened"`

Refiled from the code batch on 2026-10-16, not started. It edits recorded chat data (the saved turns' model field, from the archive's provider/model) and needs a recapture of saved-chat.png. The StackSL/RectSL conversation documents probably need the same.


Rule 25 sharp edge on `public/docs/function_conversation.md` (lines 126-127):

> The older turns predate model badges, so they show **Model not recorded**; the
> archive names the run's provider and model.

The saved chat shows "Model not recorded" although the run's provider and model are known
(gpt-6-astra / OpenAI, recorded in the archive). Backfill the model identity into the saved turns of
the function_conversation_example document (`aiChatHtml.kt` labels a turn by `turn.model`), or have
the chat fall back to a document-level recorded model, then recapture `saved-chat.png`. Delete the
sentence in the same change. The same probably applies to the StackSL/RectSL conversation
documents.
