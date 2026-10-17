# Reconstruction shader contracts and readable source containers

Baseline `9abce375c`. Eleven findings addressed across ten source/container paths.
[Reviewed drafts](drafts.json), [source/shader verification](verification.json),
[source diff](sources.diff.gz), [evidence](evidence.tar.gz).

Scene shaders now explain each packed push vector, object-ID ranges, world-to-clip projection,
unjittered current/previous correspondence, raster-only jitter, top-left UV conversion,
current-to-previous input-pixel motion and Vulkan depth. World-space checker/material operations
are separated from lighting. The compare pass explains its output dimensions, heading height and
neural-availability flag. The fullscreen triangle explains its 0/2 corners and flipped texture Y.

The RR transcript adopts the exact guide conversion reviewed in batch 10, after matching its old
shader tokens against that review's baseline. The guide's named intermediates and comments explain
depth, motion sign/scale, valid geometry, world normals and the opaque Lambertian material.

Escaped YAML source strings become readable literal scalars. A parsed-container equality check
allows only the reviewed shader comments/guide replacement at that stage. The real import pass,
ktfmt and GLSL formatter then cover fifteen Kotlin bodies and fourteen shader occurrences.
Ten eligible factories become collection literals. Source checks retain container metadata,
executable tokens after reviewed replacements, authored fragment markers and exact GLSL comments.
All fourteen authored shader occurrences compile with glslc and validate as Vulkan SPIR-V.

## Import cleaner repair

The first real transcript check caught the formatter removing Vulkan's explicit Target import.
Kotlin then selected kotlin.annotation.Target, so the render target tried to inherit an annotation.
The cleaner now considers Kotlin/JVM default packages alongside Field's intro when deciding
whether a named import disambiguates. The pinned compiler's default-import implementation was
inspected directly; its disassembly is retained in the evidence.

All twelve BoxImportsTest tests pass, including a targeted default-annotation case and the actual
formatter retaining Vulkan Target idempotently. All four ReconstructionTest contract tests pass.
The source drafts were restored mechanically and formatted again with the repaired cleaner;
all three top-level transcripts then pass their real compile classification. The failed run is
retained alongside the repaired result.

GPU rendering and NVIDIA reconstruction modes were not newly exercised. The existing runtime
fallback smoke loads different bundled shaders, so it is not presented as acceptance for these
authored stages. Mathematical operations and the push/attachment ABI are preserved; the RR guide
replacement uses the separately checked batch-10 equivalence proof.
