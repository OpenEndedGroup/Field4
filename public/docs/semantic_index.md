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

The index holds the files as they were at the last rebuild, not every earlier
version of every box. When the workspace has changed since, Field marks the
index stale; rebuild it to pick up the changes.

## Ranking and privacy

The index is stored below `~/.afield/semantic-search/`. With the feature on,
search always matches words and symbol names. Ranking by meaning is added only
when you have configured a model and tokenizer and they load. Without one, the
results report `embedderBackend` as `disabled`, and matching is by words alone.

Indexing and searching happen inside Field on your machine. Downloading a model
preset uses the network, but your workspace and source text are never sent to
an outside search service.

## Managing it

The **Semantic search** panel sits under the **AI** section of `/welcome`:

![The semantic search panel on the welcome page](assets/semantic-search.png){ width="560" }

That is where you switch the feature on, pick a model preset and download it, point
at a model file and tokenizer by hand, and set the dimension and token limit;
**Save** writes those settings back. Three buttons below them act on the index
itself, rebuilding it, deleting it, or deleting the downloaded models. A rebuild
runs in a background worker and reports its progress on the page.

## Conversational code search is separate

**Search Documentation & Code** (`ctrl/command-shift-D`) opens a generated-answer
surface in the documentation pane. The label mentions documentation, but its
retrieval store covers Field's indexed Kotlin roots and query-selected GLSL
include libraries, not the local Lucene corpora above. With no remote vector
store configured it still writes an answer, but one with no sources behind it,
so check what it tells you against the code.

Use the `/welcome` field to find things in your workspace, and the local
semantic-search API to search Field's source or these docs. Use conversational
search when a written answer helps, and check the paths it cites.
