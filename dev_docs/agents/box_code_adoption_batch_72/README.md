# Own candidate generation callbacks and verify every motion capture save

Frozen findings: `158f702c98c5-01`, `c02c565581b7-01`, `2ddf54b22aca-01`.

The gesture starting point and both MusicGen/Underfit candidate recipes use cached shared clients and `runAsync`. Asset lookup is asynchronous. Lookup, submission and candidate polling futures each register explicit cancellation; completion output is published only by the current operation. Errors use escaped Markdown. Durable service jobs remain server-owned. The request controls, seed family, source windows and gesture board are preserved.

The private real audio API identity was verified against its isolated adoption-43 project. Complete gesture and style bodies compile and report actual missing-source errors from that service. Stop cancels an operation delayed before a real lookup, with its cancellation assertion recorded. The complete Underfit body typechecks without entering the branch that requires an unavailable real LoRA checkpoint. No generated candidates or model inference are claimed.

The diffusion motion example retains every source/output save future, waits for all to finish, and joins each before reporting completion. Stop cancels pending local saves and closes the exact demo. Two actual GPU JPEG saves from the authored teacher render target were written and decoded at 96×64. The private native host subsequently blocked in `vkAcquireNextImageKHR`; its thread trace is retained and that host was stopped. The later GPU write-failure case did not finish and remains unverified. A fresh CPU host typechecked the complete revised diffusion body; no TensorRT model/streaming acceptance is claimed.

Actual import/ktfmt and parsed container, literal and fragment checks pass. Existing broad native/async ownership evidence is retained rather than repeated, and the lander owns full guards.

Checkpoint: 377/405 addressed, 28 open. Mechanical coverage: Actual import/ktfmt; parsed source, literal, fragment and surrounding prose verification. Full guards are delegated to the lander.
