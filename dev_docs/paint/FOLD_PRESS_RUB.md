# Fold, press and rub on the paint canvas

`PaintCanvas.fold / press / rub / wetMm3`, kernels in
`native/field-paint/vendor/paint/src/field/squish.rs` (C ABI in `native/field-paint/src/tools.rs`).
Method after Dan Fountain's "Fold & Squish" write-up (https://danfountain.com/blog/paint-that-squishes);
no code taken (none published, licence unstated).

- Model: contact where wet vol(a)+vol(mirror) exceeds a pressure-set gap (3 coats at pressure 0);
  symmetric exchange of `transfer` of each film; Jacobi thin-film spread (mobility ~ h²/(4+h²) ×
  fluidity × openness × contact) restricted to the pressed box; peel splits each pair's total by a
  sharpened anisotropic ridged value noise, so the two sides are complementary.
- Rub: pad reservoir, streak-weighted disk, lays 22%/step (trailing-heavy), never re-picks its own
  deposits on the same pass; what remains on the pad is reported as `liftedMm3`.
- Only the wet layer moves; openness from the drying clock (`1 - cure/GEL`).
- Timing, 1600×1280, one thread, M-series: brush stroke 527 ms, fold 156 ms, plate 63 ms, rub 29 ms
  (`cargo test --release --lib squish_timing -- --ignored --nocapture` in vendor/paint).
- Gotcha: `./gradlew jvmTest` is cached against the Kotlin inputs only; after `buildPaintNative`
  changes the dylib, pass `--rerun` or the old pictures/results are reused.
- Not done: blow/drift; GPU path; the fold's mirror is nearest-pixel, exactly symmetric only for
  axis-aligned folds on pixel boundaries (mass is conserved either way).
