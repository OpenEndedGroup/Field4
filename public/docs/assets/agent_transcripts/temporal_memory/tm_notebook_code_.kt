markdown(
    """
    # Continuing a gesture — lab notebook

    October 2026. Goal: remember a little performed movement, continue it for as long as you like, and stay
    responsive when someone takes over a channel. No training. Two backends behind one player: the Variable
    Markov Oracle (explicit memory) and Mistral's Timestral (a pretrained forecaster).

    **Boxes.** 1–3 are a dancer: six CMU mocap takes (subject 05, modern dance), the oracle's continuation as a
    strip of poses, the signal with the take each moment came from, and the dancer live. 4 is two oscillators
    with a slider to take over x. 5 needs Timestral installed (TimestralSession.install()) and draws a forecast
    fan. 6 plays the dance five ways, two of them judged by Timestral.

    ## Timestral alone

    - Fed back on itself, 28M fades to a tenth of the signal within minutes; 209M keeps a smooth figure-eight
      going for five minutes but loses an amplitude-modulated channel and settles into silence on bursty
      material. Replans: 28M ~30 ms, 209M 150–250 ms (p95 up to 660 ms); 114 channels: a few hundred ms.
    - Known-future covariates work: y = x one second late, x's future given, error on y halves (0.20 vs 0.44).
    - Quantiles are per step: drawn as a fan, never sampled as performances.

    ## The hybrid, and how we got it wrong first

    First fixture: three synthetic "phrase" takes built from four gestures. Timestral judging the oracle
    (prefer / veto) changed most decisions; repetition, measured as memory-position reuse, went up. Then Marc asked
    to see the played signal: both the plain and judged oracle were cycling rise → hesitate → burst → pause every
    6 s. The takes were copies of the same four gestures, so memory-position measures saw variety that the output
    did not have. Two lessons: measure the output (outputRepetition, now on Performance), and use real material.

    ## The dancer

    Real material broke the improviser three ways, each visible only on 114 channels:

    1. Thresholds summed over channels. A correction norm over 114 channels never passed maxCorrection = 1, so
       it froze (5122 holds in 5400 steps). Now every correction and cue distance is RMS per channel.
    2. Suffix links do not cross takes. 3 of 6 takes were never entered. Added bridges: nearest moments in
       other takes, as extra candidates with no shared context.
    3. Takes end alike. At a take's end the best matches were other endings, a loop around everyone's last
       seconds (coverage 0.34). Now: no jump with less than a phrase of runway; a take ends by beginning the
       least recently played other take within restartTolerance; holds last at most a phrase.

    Then: trimStill() for still lead-ins, and escapeReplays: while replaying something heard in the last
    reuseMemory seconds, consider a jump every step.

    | dance, 3 min from 61 s | coverage | repeats itself | holds |
    |---|---|---|---|
    | loop one take | 0.15 | 0.97 | 0 |
    | nearest history | 0.43–0.64 | 0.66–0.77 | 3–10 |
    | oracle, first try | 0.42 | 0.85 | 31 |
    | oracle now | 1.00 | 0.55–0.58 | 0 |

    A three-minute replay of 61 s must repeat about two thirds of its windows, so 0.55 is real recombination.

    **Timestral as judge on the dance (box 6, two minutes):** coverage unchanged, repetition 0.45–0.48 vs the
    oracle's 0.43, preferring gives slightly smoother joins and fewer jumps. It changes most decisions and moves
    the result a little. Not a new capability on this material.

    ## Open

    - The judge with known covariates: a performer's channel as Timestral's known future, not just its history.
    - Blind A/B viewing of oracle vs judged dance; the measures disagree at the margins.
    - Hard endpoints ("arrive here in 4 s"); tempo, offset and amplitude transforms of recalled passages.
    - More dancers: the bridges make a memory of several performers possible.

    Full report: dev_docs/TEMPORAL_MEMORY_EXPERIMENTS.md. Data: public/docs/assets/temporal_memory/cmu_05/README.md.
    """
        .trimIndent(),
)
