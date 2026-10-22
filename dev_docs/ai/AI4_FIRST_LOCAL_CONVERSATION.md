# AI4's first conversation with a local model

On 2026-10-22 AI4 held its first real conversation with a local model: qwen3.8-27b on a llama.cpp
server with a 65,536-token window, in the `morechat` document. Over fourteen turns it went from "print the
first 100 primes" to a 10,000-prime Ulam spiral drawn as `FLine` squares and coloured by local prime
density through `Colormaps.sample("inferno", t)`, with a gamma lift. The model was good: it planned,
patched, ran, looked at its captures, and when the user challenged it, it worked out what had gone wrong.
Field let it down in several places. This note records each one and what changed, and describes the
loop that turns the next conversation into the next fixes.

The evidence is the turn logs `~/.afield/logs/ai4_tool_io/2026-10-22T074222…` to `…T08…`, the
document's `*_ai4_chat_history_.md`, and the captures in `~/.afield/logs/ai4_box_captures/`.
`tools/ai4_conversation_review.py --since 2026-10-22T07:40 --merge-file-key` reproduces the list below.

## What went wrong, and what changed

| Turn | What happened | Change |
| --- | --- | --- |
| "you can put it in this box" | `ListContentsRequest` on the new, empty box threw "No text content available". | An empty box is an answer: the result says so and names SetBoxPropertyRequest (`listFileContents` in `Ai4Installer.kt`). |
| same | `503: Loading model` from the server while it loaded weights; two ~1 s retries gave up. | A 503 "Loading model" is waited out like a rate limit (`temporaryRateLimitDelay`). |
| "perhaps using FLines" | Ten lookups in a row (symbol search, retrieval, attachments) to find `fline { }`, `lines(...)`, `filled`, `color`. The page that teaches exactly this, `fline_examples.md`, never came up. | Semantic search was broken (below) and is rebuilt and measured; `TutorialCodeSearchRequest` finds worked box code by task: it is a query on semantic search's new `tutorials` corpus, which indexes the ~260 tutorial documents (the materialized transcripts and the graphics curriculum) box by box, exactly as a workspace is indexed, and returns each match's code files. A one-line FLine primer in the prompt was tried and withdrawn: the fix belongs in search. |
| same | `WorkspaceSearchRequest` refused `extensions: [".md"]`, then `pathPrefix: "public/docs"` under the source scope ("does not exist"). | A leading dot is accepted, `src/` is stripped from a source prefix, and misses name the scope root and say that the docs are reached with `SemanticRetrievalSearchRequest` or `AttachmentRequest`. |
| same | One symbol search returned 17k characters, mostly one unrelated function body. | Bodies are capped at 3k each and 6k per search (`withBodyBudget`). Semantic searches no longer repeat the embedder description. The ranking is unchanged: see "Symbol search" below. |
| "let's do 2" | `400: request (65833 tokens) exceeds the available context size (65536 tokens)`. Every turn's prompt carried the full ~24k-character instructions, and every copy stayed in the stateless history, which was capped only by item count. | `StatelessHistory`: at each turn boundary the finished prompt becomes its durable text and old tool outputs and images shrink; when the window is known (`local.contextTokens`, `AFIELD_LOCAL_AI_CONTEXT_TOKENS`, or learned from the refusal) whole old turns are dropped to fit; a refusal for size is fitted and retried once. |
| "can you try that again?" | After the failure the provider thread was discarded, and the next turn was reseeded from the prose transcript without a single tool result. | Stateless threads survive a failed request; a request that failed before an answer stays in their history. |
| "ok, are you only plotting odd numbers" | Completed with no answer: 58.5k input tokens left no room for a reply. | The input budget keeps room for the answer (`StatelessHistory.inputBudget`). |
| the spiral itself | The code walked `n += 2`, so it placed odd numbers only and filled about half its grid. The model called the capture "a textbook Ulam spiral" with "signature diagonal lines"; the picture's strongest feature was a horizontal and vertical cross. Its capture note said what to look for, and the caption put that expectation before the request to describe. | The caption now asks for a description first, presents the note as the model's own prior expectation, and says a picture does not check the computation (`captureReadingInstruction`). A curated lesson says the same about named constructions. |
| adding the density pass | `PatchBoxTextRequest` with `insert: true, startLine: 36` landed inside a `while` loop: the model had counted lines in a `\n`-escaped string. | A curated lesson: anchor insertions on a unique line with find/replace. |

Each turn's log now also records `request`, the person's own words, apart from the instructions.

## Semantic search: every vector was zero

`OnnxSemanticEmbedder.encode` read `result.firstOrNull()?.value`, the ONNX result's Map.Entry value (the
`OnnxTensor` itself) rather than its array, and pooling fell through to zeros. From 2026-07-06, when semantic
search arrived, every query vector and all 206,702 indexed vectors were zero; the dense half matched nothing, and
kNN returned the same two arbitrary capsules for every query. The model itself was fine (granite-embedding-97m
returns real hidden states). Fixed, with a regression test on a 425-byte model committed with the tests and one on
the installed model; a zero vector for non-empty text now throws, and an index with an older `vectorFormat` is
stale and its vectors are not searched. The old index was deleted.

Measured on an eval taken from the repository rather than written for it (`RetrievalEval`: 879 anchor-text
queries from cross-links between authored pages, 252 `why` lines from the agent transcripts), with a
documentation-only index rebuilt each time (~20 minutes on CPU; CoreML is ten times slower on the quantized model):

| Change | Anchor hit@1 / MRR@10 | `why` hit@1 / MRR@10 |
| --- | --- | --- |
| Real vectors, old chunking (one section per `#` line) | 32.3% / 0.518 | 6.3% / 0.322 |
| `MarkdownChunker`: headings outside fences only, breadcrumbs, markup stripped, long sections split | 33.7% / 0.524 | 8.3% / 0.350 |
| Redirect stubs not indexed; identical chunks indexed once | 50.3% / 0.641 | 48.8% / 0.648 |
| Tutorial mirrors moved out of documentation into a `tutorials` corpus | 55.9% / 0.685 | 50.8% / 0.673 |
| Text indexed twice, exact and split-and-stemmed (`allStem`, weight 2.0) | **57.0% / 0.696** | **51.2% / 0.693** |

Tutorial search (`TutorialCodeSearchRequest`), on 146 cases from the transcripts' `docs` links (a linked page's
own title is the query; the tutorials linking to it answer it): code only 51.4% / 0.601; with a summary capsule per
tutorial (its description and box names, and per lesson for the graphics curriculum) 69.2% / 0.752; with the
stemmed copy 74.7% / 0.803 (hit@5 89.7%).

The stemmed copy is there because a split-and-stemming analyzer alone (`fline_measure` → fline, measure;
"FLines" → fline) raised top-5 recall everywhere but lost first place (anchor hit@1 56.1% → 50.9%). Indexing both
forms and scoring them together kept the exact matches first and let the stemmed ones fill in; every set improved
up to weight 1.0 and then plateaued.

The chunker matters most to the dense half (dense-only MRR 0.345 → 0.415 on anchors). Each tutorial ships
`guides/<page>.md` redirect stubs quoting the transcript's description, and a copy of the same `AGENTS.md`; they took
first place in 196 of 231 `why` misses. The corpus filter now runs inside the kNN and BM25 queries: filtered
afterwards, a documentation search for "display" kept none of the global top 80. Hybrid beats lexical-only (0.596 /
0.593) and dense-only (0.493 / 0.504) on both sets. Making the generated reference opt-in would raise queries that
want authored pages and zero the 161 that want reference pages, a net loss, so it stays in, under its own kind
(`docs-reference`, beside `docs-section` and `tutorial-code`).

## Symbol search: no change survived measurement

Labels from behaviour, not from me: for each of 60 logged `WorkspaceSymbolSearch` calls, the symbols the agent then
wrote in box code (resolved by Kotlin's scope rules: top-level, the implicit `Box` receiver, or a type the code
names) or opened. Against that, hand bonuses for box-facing API, plural folding, type-aware boosts, and a usage
prior learned from the box code Field teaches (docs fences and tutorial sidecars) all stayed within noise of the
current ranker (MRR 0.609) or fell below it. The ranker is unchanged.

## The loop

1. **Review** a conversation:

    ```bash
    uv run --project . python tools/ai4_conversation_review.py --since <ISO timestamp> --merge-file-key
    ```

    It reports four signals per turn, each with where its fix belongs:

    - tool failures that are not the box's own errors (fix the tool);
    - runs of four or more lookups (put what they found in the instructions);
    - failed turns, empty answers and peak input against the window (the bridge);
    - the person's corrections, which mark the claim before them (a curated lesson, or a better check).

2. **Fix in code first.** As AGENTS.md says, a corrective paragraph is a bug report against a tool. Every
   change in the table above except two is a code change.
3. **Curate what code cannot fix** in `src/jvmMain/resources/ai4/agent_lessons.md` under `## Curated`. These
   bullets are read into every AI4 turn that has the graph tools, curated first, capped at 2,400 characters,
   so keep them few and general. `agent-eval --distil` rewrites only `## Distilled`, from eval runs.
4. **Measure** with `agent-eval` (lessons `on`/`off`) where a task reproduces the problem; for the local model,
   rerun the conversation's requests and compare the review.
