# claude-paint's `paint` crate, vendored

This directory is `crates/paint` of [claude-paint](https://github.com/aliceisjustplaying/claude-paint),
by alice ([@aliceisjustplaying](https://github.com/aliceisjustplaying); MIT, `LICENSE`; third-party
notices in `THIRD_PARTY_NOTICES.md`), at upstream commit
`92f24f07764ab73704ece828df7dca4578cc03d5` (2026-10-07, engine 5), plus Field's additions.

Copied: `Cargo.toml`, `build.rs` and `src/` of `crates/paint`, and the repository's `LICENSE` and
`THIRD_PARTY_NOTICES.md`. Not copied: `crates/paint/tests`, `examples` and its `Cargo.lock` (the
wrapper's `native/field-paint/Cargo.lock` pins the dependencies).

Field used to depend on the crate by git revision (`8e78df0`, engine 2). Upstream's history has
since been rewritten and that commit is no longer in it; and Field's tools need the crate's
internals (a held brush's bristles are crate-private), so the crate is vendored instead. Engines
are kept: a canvas made with `engine = 2` paints what `8e78df0` painted, pixel for pixel, and a
state saved by it still opens (`src/jvmTest/kotlin/afield/paint/PaintToolsTest.kt` pins both).

## Field's changes

Everything Field adds lives under `src/field/` (`paint::field`): it is Field's code, not alice's, and
its bugs are Field's. Upstream files are changed only by
these hooks:

- `src/lib.rs`: `pub mod field;`, after `pub mod surface;`.
- `src/bristle.rs`: fields widened to `pub(crate)` so `field` can read and rebuild tools —
  `Bristle`'s `rx`, `ry`, `len`, `thresh`, `bend`, `seed`, `prev`, `lat`, `hide`, `cure`; `Held`'s
  `shows_solvent`, `shows_oil`; `Knife`'s `vol`, `solvent`, `lat`, `hide`, `cure`.
- `src/bristle.rs`: `exchange`, `cohesion` and `feed` widened to `pub(crate)`, so the segmented
  brush (`field::bristles`) can trade paint with the canvas through upstream's own per-bristle
  exchange from the contacts it computes.
- Dials past their range: Field refuses only values that would break the simulation, not values
  outside the range a dial is meant for, so a brush or paint can be set past it.
  - `src/bristle.rs`, `Tool::validate`: only `width > 0` and `bristles >= 1` are checked (and
    every value finite); the 0..1 ranges of stiffness, pickup, push and point, and the signs of
    length, lay, splay and ragged, are no longer refused.
  - `src/bristle.rs`, the plough in `exchange`: a hair moves at most the paint under it
    (`.min(v)`), so a push past 1 cannot leave negative paint behind.
  - `src/wet.rs`, `Paint::with_thinner`: any finite share, not clamped to 0..0.95.
  - `src/bristle.rs`, clumping in `exchange`, and `src/surface.rs`, `rheology_at`: paint stiffness
    past 1 keeps acting (fewer, wider clumps; a stiffer paste that levels less), capped at 10 in
    the rheology where its exponentials would overflow; a clump's deposit is kept from going
    negative.
  - `src/tests.rs`, `invalid_tools_are_rejected`: what is still refused, and what is not.
  Within the old ranges every engine paints exactly as before (the engine 2 hashes in
  `PaintToolsTest` pin it).

## Taking a newer upstream

Copy `crates/paint/{Cargo.toml,build.rs,src}` of the new commit over this directory (keeping
`src/field/`), re-apply the hooks above, update the commit here, then build and test:

```bash
cargo test --release --offline --manifest-path native/field-paint/vendor/paint/Cargo.toml --target-dir build/field-paint --lib
./gradlew buildPaintNative
./gradlew jvmTest --tests 'afield.paint.*'
```

A new upstream engine number is a new behaviour, not a fix to an old one: keep `engine = N` canvases
painting as they did (upstream gates its changes on the canvas's engine, and so must anything here).
