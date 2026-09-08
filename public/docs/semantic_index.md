# The Semantic Index

The search field on [`/welcome`](history_overview.md) takes the keyboard focus
as soon as the page opens, and what you type into it runs two searches over the
current workspace at once. One is the ordinary workspace search, over document
metadata and box text. The other is the semantic index, which joins in when
semantic search is enabled and an index has been built.

Results from both are merged by document. Semantic hits are labelled, ranked
ahead of ordinary matches within each document, and limited to short source
previews. Enter opens the first document in the results. If the semantic request
fails or runs past the welcome page's short timeout, you still get the ordinary
ones.

## What the local index contains

A rebuild creates three selectable corpora:

- **workspace**: current sidecar-backed source/text properties found in documents
  under the configured workspace root, split into box/property and declaration
  fragments;
- **field-internals**: declarations from Field's indexed Kotlin source roots;
- **documentation**: Markdown sections under `public/docs`.

The welcome search requests only the `workspace` corpus. Other clients of
`/api/semantic-search/query` can request one corpus, several corpora, a workspace
document, a box, or a property.

This is an index of the current files scanned during rebuild, not every historical
version of every box. Field marks the index stale when its workspace fingerprint
no longer matches; rebuild to incorporate current changes.

## Ranking and privacy

The index is stored below `~/.afield/semantic-search/`. Lucene BM25 and symbol
fields are always available when the feature is enabled. Dense-vector ranking is
optional: it is added only when a configured ONNX model and tokenizer load
successfully. The result reports its `embedderBackend`; `disabled` means the
search is lexical and symbolic, with no meaning-based ranking.

Indexing and querying run in the Field JVM. Downloading a model preset uses the
network, but workspace/source text is not sent to an external search service by
this local retrieval path.

## Managing it

![The semantic search panel on the welcome page](assets/semantic-search.png)

The **Semantic search** panel sits under the **AI** section of `/welcome`. That
is where you switch the feature on, pick a model preset and download it, point
at a model file and tokenizer by hand, and set the dimension and token limit;
**Save** writes those settings back. Three buttons below them act on the index
itself, rebuilding it, deleting it, or deleting the downloaded models. A rebuild
runs in a background worker and reports its progress on the page.

## Conversational code search is separate

**Search Documentation & Code** (`Ctrl/Cmd+Shift+D`) opens a generated-answer
surface in the documentation pane. The label mentions documentation, but its
retrieval store covers Field's indexed Kotlin roots and query-selected GLSL
include libraries, not the local Lucene corpora above. With no remote vector
store configured it will still produce an answer, with nothing behind it, so
don't treat it as source-of-truth search.

Use the `/welcome` field to locate current workspace material. Use the local
semantic-search API for explicit source/docs corpora. Use conversational search
when a prose answer is useful, and verify its cited paths against the code.
