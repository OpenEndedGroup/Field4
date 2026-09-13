# Statistics: measurements behind `statistics_examples.md`

Numbers that support the stats pages without belonging on them. The page states the bound a
caller acts on; this file records how the bound was established and which part of it a test
actually asserts.

## `chatterjeeXi` on a constant predictor: the one-ulp bound

`src/jvmTest/kotlin/afield/stdlib/RollingDependenceTest.kt`, test
`xi on a constant predictor is zero to within one ulp, whatever order the pairs arrived in`.

The closed form for a single tie group is exactly zero: the numerator's expectation is
`(n²−1)/3` against a denominator of `n(n²−1)/3`. In `Double` the quotient is not bit-exact,
so the test sweeps `n` in `2..400` with `x` constant and `y` the arrival index, and asserts
that the worst `|xi|` over the sweep is at most `1.0.ulp` = `2.220446049250313e-16`.

What is **asserted**: the bound. That is the number `statistics_examples.md` quotes, as "one
ulp", and the reason it tells callers to compare against a tolerance rather than against `0.0`.

What is **not asserted**: how many of the 399 sweep values are non-zero, or which `n` is worst.
A run of the sweep on the current JDK returns 54 non-zero values, and the page carried that
count for a while. It is an observation of one JDK's rounding, nothing guards it, and it tells
a caller nothing the bound does not — so it lives here and not on the page.

Two further claims in the same test, both exact rather than bounded:

- order invariance — the same multiset of pairs gives a bit-identical answer however it is
  shuffled (`assertEquals(..., 0.0)`), which is the property that makes the closed-form
  expectation worth having at all;
- the `all y identical -> 0.0` branch really is exactly `0.0`, because it is an early return
  and not a computed quotient.
