# WebRTC latency measurements

Moved from `public/docs/webrtc.md` ("How fast it is") on 2026-10-21; the page keeps the round figure.

On one Apple silicon Mac, through a local aaaframe server, the time from Field rendering a frame to
the receiving page presenting it was 46 ms at the median, 63 ms at the 90th percentile and 71 ms at
the 99th, while the machine was moderately busy. Under heavy load it rose to 59 ms median and 106 ms
at the 90th percentile. The stream is captured at up to 30 frames a second, so a frame can wait up
to 33 ms before it is encoded.

These numbers are printed by the harness, not asserted by any test.

## Method

The harness is `dev_docs/webrtc_latency/`: a sink draws its wall clock as a 24-bit bar code, and a
source with `measure = true` decodes every presented frame with `requestVideoFrameCallback` and
compares clocks. The browser end was driven with `dev_docs/webrtc_latency/shadertrinket_driver.py`.
The figures on the page are screenshots of those runs, not docs-runner captures, because they need
two running peers.
