# Publish the head-tracked patSpatial render as a binaural clip on pat_spatial.md

- **Handoff:** `497cb993-eb72-4b0a-9356-fcc06c272d21` (project)
- **Filed from:** https://openendedgroup.com/field4/pat_spatial/
- **Filed by:** human, 2026-10-07 09:13
- **Close with:** `python tools/docs_notes.py handoff-close 497cb993 "what happened"`

`pat_spatial.md` publishes only the mono source before spatialisation, and says the spatialised
output cannot be rendered offline. But `./gradlew headTrackedDemo` (build.gradle.kts) renders four
head-tracked movements of `patSpatial` (still, turning, stepping sideways, leaning in) to
`build/demo/head-tracked.wav`. A page about placing sound around a listener has nothing binaural to
listen to.

Work out how `headTrackedDemo` renders (true offline, or real-time capture), and if it can run in
the docs runner (libphonon via `fetchSteamAudioNatives` would be a docs native prerequisite),
publish its output as an `audio=` clip on `pat_spatial.md` under "Following a head", with a
provenance the DocsAudioProvenanceGuardTest accepts. If it cannot, correct whichever of the page's
"Why there is no clip of the output" section or the demo task is wrong. Never hand-record the clip.
