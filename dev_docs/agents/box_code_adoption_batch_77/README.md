# Own native interop and splat playback resources across setup and Stop

Frozen findings: `1dd91cfef057-01`, `a4ee3aaf394c-01`, `82a99c5fd301-01`, `af4d4033b6e8-01`.

Native surface, encoder ring and Syphon acquisitions now sit inside the box's frame coroutine, with use/try-finally covering setup, awaits, failure and cancellation. Removal compares the exact registered target; IOSurface locks and retained references unwind on failure. Splat playback owns its cloud and both callbacks immediately, with GPU destruction deferred to renderer cleanup.

Actual import/ktfmt and parsed checks passed for all four transcripts. Complete bodies typechecked. On a private MoltenVK host, the authored IOSurface, installed encoder-ring and installed Syphon peer flows passed their real pixel, release, resize/import and zero-CPU-copy assertions. An injected failure after actual surface acquisition proved use closed that object automatically. Production-written two-frame compressed PLY input exercised real splat playback/decoding; Stop removed the cloud module and both callbacks. No output was substituted. Splat cache memory is not claimed to be explicitly cleared.

Corrected batch 76's driver attribution: its successful native acceptance ran on KosmicKrisp. Batch 77 explicitly selects and reports MoltenVK; both sets of measured behavior remain valid on their actual drivers.

Checkpoint: 402/405 addressed, 3 open. Mechanical coverage: actual import/ktfmt and parsed checks passed; corpus receipts remain in batches 56–57. Full guards are delegated to the lander.
