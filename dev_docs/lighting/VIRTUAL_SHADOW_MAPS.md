# Virtual shadow maps in the fabricated renderer

Opt-in via `StandardRenderer.Config(virtualShadows = true)`; classes `FabricateVirtualShadowMap` (GPU buffers,
caster tracking) and `VirtualShadowPageCache` (page bookkeeping, no GPU) in
`src/jvmMain/kotlin/afield/graphics/scene/fabricated/`. User-facing section: `public/docs/fabricate_renderer.md`
→ "Virtual shadow maps". Demo: `dev_docs/agents/transcripts/virtual_shadow_maps.yaml`. Smoke: `docs.fabricate.virtual-shadows`.
Tests: `VirtualShadowPageCacheTest`, `FabricateShaderCompileTest.virtualShadowPathsCompile`.

## Design
- Shadow RTT becomes the page pool (32×32 pages of 128², R8 throwaway colour, `clearFirst=false`, depth LOAD). Its
  attachments are single-buffered, so pages persist across frames whatever the swapchain depth.
- Built-in shadow and lighting shaders carry the VSM path under `#ifdef FIELD_VSM`; `withVirtualShadows()` turns it on.
  Fixed bundles compile without it, because the engine refuses unassigned buffer references (MissingRefException).
- **Clipmap levels.** Level `l` has `clip << (levels-1-l)` pages a side over the light's fixed ortho box; the coarsest
  is `clip` a side (default 32 → 4096²), the finest `clip·2^(levels-1)` (default 5 levels → 512 pages, 65536²). Each
  level addresses only a `clip × clip` window centred on the camera's light uv, snapped to its pages, clamped inside
  the box. Page table = header (clip, pool, levels, debug, per-level window origin) + `levels × clip²` slots.
  Pages are keyed by global (level, x, y), so a page outside the window stays resident (LRU) and is reused when the
  camera returns. The render list packs global coords, 12 bits each (finest ≤ 4096 pages a side).
- Lighting pass picks a level from `dFdx/dFdy` of light uv, marks the page at the finest level ≥ that whose window
  holds it, samples through the table with fallback to coarser levels. Bias is `shadowBias × max(8192 / texels, 1)`.
- Coarsest level is requested whole only when `clip² ≤ pool/4` (the old guarantee); otherwise only the coarsest
  ancestor of every marked page. With the defaults (1024 coarsest pages, 1024-page pool) it is the latter.
- **Per-frame update.** `update()` runs as a `Renderer.geometryProducers` entry, i.e. once per recorded frame, after
  that image slot's fence and before recording. Marks, page table and render list are all per-image: it reads and
  clears the slot's marks (complete — the fence proves the frame that wrote them finished), decodes them with the
  windows that frame used (kept per slot), and writes the slot's table and list that this frame draws with. The
  previous Runloop preRun hook wrote whichever slot was current, which lost marks and occasionally skipped a page's
  only draw (hence the old `REDRAW_FRAMES` = 4, now gone).
- **Caster tracking.** Per caster mesh in the shadow module: model matrix (`push["model"]`), `positionBuffer` identity,
  `numVert`, and `FrameBufferGroup.writeCount`. When the write count moves, positions are read back for local bounds
  and a content hash (an unchanged rewrite does nothing). A change marks stale every resident page under the old
  and new light-uv bounds at every level; stale pages still requested are redrawn in place (no fallback flicker),
  stale pages not requested are freed. Unreadable positions → whole box stale. Added/removed casters likewise.
  `trackCasters = false` turns it off. GPU-written geometry is invisible: `invalidateWorldBox` / `invalidate`.
- Cache dropped entirely on light matrix change or `invalidate()`.

## Known issues
- Marks arrive `numImages` frames late; a newly visible region shows a coarser level for those frames.
- Eviction waits `EVICT_AFTER_FRAMES` = 8 frames unrequested, so a page in an in-flight frame's table is not reused;
  cross-frame write-after-read on the pool relies on queue order plus the RTT's own barriers.
- Host-visible buffers are written via mapped memory without explicit flush (relies on coherent memory, true on Apple).
- Caster bounds come from CPU-visible position buffers only; skinned or compute-written casters need `invalidateWorldBox`.
