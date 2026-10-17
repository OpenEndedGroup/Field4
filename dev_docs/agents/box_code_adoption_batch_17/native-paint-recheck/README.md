# Native paint verification corrected

Native painting is available on this machine. The earlier failed private-JVM lookup used the
isolated worktree's build directory; the existing arm64 library is:
`/Users/marc/field4/build/field-paint/release/libfield_paint_jvm.dylib`.

The documented `afield.paint.library` system property selected that library without a rebuild or
changing the user's Field instance. PaintLibrary.describe reports engine 5. The current authored
paint-area example then executes successfully, producing the real layered blue/yellow work area,
red stippling and outlined triangle. Its browser capture was visually inspected. These results
supersede the original unavailable-render record for this example; no additional findings are
counted as addressed.

[Execution and capture evidence](evidence.tar.gz) includes the exact executed runner and verdicts.
The batch harness accepts AFIELD_PAINT_LIBRARY and forwards it into its private JVM. It now runs
paint_areas and records actual success, rather than preserving a historical unavailable verdict.
The standalone run.py accepts the library path as its first argument, from the worktree root.

The repaired full batch harness also passes with --resume: prior independent geometry proofs are
retained, the native paint case reruns and captures successfully, and its current comparison
record reports nativeRender=true.
