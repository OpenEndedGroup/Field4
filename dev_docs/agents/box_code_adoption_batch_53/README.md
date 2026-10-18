# Release Csound listeners and held notes with their controlling run

Frozen findings: `7a500644ebfe-01`, `7a500644ebfe-02`, `ded1d6a3cafb-01`.

Own the two native Csound outvalue listener handles in the controlling box run. Pair held MIDI noteOn/noteOff with finally so Stop during delay releases the note. Apply listener ownership to the documentation example as well.

Actual import/ktfmt, collection conversion and token/boundary checks cover 2 selected Kotlin transcript bodies and 7 documentation states. The original Csound orchestra/score and unrelated transcript bodies remain byte-for-byte unchanged.

Focused behavioral evidence uses the real native Csound library and the authored orchestra, rendered offline. It checks old/new listener delivery across whole relaunch, removal on Stop while the orchestra continues, the docs registration, and a real active native MIDI note returning to zero after Stop and its release envelope. The fixture extends the held delay to ensure cancellation occurs during the note; no audible playback is claimed.

The browser listener investigation is retained as failed diagnostic evidence only. Its source change was withdrawn and finding 235cc7aef1f3-01 remains open: actual Chromium console events exposed obsolete callbacks after relaunch, and a minimal cleanup probe logged a preamble-class ClassCastException. The primary checkout's matching Electron 44.5.1 binary was temporarily linked for the check, using this checkout's producer; that link was removed. The real isolated browser has been closed separately.

Checkpoint: 336/405 addressed, 69 open. Corpus-wide mechanical coverage remains incomplete. Full guards are delegated to the lander.
