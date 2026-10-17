# Backfill model identity into saved function_conversation chat turns

- **Handoff:** `4feaf27a-7b61-447d-a18c-5879af69166c` (project)
- **Filed from:** https://openendedgroup.com/field4/function_conversation/
- **Filed by:** human, 2026-10-15 13:49
- **Close with:** `python tools/docs_notes.py handoff-close 4feaf27a "what happened"`

Rule 25 sharp edge on `public/docs/function_conversation.md` (lines 126-127):

> The older turns predate model badges, so they show **Model not recorded**; the
> archive names the run's provider and model.

The saved chat shows "Model not recorded" although the run's provider and model are known
(gpt-6-astra / OpenAI, recorded in the archive). Backfill the model identity into the saved turns of
the function_conversation_example document (`aiChatHtml.kt` labels a turn by `turn.model`), or have
the chat fall back to a document-level recorded model, then recapture `saved-chat.png`. Delete the
sentence in the same change. The same probably applies to the StackSL/RectSL conversation
documents.
