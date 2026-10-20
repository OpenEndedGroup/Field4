# Make completion_rank_at_scale produce its own rank spec file

- **Handoff:** `1cb96e98-2c45-4313-8de4-95917f581ab9` (project)
- **Filed from:** https://openendedgroup.com/field4/agent_transcripts/
- **Filed by:** human, 2026-10-20 11:10
- **Close with:** `python tools/docs_notes.py handoff-close 1cb96e98 "what happened"`

The `completion_rank_at_scale` entry in public/docs/agent_transcripts.md carries this sharp edge (rule 25):

> The third box shells out to `tools/rank_weight_lab.py` and wants a spec file
> `afield.runtime.completion.FieldSuggestPresentationTest` drops; if it says so, run that test once.

The third box of dev_docs/agents/transcripts/completion_rank_at_scale.yaml depends on a file that only a jvmTest side effect writes. Fix in code: check the spec file in (or have the box/rank_weight_lab.py generate it from the recorded corpus), so the scene runs on a fresh checkout. Delete the sentence "and wants a spec file ... run that test once" from the page in the same change.
