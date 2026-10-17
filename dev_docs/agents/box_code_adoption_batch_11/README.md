# Graphics dispatch, inheritance and streaming

Baseline `c2103ed97`. Five saved findings addressed. [Reviewed edits](changes.json),
[verification](verification.json), [source diff](sources.diff.gz), [runtime evidence](evidence.tar.gz).

Compute examples now round workgroups upward and guard every buffer access. The buffer-reference
producer publishes its particle count with its output buffer; the draw child inherits both.
A nearby comment explains the safe stop/rebuild order when changing allocation size. The ring
fragment uses ascending smoothstep intervals, a positive pixel feather, and a named coverage value.
The streamer uses its current package, returns the cached constructor correctly and publishes a
typed render target. Rerunning it retains the actual sender.

## Runtime repair

Exercising the older descriptor-buffer example exposed a real runtime regression: module builders
assigned `Structure.proxying`, while map/read/write used a separate `proxyBuffer`. The legacy
property now reads and writes the same root backing as the newer adapter and nested structures.
The public property signature is retained. The existing coercion and per-frame-write test suites
pass, and a new regression checks shared root/child mapping and unbinding. A fresh Field JVM then
ran the actual descriptor example through the published map/write/read API successfully.

## Verification

- Actual buffer-reference GPU readback reproduces the original tail bug: only 64 of 65 requested
  particles were written. Revised dispatch checks every output scalar at counts 0, 1, 64, 65 and
  100000, with sentinel padding unchanged. Zero uses a padded test allocation, not an assertion
  that the published zero-size allocation is supported.
- Actual descriptor-buffer GPU readback checks all scalars at counts 1, 16, 17 and 65, including
  the partial final workgroup. Both input mapping and output readback use Structure's public API.
- The linked saved graph renders 100000 particles with the inherited count. Three actual native
  captures at different sprite sizes were inspected; the animated frames are not claimed equal.
- The real native streamer delivers 36 complete frames over a private loopback socket; a real
  HEVC frame decodes to 1280×720 and was inspected. Sender identity is unchanged after rerun,
  with encoding continuing from 9 to 32 frames. Cleanup removes only the fixture's owned entries.
- Four actual saved wrappers compile in their inherited graph. Field's real persistence codec
  refreshes sidecar fallbacks while preserving box IDs and all non-sidecar properties per box.
- Real Kotlin/import formatting covers 27 documentation states and three saved sources; real
  GLSL formatting covers 30 documentation states and two saved shaders. Formatting preserves
  executable tokens and exact shader comments/literals. The explicit collection audit is clean.

All runtime work uses private Field/browser port 9700 and private native windows. The user's
Electron document is untouched. Failed harness attempts and the real adapter failure are retained
in the evidence, followed by the repaired results. Prose alignment has a separate handoff.

## Merge recovery

[Recovery record](merge-recovery.json), [generation log](merge-generation.log.gz),
[guard log](merge-guard.log.gz). Main changed the shader property-template spelling; its canonical
metadata was retained and the edited sidecars were refreshed through Field’s codec. Generation
passed; an unused Git index lock interrupted staging and was archived before resuming. The full
merge guard passed every task except the canonical lesson index: the typed streamer target adds a
real RenderToTexture reference. Regenerating LESSONS.md repairs that drift; all nine index tests
then pass. The repaired branch is requeued for the lander’s full guard. No local green full-guard
run after that one-line generated repair is claimed.
