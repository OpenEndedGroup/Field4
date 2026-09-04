# MIDI files — a document you can open

Open this folder in Field and run the four boxes:

```bash
./field.sh dev_docs/agents/demos/midi_clip
```

Boxes 1 and 2 pass a file between them through the temp directory, so run 1 before 2. Boxes 3 and 4
are independent of both.

| box | what it does | what to notice |
|---|---|---|
| **1 — Write a MIDI file** | Renders two mini-notation patterns into a two-track `MidiClip` and writes a format-1 Standard MIDI File | The roll is FLines, not a picture of one: it goes out through `Box.lines`' own SVG renderer, the same path any drawn figure in Field takes. The file it writes opens in any DAW. |
| **2 — Read it back** | Reads that file with no knowledge of what wrote it, and turns it back into a `PatPattern` | The PPQ, tempo, time signature, track split and track *names* all come out of the file itself. The roll here is drawn from the file, not from the clip box 1 held in memory. |
| **3 — What can this machine see?** | Enumerates every MIDI device the JVM reports | The JDK always contributes Gervill and the Real Time Sequencer, so a non-empty list is **not** evidence of hardware. On macOS the IAC bus appears twice under the *same* name, once per direction. |
| **4 — Hear it** | Auditions a chord, and reports which of two routes it took | It does not claim to have rendered audio when it has not. Field's JVM lacks the `--add-exports` that offline rendering needs, so this box will normally say so and point at `clip.playTo("Gervill")` instead. |

The surface these boxes exercise is documented in [`dev_docs/MIDI.md`](../../../MIDI.md), including
two things worth knowing before building on it:

- `patNoteNumber` uses **Tidal's** octave numbering, so `c5` is MIDI 60 — not scientific pitch
  notation's `C4`. Getting this wrong shifts everything an octave and still round-trips cleanly.
- Opening a MIDI **input** starts a non-daemon thread that outlives `close()`, so a JVM that has
  opened one will not exit on its own. Box 3 only enumerates; it does not open an input.

The Kotlin for each box lives in the `*_code_.kt` file beside `metadata.edn`. Field prefers those
loose files over the copies embedded in the EDN, and `./gradlew checkAgentDemos` compares the two
byte-for-byte — so edit the loose file, then run `./gradlew regenerateAgentDemos` to resync.
