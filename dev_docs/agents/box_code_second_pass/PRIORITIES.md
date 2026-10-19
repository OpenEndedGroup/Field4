# Remaining sweep priorities

Checkpoint: **405/405 addressed; 0 open**. All five groups are addressed with recorded evidence. Collection-literal coverage and guide screenshot reconciliation are complete. Final landing outcomes remain. Execution order approved by Marc: **1 → 4 → 5 → 2 → 3**. Group numbers below stay stable. Addressed means reviewed/fixed with recorded evidence; queued means awaiting landing, not landed.

## Execution budget

- Work in coherent batches by shared failure mode, synchronizing authored copies together.
- Use the existing formatter/container harness; do not rescan the full frozen corpus.
- Run one focused behavioral check per meaningful shared change, plus a representative Stop/rerun/error case where ownership changes. Verify distinct behavior rather than repeating every mirror.
- Full guard runs belong to the lander. Preserve completed evidence when a host or daemon is interrupted.
- If an optional worker/GPU prevents a real check, record the exact missing capability and move to other work. Do not spend the batch installing unrelated infrastructure or count a missing check as passed.
- Extra camera angles, pixel-perfect comparisons across algebraically equivalent shader rewrites and additional presentation captures are not required unless a concrete regression remains.
- A runtime defect exposed by an example stays explicit: fix it coherently or record it as a separate prerequisite, rather than hiding it in tutorial code.

## 1. Correctness and live-state safety (7 findings)

Fix output bounds, ineffective cancellation, Runloop blocking, and restoration of temporary graph edits.

- `6bcdc2e8f183-01` — `src/jvmMain/resources/nano_compute.glsl`: Every OUT increments outOff without checking per-instance capacity. The host buildMappings accepts an outSize independent of maxSteps (default64), so a looping program can write past its output slice into another invocation’s data.
- `cf60e95a215d-01` — `dev_docs/agents/transcripts/kratos_parameter_study.yaml`: The callback intends to throw to abort grid/optimise, but box code has Box.error via HasOutput, which returns Unit and displays an error. BoxAiTools.kt explicitly documents this shadowing. The same call occurs at line 112; if resolved to the member, Cancel continues solving and repeatedly reports an error instead of aborting.
- `cf60e95a215d-02` — `dev_docs/agents/transcripts/kratos_parameter_study.yaml`: After the asynchronous sweep completes, this call runs inside frames. KratosStudy.resultAt submits an additional solve then blocks with future.get(timeoutSeconds,...), so the frame coroutine can stall the Runloop while waiting for the winning field; Cancel/Stop feedback can become unresponsive at the end of the operation.
- `26e1f530f93d-01` — `dev_docs/nesting_edge_demo/metadata.edn`: Diagnostic temporarily mutates child.parents, but restoration follows a call that can fail; an exception leaves this live document altered.
- `a006a9d7cfa3-01` — `dev_docs/agents/transcripts/kratos_flow_playground.yaml`: A pending solve uses values captured at submission, but completion builds skin and labels from shown, which may have changed while the worker ran.
- `1bfc33f3767a-01` — `dev_docs/agents/transcripts/kratos_thermal_playground.yaml`: Broad outer catch can handle coroutine cancellation as worker-unavailable, publishing installation error after user Stop even though finally correctly closes worker.
- `cf60e95a215d-04` — `dev_docs/agents/transcripts/kratos_parameter_study.yaml`: A cancelled or failed operation may stop below expected and leave its progress bar active for the remainder of the persistent frames loop. progress.finish() runs only when the whole box exits, and the next command overwrites the reporter reference.

## 2. Shared async ownership and usable audio examples (13 findings)

Adopt the existing run-owned helpers in related docs/transcripts together; compose errors and retain follow-on result handles.

- `158f702c98c5-01` — `dev_docs/agents/transcripts/audio_ai_gesture_board.yaml`: An unowned async completion can publish a previous run after a newer gesture has been submitted.
- `d95e3a0a6eb8-01` — `dev_docs/agents/transcripts/audio_ai_muscriptor.yaml`: Progress socket future is ignored and subsequent nested musicTranscription/download futures are not returned into the outer exceptional chain. Stop/rerun and inner errors have no coherent caller ownership.
- `50875a2740d1-01` — `dev_docs/agents/transcripts/audio_ai_speech_embeddings.yaml`: trackedSpeech ignores WebSocket future and callbacks have no visible caller run cancellation; nested inspect callback is detached.
- `50875a2740d1-02` — `dev_docs/agents/transcripts/audio_ai_speech_embeddings.yaml`: Status hardcodes original named projection controls, so editing pool/hop/start/duration changes calculation while text claims old values.
- `2c067afda547-01` — `public/docs/audio_ai_muscriptor.md`: result is only the parameter of the preceding thenAccept callback (line 105), so this follow-on top-level snippet cannot access that value when pasted after the previous example.
- `2c067afda547-02` — `public/docs/audio_ai_muscriptor.md`: Source reading identifies no saved socket/future or run-lifetime ownership: watch callbacks and awaitAnalysis completion can continue after Stop or a rerun and overwrite the new run outputs. FieldAudioClient.watch exposes the returned socket for cancellation but does not own the box lifecycle.
- `b4a34b4f3611-01` — `public/docs/audio_ai_speech_embeddings.md`: Progress subscription handle is discarded and callbacks are not tied to the current box run.
- `c02c565581b7-01` — `public/docs/field_audio_ai.md`: Long-running candidate completion captures output writes without a visible run ownership/freshness check. The page calls it a runnable box; rerequesting during a pending job needs an explicit last-run result policy.
- `a873c7aa4150-01` — `public/docs/loris.md`: Progress/final future callbacks publish without checking the current box run.
- `5fd2378f406a-01` — `public/docs/assets/transcripts/qualified_sources_approval.yaml`: Raw thread waits for approval with Thread.sleep and later applies edits without box-owned cancellation, so stop/rerun can leave an obsolete apply continuation.
- `4dac50c9b26c-01` — `public/docs/qualified_sources.md`: The proposal follow-up waits in a JVM thread with no box stop hook. Re-running qualifiedSource closes the previous source and replay runner, while this thread can still wake and call proposeRebaseline on that stale instance. StudyBuilds.await blocks the caller and does not establish box ownership.
- `5b66e287f774-01` — `dev_docs/agents/transcripts/staged_project.yaml`: The corpus launch is a raw worker with no run-owned cancellation or join. Closing currently registered StudyBuilds runners does not itself stop this worker from iterating on to later variants and installing new runners or writing stale gallery output after rerun/stop.
- `2ddf54b22aca-01` — `optional/streamdiffusion/examples/capture-motion.kt`: isDone includes exceptional completion, and only one output future is retained while source futures are discarded.

## 3. Resource, listener and replacement ownership (18 findings)

Group exact-instance cleanup repairs and related library defects. Exercise representative real replacement/Stop cases, then cover platform-specific paths where available.

- `b2734585c34f-01` — `dev_docs/agents/demos/learned_shader/example.kt`: Cleanup reads mutable published lab, so an old run may close a replacement; reload path also closes working lab before next.attach() can fail.
- `81ba569c56dd-02` — `dev_docs/agents/demos/neural_reconstruction/example.kt`: ReconstructionDemo attaches itself to renderer.preTargets/modules and advances animation in render(). The control loop has no end cleanup, while ReconstructionDemo construction does not register run ownership, so Stop leaves the view rendering and resources attached.
- `a86d30f62c84-01` — `dev_docs/agents/demos/opendlssnr/example.kt`: This finalizer reads a mutable exported study property. If another run publishes a replacement before the old frames block exits, old cleanup can close the new image study; the loop also follows the mutable property.
- `956a69992e2a-02` — `dev_docs/agents/transcripts/computed_ray_geometry.yaml`: Finalizer closes the mutable published lab property, permitting replacement-target cleanup if old run finishes late.
- `1dd91cfef057-01` — `dev_docs/agents/transcripts/external_browser_surface_macos_smoke.yaml`: surface.close is only attached later at Frames.end. A failed IOSurface export, Mach-port check or offerFrame assertion exits before that cleanup is registered, retaining native resident images and possibly offered handles.
- `d4ba1b4e01b8-01` — `dev_docs/agents/transcripts/gpu_shader_learning.yaml`: Cleanup reads current published lab and may retire new run; source resources are local, so mixed ownership could close mismatched view and teacher.
- `a4ee3aaf394c-01` — `dev_docs/agents/transcripts/iosurface_ring_roundtrip_smoke.yaml`: Streamer is started before require and before .end cleanup exists; setup failure can leave encoder/ring alive. Lower-level surface locks also need exception-safe release.
- `691b5d822671-02` — `dev_docs/agents/transcripts/learned_from_module.yaml`: Cleanup reads mutable inherited lab rather than the ShaderLearningView created by this run. During supersession the property is replaced, so cleanup ordering may close the new view or leave the old run tied to new shared state.
- `b03fdbe93779-01` — `dev_docs/agents/transcripts/neural_reconstruction.yaml`: ReconstructionDemo registers itself in renderer.renderList.preTargets/modules and does not adopt a RunScope. The example only closes it on a later rerun or mode change, so stopping the scene control loop can leave rendering/resources active.
- `b03fdbe93779-02` — `dev_docs/agents/transcripts/neural_reconstruction.yaml`: The constructor installs bundled shaders, not the vertex/fragment properties edited by the user. After a successful reload, switching reconstruction mode silently returns to bundled shaders. Initial creation/rerun has the same source omission.
- `d7544ea68a8c-01` — `dev_docs/agents/transcripts/optical_flow.yaml`: Cleanup closes current inherited lab rather than the instance this launch created. If property changes before old cancellation finalizer runs, cleanup can close the replacement. Raw named-context preclose may also target an ancestor.
- `bdc3d24bed57-01` — `dev_docs/agents/transcripts/path_traced_reconstruction.yaml`: Finalizer closes current published lab rather than the reconstruction created by this launch; mutable property is reassigned during rerun.
- `af4d4033b6e8-01` — `dev_docs/agents/transcripts/splat_sequence_streaming.yaml`: Direct feed/report registrations capture sequence/cloud indefinitely with no visible run cleanup. Stop/rerun should have explicit ownership so old source/cache does not remain active.
- `82a99c5fd301-01` — `dev_docs/agents/transcripts/syphon_zero_copy_receive_smoke.yaml`: Probe process starts before client/texture zero-copy require and before loop cleanup registration. Early failure can skip proc.destroy and resource close.
- `45010413e231-01` — `public/docs/neural_reconstruction.md`: Manual cleanup comment does not establish ownership of the AutoCloseable ReconstructionDemo used by rerunnable box code.
- `a599a60348a0-01` — `public/docs/onnx.md`: The once/per-frame sketch starts a daemon inference thread but omits its teardown. DepthAnythingWorker.close stops the thread; repeatedly following this sketch without ownership can leave old workers alive.
- `bbfaa07fbaa0-01` — `public/docs/orca.md`: onNote appends listeners in current Orca implementation; rerunning this update snippet against existing o accumulates melody callbacks. onCell similarly appends through onChange.
- `235cc7aef1f3-01` — `public/docs/puppet_browser.md`: A listener-registration snippet ignores its returned removal function, so repeated execution duplicates event prints.

## 4. Runnable examples and output cost (5 findings)

Remove private input prerequisites, fix the advertised texture output and bound expensive redraws.

- `192f12341fa6-01` — `dev_docs/agents/transcripts/audio_ai_underfit_mancini.yaml`: A machine-specific private source path is an unexplained prerequisite in a loadable starting point.
- `25f85b04108e-05` — `public/docs/assets/canonical_graphics/3cbcadc0-b279-470d-9a5f-afc120518829_code_.kt`: Several canonical texture/probe examples require private Desktop paths, so a learner cannot run the lesson from the repository. The paired bbc2 image has the same issue.
- `25f85b04108e-06` — `public/docs/assets/canonical_graphics/f1d2cbea-b20d-4794-b9f7-37b847dcdeb6_fragment_.glsl`: The box is named multi-textureing from a gltf, but this unconditional final assignment discards the sampled texture color from earlier in main. It renders a UV diagnostic instead of the advertised texture.
- `88ec49055037-02` — `dev_docs/agents/transcripts/typography_worked_example.yaml`: The kinetic box rerasterizes 1100x420 text and resends its grid on every Runloop frame, although elapsed-time animation needs only display-rate updates. Capture cadence does not cap live Runloop output.
- `00f66fc4d1b2-01` — `public/docs/wfc_examples.md`: Animation encodes/montages and sends the whole raster every Runloop iteration, including after completion. fps=20 in the runner controls recording, not this output loop, so redraw cost is coupled to machine speed.

## 5. Remaining helper/readability/classification polish (8 findings)

Finish prepared fullscreen-triangle/reflected-state adoption; group small idioms, explicit controls and runtime classification. Leave highlight-sensitive slideshow edits until higher-impact fixes are done.

- `794d049770a2-01` — `dev_docs/agents/transcripts/interaction_nets_lambda.yaml`: The reduction visualization manually arranges stage frames and fitInto calls before invoking showInteractionOverview, repeating the snapshot-strip pattern.
- `691b5d822671-01` — `dev_docs/agents/transcripts/learned_from_module.yaml`: The box manually installs fragment-only push members after Mesh.resize, which already installs merged vertex/fragment reflection, and uses a string-keyed state write although moduleHere generates Mesh.state.
- `0d9dd129bf16-01` — `dev_docs/agents/transcripts/polytope_path_trace_lineage.yaml`: The render quality, exposure, rotation and target sample count are spread at the bottom of a topology-heavy box; changing the convergence target requires finding the literal inside the frames loop.
- `ce11a168cbeb-01` — `public/docs/ai4_experiments.md`: Unchecked string-property casts expose the internal Studio interface in the tutorial; refresh has the same pattern two lines later.
- `906ab02e18de-01` — `public/docs/assets/opendlssnr/example.kt`: The setup requires a child of the standard renderer, which already publishes inherited renderer. Repeating fromContext teaches a defensive lookup instead of the box API and names a Renderer owner.
- `153504d5e8c3-01` — `public/docs/interactive_guides.md`: Guide alias is used only where current box is an argument; same host aliases recur in other examples. Removing aliases shortens copied teaching code but shifts line highlights.
- `2792e161f146-01` — `templates/advanced_remote/8b78e419-cd1e-4111-96ab-899eb3ed47d1_fragment_.glsl`: The shader initializes red and overwrites its three color components individually; the surrounding comments still call the opaque textured result translucent red.
- `2792e161f146-04` — `templates/advanced_remote/94f15e77-0d57-498c-a573-b7af7ac70a07_code_.kt`: Packet says Kotlin, but this body is JavaScript; metadata.json has no language on this box and it is parented only to root, so intended runtime is unclear.

## Work already prepared

Batch 64 changes four authored learned/GPU-learning sources to `fullscreenTriangle(teacher)` and reflected `mesh.state`. Actual import/ktfmt has passed. Behavioral verification is not done, so finding `691b5d822671-01` remains open. These explicit uncommitted paths and their before/after receipt are retained on `codex/box-code-readability-64`; finish them with the related resource work rather than starting another isolated visual rehearsal.

Batch 63 is committed and queued. Its measured camera evidence is complete, including the stated raster/quantization rounding bounds. Do not repeat it.

## Final sweep closure

After the prioritized findings, finish the residual collection-literal/helper-adoption audit against the frozen scope, preserving overload/type inference and live reset/fragment semantics. Existing whole-corpus import/ktfmt and GLSL receipts are in batch 56, with immutable recorded histories excluded by batch 57. Confirm all landing outcomes, repair concrete bounces through the lander, and report any explicitly unverified platform cases without silently claiming completion.
