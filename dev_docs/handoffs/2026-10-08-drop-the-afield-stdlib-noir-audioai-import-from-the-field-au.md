# Drop the afield.stdlib.noir.audioai import from the Field Audio AI fences

- **Handoff:** `d7af64e9-396d-4e47-bfd7-7cbb4a2c6398` (crosscutting)
- **Filed from:** https://openendedgroup.com/field4/field_audio_ai/
- **Filed by:** human, 2026-10-08 09:10
- **Close with:** `python tools/docs_notes.py handoff-close d7af64e9 "what happened"`

Every fence on `field_audio_ai.md` imports `afield.stdlib.noir.audioai.*` (rule 7: no `afield.stdlib.*` imports in box code), and the prose names the package too. Either make the audio-AI API resolve in a box without the import (default imports) or move it to a non-stdlib package, then update the fences and the sentence "The Field-side API is in `afield.stdlib.noir.audioai`". The sibling audio_ai_* pages likely share the import; check them in the same change. The fences are static and need the service, so no recapture.
