# Align saved fluid sources with typed inherited resources

Baseline `ea92696cf`. Three frozen sidecar findings addressed, with the same edits applied to the flow_grain transcript:

- `5e4f699ab6f2-01`: resource setup uses inherited renderer directly. Saved exports use the transcript's propDown declarations, so children read typed inherited values rather than renamed context lookups.
- `5e4f699ab6f2-02`: the advection sidecar uses those inherited resource properties and the approved computeBarrier(graphics = true) helper. The explicit graphics visibility matches the previous compute+graphics barrier pair.
- `5e4f699ab6f2-03`: keeper loops have an empty Unit body; the ignored true expression no longer suggests a continuation condition. Cleanup retains the exact simulation/surface installed by that run and checks identity before removal.

Actual imports/ktfmt cover both sidecars and 14 transcript Kotlin bodies, with the existing shader properties formatted too. The reviewed drafts, executable tokens, raw strings, comments and fragment boundaries are preserved; the selected idiom/collection audit is clean. There are no new collection conversions. All 15 boxes of the real transcript compile in their graph context.

The saved resources.kt and advect.kt execute under the actual renderer, helper shader and input stage, with the saved compute shader. After 30 real native frames, the stage is attached to the inherited simulation. Stop removes that stage and retains both parent targets. Relaunch installs the new stage. With children stopped, rerunning the resource parent creates new simulation/surface objects and old cleanup leaves those exact new targets installed. Stop then removes both. The private renderer is stopped afterward. This check proves stage/target ownership, not a numerical fluid-solver comparison or an artistic image change.

The reused formatter harness initially retained a batch-20-only builder check and stopped after formatting; that check is removed and the final formatter/preservation run passes. Exact successful source, runtime, ownership and compile evidence is retained in evidence.tar.gz, alongside the reviewed manifest and reproducible harnesses. This loose-source fixture has no metadata.edn or document.md mirror to regenerate.
