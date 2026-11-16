# Spinny algebras: notes behind the linear-algebra section

The "Spinny algebras" section of `public/docs/linear_algebra.md` (2026-11-15 nightly) explains
complex, dual, split-complex, quaternion and bicomplex numbers by drawing `exp` as a power series.
It follows 2swap's video *The best algebras are spinny* (https://youtube.com/watch?v=-k-Dnw0F3kc),
rebuilt from the textbook math; no frames are reused.

## What was added

- `Quaternionfc.times(Quaternionfc)`, `times(Number)`, `plus`, `minus` in
  `src/jvmMain/kotlin/afield/math/linalg/linalg.kt`: non-mutating operators returning a new `Quat`.
  Before this a quaternion product in box code was `Quat(q).mul(r)` (in place); a series like
  `term = term * u * (t / n)` could not be written as algebra.
- Four runner-captured FLine figures (`public/docs/assets/spinny_algebras/*.png`) and a demo
  document `public/docs/assets/spinny_algebras/metadata.edn` (two slider boxes plus the four figure
  boxes).

## Traps hit

- First draft of the prose claimed bicomplex `exp(t·u)` escapes to infinity. It does not: the
  bicomplex numbers are C⊕C (idempotents `(1±k)/2`), so `exp(t(c·i + s·j))` is two rotations at
  rates `c−s` and `c+s`, bounded but generally not closed. The capture showed bounded loops; the
  prose was corrected to "does not come back to 1 after one turn" and to the real difference
  (quaternion paths are circles with `k = 0` for every unit imaginary; bicomplex has zero divisors).
- In the `j² = κ` family with `u = c·i + s·j`, `u² = −c² + κ s²` is a scalar, so `k` stays 0 and the
  colour carries nothing; only the bicomplex panel uses the colour axis.
- `(Float * 0.8).coerceIn(-1f, 1f)` fails in a box: `Float * Double` is `Double`.
- A function parameter named `color` shadows `FLine.color` inside `fline { }` ("val cannot be
  reassigned").

## Not done

- No Vulkan-renderer version of the `j²` sweep; FLines only. A live 3D figure with the fourth
  component as colour is the obvious next build.
