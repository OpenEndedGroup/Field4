# Teach run-owned Loris workflows with cancellable native awaits

Frozen findings: `a873c7aa4150-01`.

Five Loris recipes use box-owned `go` coroutines and cancellable future `await`, with explicit cancellation propagation and escaped failure output. Native progress callbacks check the initiating coroutine's activity. Morphing uses async distillation/morph futures and interruptible IO for dilation so its explicit target duration is retained. Client/cache reuse remains explicit. The first recipe publishes a typed `analyzed` result for the follow-on low-partial fragment, whose prerequisites are now explained.

All five changed recipes ran on the actual committed film-dialogue WAV with the actual native Loris executable: analysis, low-partial follow-on, whirlpool editing/resynthesis, envelope transformation and label/align/morph. Actual outputs appeared on their advertised channels. A generated sixty-second tonal input kept a native analysis pending; its real child process was observed, Stop cancelled the exported analysis future, and the owned native process exited. No service/provider calls, substitute outputs or public capture changes were made.

Actual import/ktfmt and parsed source, literal, fragment and prose verification pass. Full guards belong to the lander.

Checkpoint: 381/405 addressed, 24 open. Mechanical coverage: Actual import/ktfmt across eleven page states; parsed source, raw literals and surrounding prose verified. Full guards are delegated to the lander.
