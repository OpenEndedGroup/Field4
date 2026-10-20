# Bound Nano GPU output slices and preserve adjacent packed lanes

Frozen findings: `6bcdc2e8f183-01`.

Nano output now halts before an OUT instruction exceeds its per-VM byte capacity, retaining the completed prefix. The explicit outputCapacity extends the mapping ABI from four uints (16 bytes) to five (20 bytes); host packing and KDoc agree. Zero capacity disables output. Negative step limits and overflowing slice offsets are rejected before allocation. Packed output uses atomic lane operations so adjacent odd-sized slices cannot erase each other's bytes.

The production GLSL ran on native Vulkan for 32 VMs at capacities 0, 1, 3 and 4. Every retained output prefix matched the actual CPU NanoVM, with distinct programs per instance and canary bytes before and after the allocation intact. Invalid host mappings were also rejected. The reusable FIELD_TEST regression is four.nano.output-bounds.

Initial diagnostics exposed test binding parameters that did not match existing probe fixtures, then misuse of sentinel-based settle for guard bytes that must stay untouched. The corrected harness uses matching buffer parameters, four real frames and the probe's fence wait. Diagnostic logs are retained; no renderer fallback or fabricated output was used. Actual ktfmt covered the host helper/test and the editor GLSL formatter processed the shader. No duplicate full guard ran locally.

Continue in Marc's approved order: 1 → 4 → 5 → 2 → 3. Prepared batch 64 helper edits remain uncommitted and uncounted until group 5.

Checkpoint: 355/405 addressed, 50 open. Mechanical coverage: Actual ktfmt and GLSL formatting applied to batch 65; earlier corpus receipts remain in batches 56–57. Full guards are delegated to the lander.
