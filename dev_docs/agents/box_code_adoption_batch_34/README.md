# Runnable chart and geometry examples, escaped tables, shared search ordering

Baseline `76953386d`. Four frozen findings addressed:

- `457ac432cf3a-01`: showChart reads the people frame. Actual fresh-box execution exposed two more faults: the missing DataFrame API import and asking to plot against the only numeric column with no numeric series left. The snippet now imports its API and demonstrates explicit name/age axes. Its actual SVG capture shows Alice/Bob/Cara at 15/30/45.
- `6f418564df96-01`: the search-root snippet qualifies java.io.File. The real two-line snippet runs twice against a disposable existing scans directory; the canonical root is registered exactly once. Only the private test host's user.home is temporarily redirected, restored in finally.
- `12a95c86b5aa-02`: parser/refusal table messages use .md. Actual parser cases and all four real hole refusals run, and their browser captures are inspected. A separate real Markdown table includes a pipe, newline and emphasis punctuation in its computed message; the inspected output keeps one literal message cell. This adversarial message tests the renderer and is not a replacement for the real parser/refusal results.
- `e86d8ebef215-01`: unsolved search fallback uses MorphoIndividual.BY_QUALITY. Source inspection establishes validity/correctness/error/gates/depth/brevity ordering. Eight real seeded 64-evaluation searches provide unsolved results: the shared comparator selects a better candidate than the deliberately worst-first initial entry. These results tie on error; the verified choice follows the later quality tie-breakers. Correct-solution minimum-gate selection is unchanged.

Actual import/ktfmt formatting covers 45 Kotlin states across all four pages (eight chart, nine geometry, twenty-five polytope, three Morpho). Mechanical preservation checks cover every state and its surrounding prose/folds. No additional literal conversions occur; the collection/idiom audit is clean across all four files. No GLSL is edited. Runtime coverage is the five edited examples/tables and one adversarial renderer case, not all 45 states.

The rehearsal's initial eight-evaluation search assumed varied errors; actual results disproved that assumption. The final test uses real 64-evaluation candidates and the shared comparator's complete ordering. Earlier compilation/runtime failures remain in the evidence archive with final successful results. Actual private browser captures are inspected; no published capture asset is replaced.

Reusable formatting, token/container preservation and live rehearsal harnesses are included. evidence.tar.gz records the successful runtime results, prior diagnostics and actual images. The corpus sweep remains incomplete.
