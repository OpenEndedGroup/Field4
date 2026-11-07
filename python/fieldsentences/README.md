# Sentence parsing for Field

`fieldsentences` ships in Field's `python/` tree. It produces schema-2 JSON: tokens,
a constituency tree and UD/basic/collapsed dependency edges. Kotlin drawing, layout and
retained examples are in `afield.graphics.text.sentences`; they need no Python installation.

Choose **Sentence diagrams / Stanford parser** in first-run setup and approve **BUILD**,
or run `./gradlew prepareSentenceParser`. This creates the separate
`optional/sentence-parser/.venv`, installs the locked packages, downloads the English
Stanza models and verifies a real CPU parse. It starts no background server. The optional
environment keeps Torch separate from the interpreter used by Python boxes/JEP.
Linux and Windows resolve CPU Torch wheels; parsing defaults to CPU on every platform.

From the repository root, after preparation:

```bash
optional/sentence-parser/.venv/bin/python python/fieldsentences --serve 9500
```

On Windows use `optional\sentence-parser\.venv\Scripts\python.exe python\fieldsentences --serve 9500`.
The server binds only to `127.0.0.1`, loads the model once and serializes requests through it.
It defaults to two CPU inference threads; `--threads` changes that. Use `--device cuda` only
with an environment containing a compatible CUDA Torch build.

For a single parse:

```bash
optional/sentence-parser/.venv/bin/python python/fieldsentences "The quiet water carries the light"
```

`--download` prepares models, `--lang` chooses a language, `-o` writes JSON, and `-` reads stdin.
The optional `--backend corenlp --url http://localhost:9000` talks to an externally managed
CoreNLP server; Field does not install that server. Stanza's UD-to-classic/collapsed
conversion is a drawing convention, not an assertion of identical Stanford Dependency analyses.

In a Field Python box, `from fieldsentences.client import parse` supplies a stdlib HTTP
client. It talks to the same server; it does not load Torch inside the JVM. Kotlin uses
`Sentences.fromServer(text)` or `SentenceDiagram(text)`.

Test the conversion machinery with `python -m unittest discover -s tools -p test_field_sentences.py`.
Inference and CLI checks run when Stanza models are present, otherwise they explicitly skip.
For an existing interpreter, `python tools/prepare_sentence_parser.py --verify-only --python <path>`
proves a live parse without creating another environment. See `public/docs/sentence_diagrams.md`
for four complete drawing examples and the shallow-3D reading playground.
