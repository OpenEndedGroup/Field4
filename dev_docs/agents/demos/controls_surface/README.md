# controls_surface

Demo for the 2026-10-29 controls renewal (see `dev_docs/CONTROLS_RENEWAL.md`). `build.yaml` is an
OperationTranscript; replay it into a fresh document:

```bash
uv run --project . --group backdoor python tools/field_backdoor.py --json --with-playwright \
  --document controls_surface_demo --workspace /tmp/AFieldWorkspace \
  --command 'macro write controls_surface --file dev_docs/agents/demos/controls_surface/build.yaml' \
  --command 'macro run controls_surface'
```

Box 1 is a five-control surface feeding a live figure; box 2 re-renders its own slider mid-drag.
