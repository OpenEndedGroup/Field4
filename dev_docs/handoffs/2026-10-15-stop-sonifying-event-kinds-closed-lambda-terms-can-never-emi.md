# Stop sonifying event kinds closed lambda terms can never emit

- **Handoff:** `f76a28ed-0160-4060-b323-99217e3ad1af` (project)
- **Filed from:** https://openendedgroup.com/field4/chain_lambda_sound/
- **Filed by:** human, 2026-10-15 13:42
- **Close with:** `python tools/docs_notes.py handoff-close f76a28ed "what happened"`

Rule 25 sharp edge on `public/docs/chain_lambda_sound.md`, note "Two of the six degrees are silent on every closed term":

> ... So the whole corpus only ever reaches four of the six kinds, and `LambdaSoundDocs.KINDS_UNREACHABLE_ON_CLOSED_TERMS` names the other two.

The page maps six event kinds to six pitches, then explains that two can never sound. Fix in code/data: either make the sonification map only the kinds a closed term can emit (four degrees), or give the corpus a term with a free variable/constant so all six sound, and drop `KINDS_UNREACHABLE_ON_CLOSED_TERMS` as a documented exception. Delete the note in the same change (the "six degrees" fence and capture are byte-pinned, so this includes a fence edit and recapture).
