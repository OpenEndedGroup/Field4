# `patCondense` — the shortest way to write a rhythm down

Open this folder in Field and run the three boxes:

```bash
./field.sh dev_docs/agents/demos/pattern_condense --read-only
```

`--read-only` keeps the demo out of your `git status`: this folder is committed, and Field writes
back into the folder a document came from. Drop the flag when you mean to edit the demo itself.

The three boxes are independent; run them in any order.

| box | what it does | what to notice |
|---|---|---|
| **1 — Condense a rhythm** | Condenses four rhythms and draws each against its condensation | The lower lane of a pair *is* the string in its label, played. `patCondense` accepts a candidate only by re-parsing it through `pat(...)` and comparing events, so if a pair ever fails to coincide the search is wrong. |
| **2 — Invent nothing** | Condenses 300 random masks and recomputes every figure the search named | The count of wrong namings is 0, and it is checked here from `bjorklund` / `kolakoskiMask` rather than believed off the label. Noise compresses about three times worse than a real figure; that separation is the claim. |
| **3 — Close the loop** | Renders a bar to samples, transcribes the samples, then condenses what was heard | Nothing between the render and the transcription looks at the pattern. `patTranscribe` gets back `60 ~ ~ 60 ~ 60 ~ ~`, and `patCondense` turns that into `60(3,8)`. |

## What to change

Each box names one value worth poking, at the top of its code.

- **Box 1, `rhythms`.** Add a line. Anything you invent comes back either as a named figure — `x(p,s)`
  for Euclidean, `x(kN)` / `x(gN)` for Kolakoski — or spelled out one slot per step. Which one you get
  is decided by character count and nothing else, so `x ~ ~ x ~ x ~ ~` is `x(3,8)` and
  `x ~ ~ x ~ ~ x ~` is `x(3,8,5)`, the same figure turned.
- **Box 2, `seed` and `density`.** `density` is "one step in N is on", so 2 is dense and 6 is sparse.
  The *wrong* count has to stay 0 whatever you set. The *named* count will move: sparse masks are more
  often a real Euclidean figure than dense ones.
- **Box 3, `bar`.** Any eight-step bar at MIDI 60 will be heard. `60 ~ ~ 60 60 ~ 60 ~` comes back as
  `60(k8)`; `60 60 ~ 60 ~ ~ 60 ~` has no single figure and comes back as two halves,
  `60(g4) 60(1,4,2)`.

## Where the mechanism is

`src/jvmMain/kotlin/afield/four/synth/PatCondense.kt` — the search, its candidate grammar, and the
four bounds it runs under (`PatCondensation.limits` prints which of them a run used).
`src/jvmTest/kotlin/afield/four/synth/PatCondenseTest.kt` carries the measurements, and
`public/docs/pattern_language.md` has the reader-facing section.

Two things the search will not do, and says so instead of guessing. A pattern that changes at random
from cycle to cycle has no period to condense. A pattern carrying parameters no atom can spell — the
`pan` that `jux` writes, the `begin`/`end` that `chop` writes — would lose them. In both cases
`PatCondensation.exact` is false and `refusal` says which.
