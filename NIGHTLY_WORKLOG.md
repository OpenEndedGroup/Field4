# Nightly worklog — 2026-07-14 / 3 / Domains explainer (kind C)

Branch: `nightly/2026-07-14/3-domains-explainer` (base 127fc7d0)
Worktree: `/Users/marc/field4-worktrees/2026-07-14-3-domains-explainer`

## Mission
Write a documentation/explainer page for the Functions **Domain** model, motivated by
commit `fd603986 feat(functions): oscillators default to infinite domains`, whose
rationale currently lives only in the commit message.

## Findings from reading the source (src/jvmMain/kotlin/afield/stdlib/Functions.kt)
- `Domain` is a `data class Domain(min, max) : ClosedRange<Double>` (line 118). Companion:
  `ALL = (-inf, +inf)`, `UNIT = DEFAULT = 0.0..1.0`, `EMPTY = Domain(1.0, 0.0)`,
  plus `point(x)`. `intersect()` (line 186) is `max(mins)..min(maxs)`.
- Arithmetic intersects operand domains: `combinedDomain(a,b) = a.domain.intersect(b.domain)`
  (line 550), used by `Fn.plus/minus/times/div` (lines 5220-5254).
- `param(value)` carries `Domain.ALL` (line 636-639) — so numeric literals never narrow a domain.
- Oscillators default `domain = Domain.ALL`: `sin` (845), `cos` (879), and the whole
  periodic family + pulseTrain/impulseTrain/pulseBurst (grep of `Domain.ALL` defaults).
- `gaussian` keeps `Domain.DEFAULT` (2020+): localized, not periodic. The illustrative exception.
- Safe enumeration: `Domain.finiteSweep()` (2296) — empty/finite/point pass through unchanged;
  unbounded previews over `plotSafe()` (2277) which falls back to `Domain.DEFAULT`.
- Plot sampling (`Fn.plotHtml`, 2330): samples over `(sampleDomain ?: domain).plotSafe()`,
  evaluating with `evalSafe(x, outside=NaN, edge=ZERO)` (333). Outside the fn's own domain the
  sample is NaN and simply not drawn. THIS is what makes the hazard visible: an operand with a
  finite domain truncates the drawn curve of the whole expression.

## The "lie and a hazard" story (concrete)
Old default: `sin()` carried `Domain.DEFAULT` = 0..1. Because arithmetic intersects domains,
`sin(...) + rising` inherited 0..1 and, plotted over a wider window, silently stopped a third of
the way across. New default: `sin()` carries `Domain.ALL`, so `ALL ∩ (rising's domain)` = the
rising term's domain, and the curve is drawn across the full window. Params still carry ALL so an
explicit finite `domain =` or a finite operand (a window, a ramp built over a finite domain) still
constrains — intent is preserved, the accident is not.

## Prior art / house voice matched
- `public/docs/functions_examples.md` — "Domains and timeline composition" section (line 358+),
  and the `diffstatic_runner` fence format with `screenshot=...` (lines 379, 405). Assets live in
  `public/docs/assets/functions_examples/` (domain-over-*.png are the closest prior art).
- Nav config: `public/mkdocs.yml` — "Functions Examples" sits under "Drawing From Data" (line 44).
- Docs build: `tools/build_zensical_docs.py`, mode via `FIELD_DOCS_RUNNER_MODE`, `--page <stem>`
  renders one page's captures (README + building_and_testing.md).

## Plan
1. New page `public/docs/functions_domains.md` in house voice: what a Domain is; finite vs ALL;
   why oscillators are infinite (before/after `sin() + rising` clamp story); intersection under
   arithmetic; params carry ALL; safe enumeration via finiteSweep()/plotSafe(); gaussian exception.
2. One mandatory `diffstatic_runner` capture = the before/after contrast on one box:
   `sin(2.0, domain=0..1) + rising` (truncates at x=1) vs `sin(2.0) + rising` (full 0..3),
   where `rising = identity(0..3) * 0.6`. Asset under `public/docs/assets/functions/`.
3. Wire into `public/mkdocs.yml` nav under "Drawing From Data".
4. Render the capture via `build_zensical_docs.py --page functions_domains`, then EYEBALL the PNG.
5. Verify markdown/nav/asset paths; record what was and wasn't verified.

## Progress log
- (in progress) plan written.
