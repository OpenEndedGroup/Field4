/*
The other direction: Field's clock is the sequencer, Sonic Pi is the instrument.

One /run-code per event instead of a live_loop. Slightly less accurate — a UDP hop of jitter per
note, measured at a few milliseconds — and completely live: edit the pattern below, re-run, and
the next event goes out changed, with no live_loop to redefine.

The thing a compiled loop cannot do is on the last line: a continuous signal sampled once per
note, so the hats breathe.
*/

(pat("bd*4, [~ cp]*2, hh*8") with gain(sine().range(0.15, 0.5))).sonicPiLive(cps = 0.5)
