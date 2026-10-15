# Unify log-channel matching across mute, pin and LogStream filter

- **Handoff:** `6e0d34a7-aa36-4f99-96b2-129e9153f7a4` (project)
- **Filed from:** https://openendedgroup.com/field4/logging/
- **Filed by:** human, 2026-10-15 13:51
- **Close with:** `python tools/docs_notes.py handoff-close 6e0d34a7 "what happened"`

Rule 25 sharp edge. The section "Channel matching: three mechanisms, three rules" exists because ConsoleMute matches whole strings case-sensitively, the pinned console matches tags, and `LogStream.Filter.channels` matches case-insensitive substrings, so a `kotlin` filter also catches `kotlinjs` (123 such collisions counted). The page advises a workaround: "filter on the full channel string (`\"info,kotlin\"`) rather than the bare tag."

Decide one rule (tag membership is the obvious one) and apply it in `afield/app/server/logstream.kt` and `ConsoleMute` (allow muting a tag), keeping substring as an explicit opt-in if wanted. Then delete the workaround paragraph and shrink the three-rules section in the same change. May need a decision from Marc first.
