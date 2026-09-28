# A RectSL scene on a light rig — a loadable demo

Open this directory as a Field document (copy it into your workspace, or
`./field.sh -d <path-to>/chain_scene_to_light_demo`). No lighting hardware is needed: the rig's
universes go over UDP to this machine, on port 35690, and come back. Boxes 1 and 2 use that fixed
port so they agree without any setup (change it in both if something else holds it); box 3 lets
the system pick a free port for its own round trip.

| box | action | what it shows |
| --- | --- | --- |
| **1. Scene on the rig** | launch | A RectSL scene drawn on the CPU (`RectSLImager`), shrunk to one pixel per fixture, mapped onto a 16 × 8 grid of RGB pixels and four RGBWA washes, and sent as sACN on the sender's 44 Hz clock. Draws the look it is sending. |
| **2. Receiver** | launch | Field as an sACN node on the same port. Draws the rig lit by what arrived (`showReceived`), and universe 1 in wire order. |
| **3. Measure** | run | The page's numbers on this machine: a round trip of nine frames, and a thin line sampled two ways. |

Change the scene in box 1 (the line's `0.25 ->wh`, a colour, a track) and launch it again; box 2
follows. Set `area = false` in box 1 to see the thin line flicker on the rig.

To drive real lights, give `sacnOut` a node's IP address and the standard port (`sacnOut("10.0.0.20")`),
or no host at all for multicast. The walk-through is `public/docs/chain_scene_to_light.md`.
