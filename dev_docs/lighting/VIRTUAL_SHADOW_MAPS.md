# Virtual shadow maps in the fabricated renderer

Opt-in via `StandardRenderer.Config(virtualShadows = true)`; class `FabricateVirtualShadowMap`
(`src/jvmMain/kotlin/afield/graphics/scene/fabricated/`). User-facing section: `public/docs/fabricate_renderer.md`
→ "Virtual shadow maps". Demo: `dev_docs/agents/transcripts/virtual_shadow_maps.yaml`. Smoke: `docs.fabricate.virtual-shadows`.

## Design
- Shadow RTT becomes the page pool (32×32 pages of 128², R8 throwaway colour, `clearFirst=false`, depth LOAD).
- Built-in shadow and lighting shaders carry the VSM path under `#ifdef FIELD_VSM`; `withVirtualShadows()` turns it on.
  Fixed bundles compile without it, because the engine refuses unassigned buffer references (MissingRefException).
- Lighting pass marks pages (fragment stores into a host-visible buffer), chooses level from `dFdx/dFdy` of light uv,
  samples through the page table with fallback to coarser levels; coarsest level always resident.
- CPU `update()` (bundle auto-sync preRun, and once at attach): read+clear marks, LRU allocate, write per-image page
  table and render list, set `instanceCount` on every shadow mesh and on the clear mesh (`prefab-vsm-clear`, depth ALWAYS).
- Cache invalidated on light matrix change or `invalidate()`. Moving casters are NOT detected.
- Levels are halvings of the light's fixed ortho box, not camera-centred clipmaps (the article's design).

## Known issues
- New pages are redrawn for `REDRAW_FRAMES`=4 updates: preRun ticks and recorded frames are not 1:1, and a single draw was
  observed to be missed (shadow entirely absent) before this.
- Same mismatch makes the mark read-back lossy per tick (counters showed "requested 64" = coarsest only on some ticks);
  eviction age is counted in updates, so under pool pressure a visible page could be evicted. A GPU-side frame counter
  or reading marks per renderer frame would fix it.
- Host-visible buffers are written via mapped memory without explicit flush (relies on coherent memory, true on Apple).
