# Restore recording animation and expose procedural-only study controls

Frozen findings: `a86d30f62c84-02`, `6af7378331b4-01`, `aff7b1698617-01`.

The image study now offers shader reload and source animation only in procedural mode. All nine authored recording/source containers restore the captured animation setting in their existing finally block, alongside time and recording state.

Actual private Vulkan verification preserved the replacement study's target, screen and exact captured pixels after closing the older study. The downloaded study already contains the matching identity guard; it needed no edit. Actual image/procedural DOM controls and procedural shader reload passed. Three real GPU frames were saved, and both normal recording completion and Stop restored time and animation. These checks used reduced source-mode targets, without optional NVIDIA denoising; no public media was replaced. An early fixed-frame readback assertion was replaced with waiting for actual readback completion; the diagnostic logs are retained.

The editor formatter processed every changed Kotlin body; parsed container, token, raw-string, execution-boundary and untouched GLSL checks passed. No metadata document exists in this demo folder.

Checkpoint: 347/405 addressed, 58 open. Mechanical coverage: Full corpus formatter receipts are in batch 56, with immutable history exclusions repaired in batch 57; all changed batch 59 bodies formatted and checked. Full guards are delegated to the lander.
