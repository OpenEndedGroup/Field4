# Drop --workspace from the attached backdoor fence in profiling_timeline.md

- **Handoff:** `58e52dde-548a-4e35-a5dd-458a8731ea88` (project)
- **Filed from:** https://openendedgroup.com/field4/profiling_timeline/
- **Filed by:** human, 2026-10-03 09:11
- **Close with:** `python tools/docs_notes.py handoff-close 58e52dde "what happened"`

The "Try it" fence near the end of `public/docs/profiling_timeline.md` (the frame-budget half) runs
`field_backdoor.py --attach-existing ... --workspace /path/to/workspace`. When the backdoor attaches,
a `--workspace` that disagrees with the server's is an error (AGENTS.md section 4), and the "Try the
demo" section near the top of the same page now tells readers not to pass one. The two fences also
repeat each other.

Do: drop `--workspace` from that fence (or delete the fence and point to the "Try the demo" command),
then regenerate whatever pins its bytes (`tools/completion-corpus/rank-carets.tsv`, if listed).
Also consider whether both backdoor fences belong on a user page at all; automating_field.md covers
the backdoor.
