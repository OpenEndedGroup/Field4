# W5: remaining GPU probe migrations

Migrated `GpuSkinningSmoke`, `NeuralFieldParitySmoke`, `FieldStatsSmokeTest`,
`ShaderFieldAutoControlSmokeTest`, and `ShaderFieldQuantileWarpSmokeTest` to
`GpuProbe`. Their numerical tolerances, CPU oracles, negative controls and
measurement windows are retained.

The harness now accepts stage configuration for field-probe/statistics hooks and
SSBO resolution, without allowing a custom error callback to replace its failure
recorder. It waits before sentinel writes and releases registered pipelines on
close. The harness self-check exercises the custom error callback and repeated
close as well as its existing under-dispatch, binding, ordering and teardown checks.

Skinning uses shared fixture allocation, slot validation and sentinel readiness,
while retaining its existing repeated-dispatch throughput schedule. Field stats
still produces exactly six generations: that is the input sequence behind its
expected changing mean of 500, not a readiness delay. The two adaptive probes wait
for actual sample batches before beginning their original measurement windows.

## Verification

Verified in isolated checkout `codex/gpu-probe-migrations`, based on `cae5db555`.
`compileKotlinJvm` passed. Real GPU entrypoints ran serially through the backdoor
on dedicated port 9783, with exit code 0 and their success messages:

| Check | Evidence |
| --- | --- |
| Harness self-check | Under-dispatch, invalid slot, broken shader with custom handler, foreign buffer, stage ordering, teardown and repeated close passed. |
| Neural parity | All three networks passed; worst relative errors 5.84e-6, 3.50e-7 and 7.36e-6. Transposed-network negative controls failed by large margins as required. |
| Skinning | Fixtures, animation samples and 64-instance crowd passed. Crowd worst position error 9.537e-7, normal error 1.788e-7. Throughput sweep completed. |
| Field statistics | Generation 6, 1024 samples, mean 511.5, changing mean 500, smoothed mean 301.2563. |
| Adaptive control | Feature loss 0.064162 to 0.004014; 499 updates. |
| Quantile warp | Feature loss 0.063620 to 0.000960; CPU/GPU quantile agreement error 0.01158246; 256 updates. |
| Existing pressure-projection chain | All 16 claims passed; exit 0. Separate log: `/tmp/field-gpu-probe-pressure.log`. |
| Existing neighbour-grid probe | Exit 0; existing numerical and semantic-readiness assertions passed. |

`generateCodebaseReference`, `checkCodebaseReference` and `checkKDocAttachment`
passed; regeneration left the committed reference pages unchanged. Documentation
check log: `/tmp/field-gpu-probe-doc-check.log`.

Runtime log: `/tmp/field-gpu-probe-runtime.log`. These are GPU compute/readback
checks on this machine; no cross-platform or visual screenshot certification is
claimed. No numerical bounds were relaxed.

Commit-time citation coverage caught the loss of the direct `streamReads` call
when fixture allocation moved into the harness. The foreign-buffer self-check
now allocates that host-readable buffer directly, retaining executable coverage
without adding an exemption. The revised GPU self-check passed; log:
`/tmp/field-w5-commit-selfcheck.log`.
