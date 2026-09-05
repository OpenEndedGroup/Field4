# Evolution comes to ALife — a document you can open

Open `metadata.edn` in Field (**File ▸ Open**, or `./field.sh <path to this folder>/metadata.edn`)
and run the four boxes. No GPU, no window, no `Renderer`: everything here is CPU Kotlin, and the
slowest box takes about ten seconds.

Field could always *measure* artificial life. `alife.kt` is 816 lines of morphology statistics —
`polarization`, `blobShapes`, `correlationLength`, `massRadiusDimension`, `stateEntropyBits`,
`translationBetween` — and before 2026-09-05 not one of them had ever been used as a fitness.
`git grep -inE 'fitness|genome|evolve|mutation' -- 'src/jvmMain/kotlin/afield/stdlib/alife*.kt'`
returned nothing at all. This document is the other half.

| box | what it does | what to notice |
| --- | --- | --- |
| **1 — a Lenia rule** | evolves `mu`, `sigma` and the kernel's shell weights for a soliton: alive, in one piece, and travelling | the first panel is the **random control's** best at the same budget under the same seed. The lineage that follows is a small dense blob becoming a large travelling ring. Margin +17% |
| **2 — a life-like rule table** | the same engine over a completely different genome: 49 bits of birth and survival counts, with real uniform crossover, under lexicase selection | the radius-2 neighbourhood is the point. At radius 1 the space is 2^17 and a few hundred *independent* draws land near the optimum on their own — the first version of this demo measured evolution winning by 3% for exactly that reason. Margin +27% |
| **3 — MAP-Elites** | keeps the best rule per cell of a `(density, activity)` archive instead of searching for one winner | the contact sheet is the figure worth looking at: sparse and nearly dead at top left, dense and noisy at bottom right, and a labyrinth rule in between. And read the box's own caveat — MAP-Elites **loses** raw coverage to random sampling here and wins on quality per cell |
| **4 — the engine is shared** | the same core running `FunctionEvolve.evolve` over an `Fn` graph | `FunctionEvolve` no longer contains a search. Its body is `PopulationSearch.evolve(mu = 1, …)`, and the FIELD_TEST `functions.evolve.worked-example` reproduces `bestScore = -0.15676727177844857` **bit for bit** from before the refactor |

Box 1 is the one to run first. Box 2 is the one that teaches the most, because the interesting
finding there is not that evolution won — it is *how much the answer depends on the problem being
hard*. Change `LifeRuleGenome(radius = 2)` to `LifeRuleGenome()` and watch the margin collapse to
almost nothing. A control arm that nearly wins is telling you the problem is easy, not that the
search is good.

The engine is `src/jvmMain/kotlin/afield/stdlib/PopulationSearch.kt`
([reference](../../../../public/docs/reference/search.md)); the genomes and fitnesses are
`src/jvmMain/kotlin/afield/stdlib/alife_evolve.kt`
([reference](../../../../public/docs/reference/alife.md)). The measurements behind every number
above are in `src/jvmTest/kotlin/afield/stdlib/PopulationSearchTest.kt` — a bit-string genome and
OneMax, where the optimum and the expected best of *n* draws are closed forms — and in the
FIELD_TEST `stdlib.alife.evolve.gallery`
(`src/jvmMain/kotlin/afield/examples/stdlib/AlifeEvolveGallery.kt`), 52 checks, which asserts every
margin as a **floor** and asserts its own premise first.

The loose `*_code_.kt` files are what Field loads; `metadata.edn` carries portable inline copies of
the same text, and `./gradlew checkAgentDemos` fails if the two ever disagree.
