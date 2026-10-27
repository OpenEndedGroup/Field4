# Runaway box code: watchdog and interrupt

Box code runs on the Runloop thread. Before this change, a box whose code never returned
(`while (true) {}`, runaway recursion) stopped every frame for good: `stop-all` dispatches on a
Ktor coroutine but its work waits on the blocked Runloop, and the in-Field agent's RunBox could
only time out (`dev_docs/ai/IN_FIELD_AGENT_HARNESS.md`).

Source: `src/jvmMain/kotlin/afield/runtime/lifecycle/BoxInterrupt.kt`.

## How it works

- **Instrumentation.** `KotlinInterface2.evaluate*` calls `BoxInterrupt.instrument(snippet, label)`
  just before `evaluator.eval`. It rewrites the `.class` entries of the snippet's
  `KJvmCompiledModuleInMemory.compilerOutputFiles` (a mutable sorted map in the current compiler;
  if it ever becomes read-only, a warning is logged once and box code is simply not interruptible)
  with the compiler's shaded ASM: `INVOKESTATIC BoxInterrupt.poll()` at every method entry (not
  `<init>`/`<clinit>`) and before every backward jump or switch. `COMPUTE_MAXS` only: the call
  has no stack effect and is never placed at a label, so stack-map frames stay valid and no class
  loading happens. Instrumented modules are remembered (weakly), so the rerun compile cache's
  re-evaluation does not instrument twice.
- **poll()** is one *opaque* read (`AtomicReference.getOpaque`) and a null check. When a request targets the current thread it
  clears itself and throws `BoxInterruptedError` (an `Error`, so box `catch (e: Exception)` does not
  swallow it). The evaluator reports it as the run's error; Framer and other dispatch already catch
  `Throwable`.
- **Requests** come from the `interrupt-box` RPC (palette "Interrupt Running Box"), from
  `FieldGraphTools.execute` when RunBox times out with box code on the Runloop stack (stage
  `interrupted`), and from the watchdog. `Thread.interrupt()` is only added when the Runloop is
  parked in `sleep`/`wait`/`park` *beneath* box code, because an interrupt flag left on the
  Runloop outside box code would break its idle park.
- **Watchdog** (`afield-runloop-watchdog`, daemon, started by `Runloop.start`): polls
  `Runloop.runLoop.frame` every 100 ms. After `AFIELD_RUNLOOP_WATCHDOG_REPORT_MS` (1500) of no
  frame it logs `warn,runloop,watchdog` with the culprit box and the top of the Runloop stack;
  after `AFIELD_RUNLOOP_WATCHDOG_INTERRUPT_MS` (30000, `0` = never) it requests an interrupt, but
  only if a snippet class is on the stack. A request is withdrawn as soon as the frame advances, so
  it can never land later in unrelated box code.
- **Naming the culprit.** `RunScope.current()` and `Box.context` are thread-locals, unreadable from
  the watchdog; instead each instrumented class name is recorded against its box label, and the
  innermost matching frame of `Runloop.mainThread.stackTrace` names the box.

Switches: the **interruptible box code** checkbox in the Runtime section of `/welcome` (persisted as
`FieldPreferences.boxInterrupt`, default on; off stops instrumenting newly compiled code and stops
the watchdog interrupting, while stall reports continue). `AFIELD_BOX_INTERRUPT=0|1` or
`-Dafield.boxInterrupt` override the checkbox. `AFIELD_RUNLOOP_WATCHDOG=0` (no watchdog); system
properties `afield.runloop.watchdog[.reportMs|.interruptMs]`.

## Overhead

Measured 2026-10-26 with a standalone copy of `poll()` (JDK 22, M1 Max, loaded host, best of 15–30
runs, 10M iterations), polls placed exactly where the rewrite puts them:

| loop | no polls | volatile read | opaque read |
|---|---|---|---|
| `double` sum over an array | 9.6 ms | 9.5 ms | — |
| 1000² grid calling `sin(x)*cos(y)` | 8.0 ms | 8.4 ms | — |
| `a[i] += i` over an `IntArray` | 2.2 ms | 43–57 ms | 3.1–3.3 ms |

The volatile read's acquire barrier on every back-edge stopped the JIT vectorising the store loop
(~20x). An opaque read cannot be hoisted out of the loop either, so a request is still seen within
an iteration, but it leaves the loop vectorisable: ~1.5x on the worst shape, noise elsewhere.
Loops that call functions are dominated by the call.

## Limits

- Loops inside library/JDK code that box code calls never poll (a `Thread.sleep` or lock wait is
  woken, see above). Such stalls are still reported.
- Only Kotlin box code (K2 engine) is instrumented; Python, Clojure, JS boxes are not.
- `Thread.stop` is unsupported since JDK 20, so there is no non-cooperative escape.
- A legitimately long synchronous computation (> 30 s on the Runloop) is now interrupted. Move it
  off the Runloop or raise the threshold. The limit was 5 s until 2026-10-27, which interrupted
  docs examples that compute for a few seconds in their body (`a_pen_that_sees` at 5046 ms). The
  docs runner starts Field with 300000 (5 minutes); its own per-capture timeouts govern there.

## Verification

Smoke `runtime.runaway-box-interrupt.smoke`
(`src/jvmMain/kotlin/afield/runtime/lifecycle/RunawayBoxInterruptSmokeTest.kt`) and jvmTest
`afield.runtime.lifecycle.BoxInterruptInstrumentationTest`. Demo transcript:
`dev_docs/agents/transcripts/runaway_box_interrupt.yaml`.
