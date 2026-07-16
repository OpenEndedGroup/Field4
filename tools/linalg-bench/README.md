# linalg-bench

JMH benchmarks for `afield.stdlib.linalg`. This module compiles the linear-algebra sources
(`src/generatedLinalg` and `src/jvmMain/.../stdlib/linalg`) **standalone** — they depend only on the
Kotlin stdlib and the JDK foreign-memory API — so the benchmarks build fast and, importantly, the
arena safety profile can be set independently of the main app.

## Running

```text
./gradlew :tools:linalg-bench:jmh                            # arena checks compiled in (default)
./gradlew :tools:linalg-bench:jmh -PlinalgArenaChecks=false  # arena checks compiled out (production)
```

Results are written to `build/results/jmh/results.txt`. The default config is average-time,
nanoseconds, two forks.

### Allocation / GC story

The value of the frame arena and the segment views is that they do not allocate. That is invisible
to a latency benchmark; use JMH's allocation profiler. Temporarily add to the `jmh { }` block in
`build.gradle.kts` and re-run:

```kotlin
includes.set(listOf("Spline"))   // or "segment"
profilers.set(listOf("gc"))
```

`gc.alloc.rate.norm` then reports bytes allocated per operation.

## What's here

- `VectorBenchmark` — one vector kernel (add, scalar-mul, cross, dot, normalize) lowered onto every
  backend: heap (reused / allocate-per-op), heap double, frame arena, segment (packed / aligned),
  plus construction (raw constructor vs collection literal). Also a diagnostic `arenaAllocateOnly`.
- `JomlBaselineBenchmark` — the same heap kernel on stock JOML `Vector3f`. JOML is a **benchmark-only
  dependency here**; it is not a Field runtime dependency. This exists to verify the "generated heap
  vector performs like JOML" assumption behind removing JOML.
- `SplineThroughputBenchmark` — a 1,000,000-point cubic Bezier per frame, over many frames, comparing
  fresh-heap (GC churn), pooled-heap (manual, GC-free) and arena-reset (GC-free, no pool). Run under
  `-prof gc`; this is the benchmark that shows what the arena is for.

See `dev_docs/stdlib/GENERATED_LINALG_ARCHITECTURE.md` (“Measured performance”) for representative
numbers and interpretation.
