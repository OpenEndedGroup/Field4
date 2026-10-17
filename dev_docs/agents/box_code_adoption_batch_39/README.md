# Reprojection: rerunnable passes and stable editable motion

Baseline `aca07741a`. Five frozen findings addressed: `c69d11328023-02` through `-05`, and `fc9b34aa3014-01`.

The pass-chain builder now selects the input before the first existing named pass when replacing a chain. New chains still append to the current output. This prevents a rerun from feeding a reused output target back into its own input. Six CPU cases cover empty/new/replaced chains, first-pass replacement, downstream effects and target replacement; the real JVM entrypoint passes.

Camera motion starts from one fixed pose. Eye and target translate together; bounded world-Y yaw is computed from the original direction. A full launch resets time/pose and invalidates history. Deliberate declaration/reset pairs expose blend, debug gain and motion amplitudes/speeds for assignment-only Alt-Enter edits. Actual shader bindings read the editable variables. The custom Vector3f has no rotateY method, so the executable example uses explicit sin/cos rotation.

Canonical sidecars, builder and transcript contain exactly the same four code bodies. Saved EDN fallbacks and document.md mirrors are refreshed through Field's codec, preserving IDs and all non-source graph properties. Historical camera-only commentary is replaced with the current camera/object motion explanation. Availability is described accurately as frame validity plus previous-UV bounds; it does not detect depth disocclusion.

## Actual verification

- Baseline: 900 real camera poses show direction length drifting from 2.904659 to 6.096752. Source plus actual target identities establish that the old current-output selector chooses the reused display output. The attempted baseline second launch after stopping its renderer failed because that inherited renderer was gone; this is retained as a harness diagnostic, not claimed as successful execution of self-feedback.
- Fixed builder graph: all four real native modes render. Setting motionGain to zero on the existing variable produces neutral output with zero RGB deviation; changing dollyAmplitude to zero fixes the existing loop's eye at Z=-4.2.
- Fixed motion: 900 samples keep direction length within 4.427188311..4.427188761, yaw within ±0.100000 radians and eye Z within -5.899999..-2.500007.
- Two full chain reruns retain the original upstream input, persistent TAA target and pass order, change the history epoch, and retain the bounded reset pose. The actual mode controller cycles successfully afterward.
- A fresh JVM opens a copied saved document, checks all four loaded source properties, renders it, samples another 120 poses and passes two more actual reruns. Native captures are inspected.
- Actual import/ktfmt covers 12 code occurrences; three collection-literal conversions. Reviewed tokens, execution boundaries, raw strings and YAML containers are checked. Explicit collection/idiom audit and scoped compile gates pass. No GLSL source is changed.

Reusable harnesses and evidence.tar.gz retain exact results, numeric samples, genuine captures, failures and source/runtime diffs. Derived contact sheets are private QA only and excluded. Runtime coverage is this graph and the CPU ordering cases; the full corpus pass remains incomplete.
