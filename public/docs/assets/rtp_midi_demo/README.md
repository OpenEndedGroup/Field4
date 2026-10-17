# RTP-MIDI in Field — a loadable demo

Open this directory as a Field document (`./field.sh -d <path-to>/rtp_midi_demo`) and run the boxes
in order. Box 1 opens a network MIDI session called "Field" on UDP 5004/5005 and announces it over
Bonjour; boxes 2 and 3 open a second session on 5006 that joins it, so nothing else needs to run.
On a Mac, *Audio MIDI Setup → MIDI Studio → Network* lists "Field" too.

| box | action | what it shows |
| --- | --- | --- |
| **1. Open the session** | run first | `rtpMidi("Field")`, with `onNote` and `onControl` writing the last note and CC. |
| **2. A peer joins** | run | a second session invites the first; its peer list shows the round trip from the clock exchange. |
| **3. Play into it** | run | four notes and a CC sent from the peer; box 1's outputs update. |

The page is `public/docs/rtp_midi.md`.
