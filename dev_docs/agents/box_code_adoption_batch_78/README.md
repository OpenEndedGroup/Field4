# Own named tutorial listeners and depth worker threads by their box runs

Frozen findings: `a599a60348a0-01`, `bbfaa07fbaa0-01`, `235cc7aef1f3-01`.

DepthAnythingWorker now adopts its constructing box run after its thread is initialized; ordinary non-box callers retain explicit close. The ONNX sketch says once per run and explains Stop/rerun cleanup.

ORCA has named note/change/cell overloads. PuppetBrowserControl has a named event-listener overload. A shared internal registration helper replaces the exact listener on the same source object, guards stale dispatched callbacks, restores the registering box/run context, and removes only that subscription on Stop/rerun. Existing unnamed APIs keep their behavior. Tutorial snippets use distinct descriptive names for independent callbacks. The page-request bridge explicitly owns its existing remover. FieldRTTStreamer.stop also removes only its own exact target, so delayed cleanup cannot remove a replacement.

Real acceptance used a fresh private Field host: an actual running ORCA page emitted notes and cell changes; real headless Chromium emitted console events. Only replacement handlers ran, their originating run context was preserved, Stop detached handlers while both sources stayed alive, and stopping an older registering run preserved a newer run's listeners. Two real Depth worker threads constructed using the already-cached FP32 model exited after Stop and rerun. This is worker lifecycle evidence, not a claim of a new inference or webcam capture. Actual import/ktfmt and parsed preservation checks passed for all selected source/doc states. The lander owns full guards.

Checkpoint: 405/405 addressed, 0 open. Mechanical coverage: actual import/ktfmt and parsed source/state preservation checks passed. Full guards are delegated to the lander.
