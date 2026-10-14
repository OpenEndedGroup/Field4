# Compile once, re-evaluate on rerun

A box that reruns with nothing changed used to compile its code again on every run. The
measured case was a `Tempo` box writing `propDown<Int>(bpm)` every 250 ms to a `Beat` child.
Each Beat rerun compiled twice: once for the regathered preamble and once for the code. In
this morning's session log that came to 357 compiles (median 42 ms, 20.0 s in total) against
336 evaluations (median 6 ms, 2.3 s), and Beat fell behind its writer. Every compile also
adds a snippet to the engine's compiler REPL history.

`KotlinInterface2.exec` now goes through `RerunCompileCache`
(`src/jvmMain/kotlin/afield/RerunCompileCache.kt`). An exact rerun evaluates the snippet
the engine already compiled, without compiling it again.

## What the key covers

- **The macro-expanded source** that `exec` receives. Macro expansion and preamble
  gathering still run on every rerun, so side effects such as `propDown` registering its
  reader, or `Insert`s into other boxes, still happen. Only the compile is skipped.
- **The source property and the implicit receivers' classes.**
- **The engine.** The cache belongs to one `KotlinInterface2`, so a reset box gets a new
  engine and starts cold. A disposed engine refuses the snippet; it does not reuse it.
- **Not cached at all:** sources with `@file:` annotations (`DependsOn` changes the compile
  configuration), and `compile`/`compileSnippet` callers, which always compile.

## Re-evaluating a K2 snippet is not the same as recompiling it

These cases were measured with a throwaway probe on Kotlin 2.5.0-Beta1 `K2ReplEvaluator`
on 2026-10-14. They are pinned by the smoke test below.

1. **Evaluated straight after itself, the body does not run.** The evaluator reuses the
   class it already loaded, and a K2 snippet body runs once per loaded class. For example,
   `lst.add(1); lst.size` returned 1 twice and the list kept one element. When another
   snippet is evaluated in between, the class is reloaded and the body runs. So a hit is
   refused when the cached snippet is `evaluator.lastEvaluatedSnippet`. A box with
   preamble + code always alternates the two, so this case only applies to a box with no
   preamble, which then compiles as before.
2. **Bindings go stale.** A snippet binds each name to the snippet class that declared it
   when the snippet was compiled. After `val q = 5; q * 2; val q = 6`, re-evaluating
   `q * 2` gave 10, where a recompile gives 12. So each entry records the earlier snippet
   classes its bytecode names. The entry is refused once any name those classes declare
   (fields, methods, accessors, nested classes, read with ASM) has been redeclared by a
   later compile. A compiled `import` invalidates every entry.

A hit therefore evaluates a reloaded copy of the class. Each rerun still loads a class.
What it no longer does is run the compiler or grow the compiler's REPL history.

Switch it off with `-Dafield.kotlin.rerunCompileCache=false`, or at runtime with
`KotlinEngineConfig.rerunCompileCache = false`.

## Verification

`core.repl-rerun-compile-cache.smoke` (`ReplRerunCompileCacheSmokeTest`), run through
`run-jvm-main`, passed. It covers these cases:

- 5 preamble + code reruns: 0 compiles, 10 hits, and every body ran.
- Edited code compiles and shows the new behaviour.
- A redeclared preamble name recompiles the code that reads it (12, not 10).
- A different expansion compiles.
- The same snippet run twice in a row compiles.
- Another source property compiles.
- A new engine compiles everything.
- A disposed engine refuses the snippet.
- With the cache switched off, every run compiles.

Live run, port 9671, one Field process. Tempo ran `propDown<Int>(bpm) = 120` with
`loop { delay(250); bpm = 1 + (Math.random()*99).toInt() }`. Beat printed `60000 / bpm`
together with its engine's `compileCount`, `compileNanos` and `rerunCacheHits`. Each mode
ran for 20 s:

| | cache off | cache on |
| --- | --- | --- |
| Tempo writes | 80 | 80 |
| Beat runs, distinct output lines | 79 (missed the first write, which happened before Beat first ran) | 80 |
| Beat engine compiles at the end (4 at the first run) | 160 | 5 |
| Total compile time | 5844 ms | 315 ms |
| Rerun compiles | 156, ≈ 35 ms each | 1 |
| Cache hits | 0 | 157 |
| Beat output after the write, median / p90 | 95 / 131 ms | 36 / 42 ms |
| Every Beat line has `60000 / bpm` right, and Beat's bpm sequence follows Tempo's in order | yes | yes |

After that, Beat was edited to `println("edited ${bpm * 1000}")` and run. It printed
`edited 87000`.

Not measured: heap and metaspace, before or after.
