# Reclaim or reap an abandoned Sonic Pi daemon at boot

- **Handoff:** `3c197348-da7d-45c3-bbb7-9b9d7edcb08e` (project)
- **Filed from:** https://openendedgroup.com/field4/sonic_pi/
- **Filed by:** human, 2026-10-15 13:45
- **Close with:** `python tools/docs_notes.py handoff-close 3c197348 "what happened"`

public/docs/sonic_pi.md ("Ports, tokens and shutting down") says:

> Until then the abandoned daemon holds the cue port, so the next boot gets a random one. If
> `oscCues` is not 4560, look for a leftover Sonic Pi process.

This is a sharp edge (DOCS_WRITING_RULES rule 25): the user is asked to diagnose a leftover process by hand. Fix in code: when owned-mode boot finds the cue port taken by a Sonic Pi daemon that Field itself started earlier (e.g. record its PID under ~/.cache/field4/sonic-pi/ and check it at boot), send it /daemon/exit or terminate it before booting, or at least report the leftover PID in the box. Delete the paragraph in the same change.
