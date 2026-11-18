# Drag handles in a `three { }` scene — a document you can open

Open `metadata.edn` in Field (**File ▸ Open**, or `./field.sh <path to this folder>/metadata.edn`),
run both boxes, and drag the coloured markers in their viewers. With the visionOS app connected,
select a box and pinch the same markers in the volume.

| box | what it does | what to notice |
| --- | --- | --- |
| **1 — A curve you shape** | a cubic FLine with its four control points as free handles | the curve, its control polygon and the length label at its end follow every move, because each drag calls `draw()` again |
| **2 — A slab and a post** | a slab whose height is a handle held to a vertical line (`axis`), and a post held to the slab's top (`normal`) | drag the blue marker anywhere: it only goes up and down, and the slab follows. The orange marker slides across the top, at whatever height the slab now is |

In the browser a free handle moves on the plane facing the camera. In the visionOS viewer you pinch a
marker and move it in 3D. While one viewer drags a handle, the other shows the marker moving.

`build.yaml` beside this file is the `OperationTranscript` that built the document. Replay it into a
fresh Field with:

```bash
uv run --project . --group backdoor python tools/field_backdoor.py --json --scratch --with-playwright --port 9477 \
  --command 'macro write three_handles --file dev_docs/agents/demos/three_handles/build.yaml' \
  --command 'macro run three_handles'
```

The loose `*_code_.kt` files are what Field loads; `metadata.edn` carries portable inline copies of
the same text, and `./gradlew checkAgentDemos` fails if the two ever disagree. See the "Drag handles"
section of [`public/docs/three_output.md`](../../../../public/docs/three_output.md).
