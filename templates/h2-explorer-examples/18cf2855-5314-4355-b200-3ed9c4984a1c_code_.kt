/*
 * HOW `.h2` DECIDES
 *
 * `.h2` engages when there is something to browse, and otherwise hands the value straight to
 * `.h` -- byte for byte, same HTML. Two rules:
 *
 *   1. A collection, array, map or buffer. No size threshold: the gate admits exactly what the
 *      three rendering surfaces can draw.
 *   2. A domain object with two or more readable properties.
 *
 * Two or more, and not `.h`'s elision point of ten, because `.h2` is typed by hand. Someone who
 * wanted the compact form typed `.h`.
 *
 * WHAT IT REFUSES, and why each refusal is deliberate:
 *
 *   - numbers, strings, booleans, chars, enums -- nothing to browse
 *   - `Quantity` and `Expression` -- the unit-aware formatter IS the value
 *   - vectors, matrices, quaternions -- `.h` draws a matrix as a heatmap; a property dump is worse
 *   - `Pair`, `Triple`, lambdas
 *   - anything in a JVM or Kotlin platform package (`java.`, `kotlin.`, ...)
 *
 * That last one is what keeps "`.h2` explores everything" from meaning "`.h2` dumps every object
 * on the heap". A class with an EMPTY package is admitted, which is where your own box-compiled
 * declarations live -- box 1 above relies on that.
 *
 * WHEN THE REFLECTIVE DEFAULT READS BADLY
 *
 * Register an adapter. It returns the value you would rather explore, and every surface picks it
 * up -- a `LinkedHashMap` inherits map paging, and a `List<Map<String, Any?>>` becomes a table
 * with one column per key:
 *
 *     registerH2Value<MyType> { v ->
 *         H2ValueSnapshot(
 *             value = linkedMapOf("a" to v.a, "rows" to v.rows),
 *             summary = "MyType with ${v.rows.size} rows",
 *             title = "MyType"
 *         )
 *     }
 *
 * Reach for one when reflection would be misleading (an `Fn`), when the interesting content is not
 * in the fields (an `FLine`), or -- most importantly -- when a getter has SIDE EFFECTS. A
 * `KProperty1` getter is opaque, so `.h2` cannot tell a field read from a property that opens a
 * file or compiles a REPL engine. `Box` is served by an adapter for exactly that reason.
 *
 * A getter that throws is not hidden: it becomes a row marked with a warning sign, carrying the
 * exception text.
 *
 * See dev_docs/VALUE_VISUALIZER.md and public/docs/visualizers.md.
 */