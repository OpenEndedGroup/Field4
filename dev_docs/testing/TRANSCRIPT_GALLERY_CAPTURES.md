# Transcript gallery previews

The gallery reuses existing documentation captures. Two previews added on 2026-10-06 needed
fresh captures because there was no published image of the corresponding transcript.

## Simulation chain

Run from the repo root in a graphical session with its matching `DISPLAY` and `XAUTHORITY`.
The retained image was captured on Linux/NVIDIA (RTX 4090), under XWayland.

```bash
VK_DRIVER_FILES=/usr/share/vulkan/icd.d/nvidia_icd.json \
uv run --project . --group backdoor python tools/field_backdoor.py --json --timeout 180 \
  --command 'macro write four_simulation_chain --file dev_docs/agents/transcripts/four_simulation_chain.yaml' \
  --command 'macro run four_simulation_chain' \
  --command 'new-box --name gallery-paint --alias gallery_paint' \
  --command 'connect sim_fluid gallery_paint' \
  --command 'launch gallery_paint --file tools/captures/simulation_gallery_input.kt' \
  --command 'sleep 4' \
  --command 'native-screenshot /tmp/gallery-simulation.png --uid sim_renderer --wait-frames 3'
```

The input driver supplies a circular drag through the transcript's own push parameters. The
fluid and distance bands are rendered by the actual GPU pass chains. The moving capture is
illustrative; its precise shape depends on frame timing.

The separate `four.simulation-chain.smoke` on this host produced genuine readbacks but failed
its coast-motion gate: centroid displacement was 0.0094 UV against the 0.01 minimum over 40
frames. This gallery refresh does not claim that smoke passed and does not change its threshold.
The tiny smoke splat was not used as the preview.

## Kratos flow

Prepare the pinned optional worker with `uv sync --project optional/kratos --frozen`. In this
checkout the existing venv contained only the thermal modules; sync installed the missing fluid,
linear-solver, meshing and structural modules without changing the dependency files.

```bash
uv run --project . --group backdoor python tools/field_backdoor.py --json --with-playwright --timeout 180 \
  --command 'macro write kratos_flow_playground --file dev_docs/agents/transcripts/kratos_flow_playground.yaml' \
  --command 'macro run kratos_flow_playground' \
  --command 'launch kratos_flow' \
  --command 'wait-output kratos_flow --contains data-background --timeout 120' \
  --command 'run kratos_flow --value "displayClose(\"controls\"); displayClose(\"geometry\"); displayClose(\"status\")"' \
  --command 'page-eval --file tools/lighting/capture_background.js' \
  --command 'screenshot-box kratos_flow /tmp/gallery-kratos-flow.png --no-open-outputs --padding 16'
```

The capture shows the real solved streamlines and mid-plane velocity slice. All three Newton
steps converged; the default 729-node, 3072-tetrahedron solve returned Re 12 and speed range
0.175–1.454 m/s. Closing the setup panels is framing, not a replacement of the result.

Inspect both PNGs before copying them to `public/docs/assets/transcript_gallery/`. Native
screenshots and the browser screenshot have their original pixels; no image processing is used.
