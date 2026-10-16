# Drawing paths and grid helpers

Baseline `989d5f72e`. Twenty findings addressed across Gaussian-process drawings, MARF,
stipple transport, movement, graph routes, spatial grids and the first-steps template.
Three logging-builder import findings are separately accounted for as already repaired upstream
in `c7c58915d`; their builder bodies and shipped sidecars agree and import `withSink` explicitly.

Connected samples now start with `breakNext()` followed by uniform `lineTo` calls. Independent
point clouds and disconnected graph edges retain their moves. Incidental rectangles use `rect`;
two grid wrappers delegate to `grid(bounds, nx, ny)`. The first-steps loop drops its unused trailing
Boolean. Its deliberate `petals` declaration/reset pair and all authored fragment boundaries remain.

## Verification

- Actual import analysis and ktfmt applied to Kotlin sidecars and YAML code. The six selected docs
  pages contain 62 independently formatted Kotlin states, including before/after and folded states.
- Collection-literal audit passes; two spatial calls with spreads remain factories. Compile/replay
  caught an ambiguous single-element literal passed to `lines`; all three MARF copies now use the
  direct `lines(path, ...)` overload. Gaussian-process list concatenation likewise appends concrete
  lines rather than an ambiguous literal RHS.
- The repaired five-transcript compile/replay guard passes. This executes the safe transcripts;
  it does not establish that every standalone documentation fragment executes independently.
- Three actual Gaussian-process drawing helpers have identical before/after path fingerprints.
  Both actual grid functions have matching node classes, styles and endpoints (measured maximum
  distance **0.0** at the tested bounds). The first ribbon frame has identical geometry across
  all 24 paths, each with 181 nodes.
- The shipped first-steps sampling code launches, renders, stops, reruns and stops with passing
  lifecycle censuses. Four real private-browser captures were inspected. Capture fixture repairs
  supplied the correct `lines` channel and an explicit box frame; failed attempts are retained.
- Demo inline fallbacks were refreshed by `regenerateAgentDemos`; the template uses Field's actual
  persistence codec. The MARF Markdown code mirror/digests match sidecars, and the first-steps
  capture transcript matches its template.

The spatial unit-cell rectangle now uses a Close instruction instead of an explicit final segment
to its first point. The rendered boundary is the same; node representation intentionally differs.
No public capture was regenerated. Runtime evidence is from private Field/browser port 9700,
separate from the user's Electron session. [Verification index](verification.json) and compressed
source diff/evidence preserve the exact scope and failures.
