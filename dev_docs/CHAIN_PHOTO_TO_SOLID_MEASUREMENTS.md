# `chain.photo-to-solid` — the level-set reproducibility run, and how it was populated

Displaced from `public/docs/chain_photo_to_solid.md` by the seventeenth documentation-voice-pass
firing (2026-09-16). The page keeps what a user acts on — use `parallel = false` if a person is
going to read the numbers, and the warning that the parallel path can be *confidently* wrong rather
than visibly noisy. The population and the sameness criterion live here.

**Nothing below is asserted as a number.** The figure `chain-photo-levelset` is recaptured on every
docs build and its `parallel = true` column comes out different each time — that unreproducibility
is the claim, so there is nothing stable to pin. What the page does assert is the *shape*: the
`parallel = false` column reads `1/8` on every row.

## Population

- Depth maps of one photograph (`assets/vision/fline-viewer.jpg`), at two smoothing levels.
- Five regimes, selected by `floor`. Within a regime the field, the bounds and the edge length are
  identical; only `floor` varies, which erodes the plaque so that progressively nearer parts of the
  photograph survive as islands.
- Eight runs each way — sequential and parallel — for **80 marches** in total.
- Plus a **twelve-point component scan** to locate the regimes in the first place. The regimes are
  not round numbers; they are where the component count changes.

## What counts as two answers being the same

Two marches count as the same answer only if the triangle count, the component count *and* the
volume to twelve decimal places all agree. Volume alone would hide a retriangulation, and the
component count alone would hide a shell mis-classification.

## The intermediate-floor result that is not in the page's table

At one `floor` between the tabulated regimes, the parallel path returned **one component on all
eight runs** where the sequential path returned **two**. This is the reason the page warns about
confident wrongness rather than noise: eight-for-eight agreement on a wrong count is not
distinguishable from a correct answer by repetition alone.

## Open

- **Nothing guards any of this.** `PhotoToSolidChain` prints the table into the figure; no test
  asserts the `1/8` shape of the `parallel = false` column, so a regression that made the
  sequential path non-deterministic would change the figure and fail nothing. The cheap guard is
  the shape, not the values: assert that the sequential column is `1/8` on every row.
- `Solid.levelSet`'s KDoc records the original 46/47/48-component observation that prompted this
  run. It has not been re-dated to this measurement.
