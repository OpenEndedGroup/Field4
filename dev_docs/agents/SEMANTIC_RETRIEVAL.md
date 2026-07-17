# Native Semantic Retrieval

Field has a native, in-process retrieval baseline for natural-language code and
documentation discovery. It is separate from code-use telemetry:

- `SemanticRetrievalSearchRequest` answers "where is the code/docs that...?"
- `CodeUseSemanticAnalyzeCodeRequest` resolves a specific Kotlin snippet through
  the optional K2 sidecar and local code-use graph.

## Architecture

The first implementation builds semantic capsules from the existing Kotlin
source index plus markdown documentation sections, then indexes them with
Lucene:

```text
source-index declarations + docs
        ↓
semantic capsules
        ↓
Lucene BM25 / symbol fields / optional KnnFloatVectorField
        ↓
RRF-ranked results
```

Dense vectors are optional. Set these properties or environment variables to
enable ONNX Runtime Java plus the DJL Hugging Face tokenizer:

```text
-Dafield.semanticSearch.onnx.model=/path/to/model.onnx
-Dafield.semanticSearch.onnx.tokenizer=/path/to/tokenizer.json
-Dafield.semanticSearch.dimension=384
-Dafield.semanticSearch.maxTokens=512
-Dafield.semanticSearch.queryPrefix="..."
-Dafield.semanticSearch.documentPrefix="..."
```

Equivalent environment variables are:

```text
AFIELD_SEMANTIC_SEARCH_ONNX_MODEL
AFIELD_SEMANTIC_SEARCH_ONNX_TOKENIZER
AFIELD_SEMANTIC_SEARCH_DIMENSION
AFIELD_SEMANTIC_SEARCH_MAX_TOKENS
AFIELD_SEMANTIC_SEARCH_QUERY_PREFIX
AFIELD_SEMANTIC_SEARCH_DOCUMENT_PREFIX
```

Without model files, the index still runs as a Lucene BM25/symbol baseline and
reports `embedderBackend=disabled`.

## HTTP API

```text
GET  /api/semantic-search/status
GET  /api/semantic-search/model
POST /api/semantic-search/rebuild
POST /api/semantic-search/query
```

Example query body:

```json
{
  "query": "where does the semantic sidecar resolve Kotlin code",
  "limit": 8,
  "corpus": "",
  "includeCapsule": false,
  "includeSource": true
}
```

Results include vector, BM25, and symbol ranks when available. Always report
`capsuleBackend` and `embedderBackend` literally, whatever string the field actually
carries; the current capsule backend reports `source-index+workspace` (source-index
declarations plus workspace docs), not K2-resolved declaration analysis.

## Smoke Test

```bash
uv run --project . --group backdoor python tools/run_field_tests.py \
  --refresh-catalog --id examples.semantic-retrieval.lucene-baseline
```

The smoke test builds a temporary Lucene index with vectors disabled and verifies
that source and documentation searches return results.
