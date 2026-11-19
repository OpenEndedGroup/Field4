# Squircles by one gauge — a document you can open

Open `metadata.edn` in Field (**File ▸ Open**, or `./field.sh <path to this folder>/metadata.edn`).

| box | what it does | what to notice |
| --- | --- | --- |
| **squircle_morph** | the five `SquircleStyle`s (superellipse, one cubic, Chromium, Figma, clothoid) at one gauge, each over its exact curvature against arc length, with a **Gauge** slider | drag the slider: the superellipse's curvature stays smooth, Figma's steps where its cubics meet the arc, the clothoid ramps linearly |
| **squircle_rect** | `squircleRectFrom` with Figma and clothoid corners over circular ones, with curvature combs | the circular corner's comb jumps from zero to `1/r`; the squircle corners rise into it |

`build.yaml` beside this file is the `OperationTranscript` that built the document. Replay it into a
fresh Field with:

```bash
uv run --project . --group backdoor python tools/field_backdoor.py --json --scratch --with-playwright --port 9477 \
  --command 'macro write squircles --file dev_docs/agents/demos/squircles/build.yaml' \
  --command 'macro run squircles'
```

The loose `*_code_.kt` files are what Field loads; `metadata.edn` carries portable inline copies of
the same text, and `./gradlew checkAgentDemos` fails if the two ever disagree. The page is the
Squircles section of [`public/docs/fline_examples.md`](../../../../public/docs/fline_examples.md).
