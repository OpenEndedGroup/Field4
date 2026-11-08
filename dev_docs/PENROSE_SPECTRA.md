# Penrose spectra (TilingGraph, GraphModes) — method and numbers

Code: `src/jvmMain/kotlin/afield/geometry/path/TilingSpectrum.kt` (TilingGraph, GraphSpectrum,
ZeroModeFit), `src/jvmMain/kotlin/afield/audio/modal/GraphModes.kt` (ModeSet, GraphModes,
springModes), `PenroseSpectraDocs.kt` (the page's three WAV scenes); test
`src/jvmTest/kotlin/afield/geometry/path/TilingSpectrumTest.kt`; page `public/docs/penrose_spectra.md`;
starting point `dev_docs/agents/transcripts/penrose_spectra.yaml`.

## The claim and where it comes from
- Vertex model, H = adjacency matrix (hopping t = 1 on every rhombus edge, zero on-site energy):
  Kohmoto & Sutherland, PRL 56, 2740 (1986).
- E = 0 states are strictly localised and make up at least 81 − 50τ ≈ 0.09830 of all states,
  conjectured exact: Arai, Tokihiro, Fujiwara & Kohmoto, PRB 38, 1621 (1988). Infinite tiling;
  finite patches have rim corrections.
- Same number as the monomer density of maximum dimer coverings: Flicker, Simon & Parameswaran,
  PRX 10, 011005 (2020). Day-Roberts, Fernandes & Kamenev, PRB 102, 064210 (2020) tie the zero
  modes to local sublattice imbalance.
- Not checked (search quota ran out 2026-11-08): Oktel, PRB 104, 014204 (2021) on Ammann–Beenker.

## Method
- Exact count: nullity of A = n − 2·rank(B), B the bipartite block, rank by row-echelon insertion
  mod 2³¹−1 and 10⁹+7 (max of the two). Vertices sorted by (x, y) keep rows short: 31,662
  vertices in ~0.1 s.
- Matching deficit (Hopcroft–Karp, iterative DFS) is the lower bound n − 2ν.
- Bulk/rim split: least-squares z = bulk·N + rim·√N over several radii (ZeroModeFit).
- Dense spectrum: EJML symmetric eig; 934 vertices 1.3 s, 1,629 vertices 8 s (loaded machine).
- confinedState(v): BFS ball S of h hops, null space of A[S ∪ ∂S, S] via eig of MᵀM, project e_v.
- Spring network: unit masses, unit edge springs, ground springs k − deg(v) ⇒ K = kI − A, ω² = k − E.
  Strike-and-listen at v: amplitude ψᵢ(v)², basis-independent within degenerate levels; the
  E = 0 share is the zero weight Σ_{E=0} ψᵢ(v)².

## Numbers (TilingSpectrumTest, 2026-11-08)
| case | result |
|---|---|
| Penrose (N, zeros) R=6,14,20,28,36 | (934,92) (4874,478) (9853,965) (19225,1879) (31662,3104) |
| bulk fit R=20..36 | 0.0983 (81−50τ = 0.09830) |
| bulk fit R=6..36 (all six) | 0.0978 |
| matching deficit = zeros | at R=4, 6, 14; up to 6 fewer at R=10, 20, 28 |
| square lattice disk radius R | exactly 2R+1 zeros, bulk −0.0004 |
| 3-fold (dice lattice) | bulk 0.330–0.335 (flat band 1/3) |
| disorder 0.02 / 0.05 / 0.1 / 0.2 / 0.8 | bulk 0.080 / 0.065 / 0.041 / 0.031 / 0.029 |
| offset sum 0 / 0.1 / 0.2 / 0.3 / 0.4 / 0.5 | 0.099 / 0.045 / 0.054 / 0.081 / 0.095 / 0.106 |
| symmetry 4,5,6,7,8,9,10,12 (bulk, ~14.5k vertices) | 0.085, 0.100, 0.055, 0.031, 0.030, 0.020, 0.026, 0.017 |
| 4-fold matching deficit | 0.009 → 0.004 (zeros not imbalance-forced) |
| gap around E=0, bulk-weighted | 0.168 (R=6), 0.161 (R=8); all states 0.106 / 0.092 |
| confinedState supports (R=6, 8) | 10–59 vertices; max abs(A·ψ) 1e-14 |
| zero weight by degree (R=6) | 2: 0.16, 3: 0.17, 4: 0.014, 5: 0.011, 6/7: 0 |
| strike share at E=0 | confined deg-3 vertex 0.315; deg-7 vertex 0; square centre 0.072 |
| ModalDrum, 934 modes | 1.9 ms per 512-frame block (budget 11.6 ms) |

Fits at symmetry ≥ 6 have residuals up to ~13 states over four sizes, so their third decimal is
not settled. A periodic approximant (no rim) would settle them.

## Notes
- `deBruijnTiling(symmetry = 4)` is Ammann–Beenker (four families at 45°: squares and 45°
  rhombi). `symmetry = 8` is eight families at 22.5° (four rhombus types), which
  `debruijn_tilings.md` labels Ammann–Beenker in its gallery title; that label looks wrong.
- K2 here rejected `IntArray.mapNotNull { map[it] }` with an ambiguity error; a plain loop works.
