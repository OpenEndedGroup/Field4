# visionOS pose/stream latency — what the report's figures are

Displaced from `public/docs/vision.md` on 2026-09-16. The page keeps the call to make
(`visionPoseLatencyReport()`), the empty readout a machine with no headset attached prints, which
two spans are deliberate costs, and the fact that no span has ever been measured against real
hardware. What is here is the fixture readout and the reading of it.

## The populated readout

Printed by the `visionpose.latency` smoke test, which drives a **synthetic head** with a known clock
offset and known one-way delays and needs no hardware:

```
vision pose/stream latency
  head clock = host clock +1234567.547 ms (best RTT 10.69 ms, 7 pongs)
  --- this machine ---
  pose uplink transit                    (no samples)
  pose age when latched                  (no samples)
  latch to frame submit                  p50    4.00  p95    4.00  max    8.00  n=2
  submit to encoded (incl. render fence) p50   11.00  p95   11.00  max   11.00  n=1
  --- the head ---
  head: jitter buffer hold (deliberate)  p50   33.40  p95   50.10  max   66.80  n=900
  head: POSE to texture (motion-to-photon lower bound) p50   72.50  p95   98.20  max  140.00  n=900
  note: the head's last observable moment is the texture upload, so any motion-to-photon figure above is short by up to one compositor frame.
```

Read that for its structure, not its magnitudes — a table like this invites being quoted as a
benchmark. Which figure is which:

- The **span values are fixtures.** `33.40`, `72.50`, `n=900`, the `4`/`8`/`11` ms host samples: the
  test writes those in, so they say nothing about how fast anything is. What they demonstrate is
  that a device-reported span survives the wire intact, that percentiles and counts land in the
  right columns, and that a *negative* span is dropped rather than counted as zero. `submit to
  encoded` shows `n=1` from two recorded samples, one of which was negative, and counting it would
  have pulled every percentile down with nothing to show why.
- The **clock offset is recovered, not written in.** The synthetic head is built at an offset of
  `+1234567.890 ms`; the estimator recovered `+1234567.547 ms` from seven round trips without ever
  being told the answer. It is the only measured figure in the table, and the first one to check,
  because a wrong offset does not fail; it produces a *plausible* latency. The smoke's own bound is
  a loose 20 ms: a sign slip would be out by twice the offset and a missing halving by the offset
  itself, and what fits inside that window is host scheduling noise on a shared machine.
- The **`best RTT 10.69 ms`** is against a synthetic path of two 5 ms crossings, with the head's own
  2 ms hold correctly excluded rather than charged to the network.

So the arithmetic is checked and nothing else is.

## Open

No span has been measured against real hardware. The two blockers are the same one: the device's
own spans stay empty on the visionOS simulator, because there is no video stream there to time, and
the motion-to-photon figure stays a lower bound until the present path moves off RealityKit onto
Compositor Services — the same change reprojection from the media wire's pose tail needs.
