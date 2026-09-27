# Field drives light — a loadable demo

Open this directory as a Field document (copy it into your workspace, or
`./field.sh -d <path-to>/dmx_lighting_demo`). Four boxes. No lighting hardware is needed: everything
goes over UDP to this machine and comes back.

| box | action | what it shows |
| --- | --- | --- |
| **1. Art-Net receiver** | launch first | Field as an Art-Net node on UDP 6454. Draws universes 1 and 2 as they arrive, one cell per RGB pixel. |
| **2. Patch + pixel mapper** | launch | 200 RGB pixels of LED tape along a curve, coloured by a function of (x, y, t) on the sender's 44 Hz clock and sent as Art-Net. 170 pixels fit in universe 1; the other 30 roll into universe 2, which box 1 shows as a part-filled second grid. |
| **3. ArtPoll** | run | Art-Net discovery. With box 1 running, Field answers its own poll as a node. |
| **4. sACN matrix** | launch | An image on a 16 × 8 matrix of RGBW fixtures, sent as E1.31 and received again in the same box. The received universe is drawn as RGBW fixtures in wire order, so every other row runs backwards — the matrix is wired serpentine. |

Stopping a box closes its sockets (and an sACN sender says goodbye with Stream_Terminated).

To drive real lights, change `"127.0.0.1"` in box 2 to a node's IP address, or to your network's
broadcast address; for sACN, `sacnOut()` with no host sends multicast. Box 1 needs UDP 6454 free, so
quit other Art-Net software on this machine first (or give `artnetIn(port = ...)` another port and
`artnetOut(port = ...)` the same one).

The API is described in `public/docs/dmx_lighting.md`; how it was verified, including independent
decoders, in `dev_docs/dmx_lighting.md`.
