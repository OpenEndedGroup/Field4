# A three-step tour of an existing document

`build.yaml` is an `OperationTranscript` that builds two ordinary boxes (`numbers`, `squares`) and a
guide box (`tour`) that walks through them, the example in "A tour of your own document" on
`public/docs/interactive_guides.md`. Replay it into a fresh document:

    uv run --project . --group backdoor python tools/field_backdoor.py --json --port 9477 \
      --document own_tour --create --with-playwright \
      --command 'macro write tour --file dev_docs/agents/demos/slideshow_own_tour/build.yaml' \
      --command 'macro run tour'

then open `https://localhost:9477/index.html?file=own_tour` (or the HTTP mirror on 19477) and
follow the note's links. Step 1 selects `numbers`, step 2 selects `squares` and marks line 3,
step 3 offers Start again and Finish.
