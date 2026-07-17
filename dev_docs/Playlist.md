# Playlist Feature

Executes boxes in ascending order of their `playlist` (Double) property, calling
`begin()` on each with a wait between boxes.

The authoritative documentation now lives as KDoc in
`src/jvmMain/kotlin/afield/playlist.kt`:

- `Box.playlist` — the ordering property (Double, persisted).
- `Box.pauseList` — per-box frame count: `executePlaylist` waits this many `frame()`
  calls (default 1, minimum 1) between boxes.
- `Box.findBoxesWithPlaylist()` — recursive search (skips `_tombstone` boxes).
- `Box.executePlaylist(strand)` / `Box.runPlaylist(strand)` — run the playlist.

Both `playlist` and `pauseList` are registered in `ioPersistentProperties`
(`Boxes.kt`), so they are saved with the box.

Runnable example: `src/jvmMain/kotlin/afield/examples/playlist_example.kt`.
