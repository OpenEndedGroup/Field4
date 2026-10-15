# Add a timed bundle send to OscClient

- **Handoff:** `65665eb1-3c5d-4c51-b924-ba358af62be6` (project)
- **Filed from:** https://openendedgroup.com/field4/osc/
- **Filed by:** human, 2026-10-15 13:46
- **Close with:** `python tools/docs_notes.py handoff-close 65665eb1 "what happened"`

Rule 25 sharp edge on `public/docs/osc.md`, "Bundles and timetags":

> `timetagIn` builds the timetag from raw NTP seconds on purpose: JavaOSC's own conversion from a `Date` counts from an epoch an hour away from the true one.

The figure's code also has to drop to raw JavaOSC (`OSCPortOutBuilder`, `OSCBundle`) because `OscClient` "sends single messages". Fix in code: give `OscClient` a `sendBundle(atMillis/inMillis, messages)` (or `sendAt`) that builds a correct timetag, so no box touches JavaOSC's broken `Date` conversion. Then rewrite the fence to use it (fence rewrite) and delete the sentence above and "OscClient sends single messages. To send a bundle, build one with JavaOSC" in the same change.
