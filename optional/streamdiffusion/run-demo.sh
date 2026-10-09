#!/usr/bin/env bash
set -euo pipefail
field_diffusion_checkout="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$field_diffusion_checkout"
field_diffusion_capture="${FIELD_DIFFUSION_CAPTURE:-$field_diffusion_checkout/build/streamdiffusion-demo.png}"
field_diffusion_port="${FIELD_DIFFUSION_PORT:-9477}"
uv run --project . --group backdoor python tools/field_backdoor.py --json \
  --port "$field_diffusion_port" --document streamdiffusion-demo \
  --command 'macro write diffusion_renderer --file dev_docs/agents/transcripts/graphics_renderer_launch.yaml' \
  --command 'macro run diffusion_renderer' \
  --command 'wait-native-renderer renderer --frames 3 --timeout 90' \
  --command 'new-box --name "spinning diffusion"' \
  --command 'connect renderer @last' \
  --command 'launch @last --file optional/streamdiffusion/examples/spinning.kt --timeout 120' \
  --command 'wait-output @last --contains "Active TAEHV TensorRT" --timeout 240' \
  --command 'sleep 20' \
  --command "native-screenshot '$field_diffusion_capture' --uid @last --wait-frames 5 --timeout 90" \
  --command 'output @last'
