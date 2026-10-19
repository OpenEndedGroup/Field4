markdown("""
# Continuing a gesture — lab notebook

October 2026. What this document is for: remember a few seconds of performed behaviour, continue it,
and stay responsive when someone takes over a channel. No training. Two backends, one player.

**Boxes.** 1 records three phrase takes and builds the oracle. 2 recombines them and shows which
take each moment came from. 3 is live: tick "take over x" and drag. 4 needs Timestral installed
and draws a forecast as a quantile fan. 5 compares four ways of playing the same memory.

## What we measured (5-minute performances, 20 Hz, seed 1)

- **Oracle (VMO).** Holds the demo's spread, frequency and speed on every fixture; uses 92–98% of
  the memory. Every sample comes from the recording, and `segments` says why it jumped.
- **Timestral alone, fed back on itself.** 28M fades to a tenth of the signal within minutes.
  209M keeps a smooth figure-eight going for all five minutes, but loses an amplitude-modulated
  channel and settles into silence on the phrases. Replanning: 28M about 30 ms, 209M 150–250 ms
  (p95 up to 660 ms).
- **Known-future covariate.** y = x one second late; giving Timestral x's future halves the error on
  y (0.20 vs 0.44 mean absolute error). The most useful steering control we found.

## The hybrid, three tries

| phrases, 5 min | reuse | coverage | join roughness | jumps |
|---|---|---|---|---|
| oracle alone | 0.25 | 0.98 | 0.89 | 250 |
| judge prefers (28M) | 0.45 | 0.87 | 0.69 | 269 |
| judge vetoes at 1.2× | 0.54 | 0.74 | 0.89 | 258 |
| oracle, reuseWeight 6 | 0.20 | 0.92 | 0.60 | 360 |
| judge prefers + reuseWeight 6 | 0.22 | 0.97 | 0.63 | 267 |

1. *Prefer.* The judge changed 55–70% of decisions. Joins got smoother, but repetition nearly
   doubled: a pinball-loss judge rewards the predictable continuation, which on phrases is what
   just played.
2. *Veto.* Expected it to remove bad joins without the pull. It vetoed most jumps on the phrases and
   repetition got worse still. On the figure-eight it vetoed almost nothing: Timestral agrees with
   every candidate on smooth periodic material.
3. *Prefer + a stronger reuse penalty.* Smooth joins and low reuse together. But the plain oracle
   with the same penalty gets nearly there on its own, with more, shorter jumps. Honest summary: on
   these fixtures the judge buys longer phrases at the same smoothness, not a new capability.

The 209M judge gave the same picture as 28M at five to ten times the cost.

**Then box 5, three minutes instead of five, same seed:** prefer + reuseWeight 6 came out at
reuse 0.50, coverage 0.64, join roughness 0.40, where the 5-minute run said 0.22 / 0.97 / 0.63.
So the "reuse penalty cancels the pull" result does not hold up across lengths: judged runs
vary much more than oracle-only ones (0.26 / 0.93 / 0.87 here vs 0.25 / 0.98 / 0.89).
What does hold every time: the judge makes joins smoother and costs variety on phrase
material. Next experiment should be several seeds × lengths per setting, not one run.

**Then Marc asked to see the played signal, not just memory position.** Both the plain and the
judged oracle play rise → hesitation → burst → pause about every 6 s, near-identically. The
memory trace looked varied only because the takes are copies of the same four gestures.
`reuse` counts source positions, so a loop made of copies passes it. Two consequences: we need
an output-side repetition measure (matrix profile / autocorrelation of the performance), and
this fixture is too poor to say anything about recombination. Next: takes that really differ.

## Open

- A measure of "better dynamics" that is neither the judge's own score nor a statistic the oracle
  already matches. Blind A/B listening or viewing is probably the honest one.
- Timestral steering through known covariates inside the hybrid: a performer's channel as a known
  future, not just as history.
- Takeover is realigned by matching history; a hard endpoint ("arrive here in 4 s") is not supported.
- Tempo, offset and amplitude transforms of recalled passages; the oracle plays them as recorded.

Full report: dev_docs/TEMPORAL_MEMORY_EXPERIMENTS.md. Page: Continuing a Gesture.
""".trimIndent())
