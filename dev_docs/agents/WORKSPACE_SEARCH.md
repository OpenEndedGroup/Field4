# Scoped saved-file search

`WorkspaceSearchRequest` provides Kotlin-native literal text search to AI4 chat, tool programs
and the backdoor/MCP surface. It replaces the approach proposed in PR #92: no `rg`, subprocess,
temporary output file, index, or extra runtime dependency.

Example tool arguments:

```json
{"scope":"WORKSPACE_FILES","query":"moduleHere","pathPrefix":"myDocument","extensions":["kt"],"ignoreCase":false,"maxMatches":80}
```

`scope` and `query` are required. The scope is one of:

| Scope | Root | Selection |
| --- | --- | --- |
| DOCUMENT_FILES | Attached session's document directory | Saved metadata, sidecars and other text files; no backups |
| DOCUMENT_HISTORY | Attached document's `backups` directory | Raw saved backup text, with backup-file coordinates |
| WORKSPACE_FILES | Configured `home` workspace | Saved workspace text, excluding backups throughout |
| FIELD_KOTLIN_SOURCE | Configured `app/src` | Only `.kt` files across source sets |

Missing roots fail; they never fall back to another scope or cwd. History is not automatically
added to document/workspace results. Hidden entries, `build`, `node_modules`, and
`history_screenshots` are excluded. Symlinks are not followed. The configured root itself may
resolve through a symlink, but the document's `backups` link and symlinked prefixes are refused.

`query` is literal single-line text, 1–256 UTF-16 code units. Whitespace is preserved. Case
matching follows Kotlin's locale-independent character comparison, not full Unicode case folding.
`pathPrefix` selects a relative file or directory with `/` separators, at most 1,024 units;
absolute paths, drive prefixes, NUL, backslashes and `..` are refused. `extensions` is an optional
list of up to 16 alphanumeric suffixes without dots, case-insensitive; it cannot broaden source
scope beyond `.kt`. Regex, context expansion, globs, multiline matching and Git-ignore parsing
are deliberately outside this version.

The result contains `success`, `scope`, `root`, structured `matches`, `complete`, `stopReason`,
`filesSearched`, `bytesRead`, `skipped`, and a fixed diagnostic `message`. Each matching source
line is returned once, with its first matching `column`, an excerpt, `excerptStartColumn`, and
`excerptClipped`. Lines and UTF-16 columns are 1-based. Paths are root-relative with `/` separators.
LF, CRLF, lone CR and the final unterminated line are supported.

This searches **saved files**, not unsaved editor text. Finding serialized source inside an EDN
backup does not produce live box-source coordinates. Use `DocumentSourceReadRequest` when inspecting
the live/saved/executed revisions of a known box. Searching never saves a document.

## Bounds and partial results

- Global matching-line limit: 80 by default, valid range 1–500. Reaching it stops immediately and
  reports `MATCH_LIMIT`, even if there might be no further matches.
- At most 256 KiB serialized result, including JSON escaping, with `RESPONSE_LIMIT` before retaining
  another row. Excerpts are at most 512 UTF-16 units, preserving surrogate boundaries.
- At most 2 MiB of contents per file, with one extra byte to detect a growing/oversized input;
  size metadata is only an early exclusion. Total actual input is capped at 32 MiB.
- At most 10,000 visited entries and 32 traversal levels below the selected prefix. Traversal uses
  directory streams and does not collect/sort the whole tree. Order is unspecified.
- Cooperative two-second monotonic deadline and thread-interruption checks during traversal,
  chunked reading and line scanning. Files/streams are closed on every stop. A blocked OS filesystem
  call is not subject to a hard wall-clock guarantee. Calls on Field's Runloop are refused.

`skipped` counts oversized, non-text (invalid UTF-8 or NUL), unreadable and detected-changing files.
A budget stop or skip makes `complete=false`. Root/prefix failures make `success=false`;
per-file failures retain other matches. A partial no-match result is not evidence of absence.
Results are not an atomic snapshot; identity/size/mtime checks detect some concurrent changes,
but this is not a hostile-filesystem sandbox.

Implementation: `afield.ai.tools.WorkspaceSearch`; one shared registration in
`AgentToolRegistrations.kt`; a common provider factory binds configured roots for chat/backdoor.
The request declares the `FILESYSTEM_READ` effect and participates in normal availability and
withholding rules. No semantic/symbol search behavior is changed.

## Validation

Focused tests: `WorkspaceSearchTest` covers scope/filter selection, JSON arguments, literal and
Unicode coordinates, bounds, changing/binary files, symlinks, cancellation and cleanup.
`WorkspaceSearchIntegrationTest` covers shared registry availability/metadata/dispatch, all four
chat helper paths, and the backdoor's saved/live distinction.

Verified in a provisioned Field checkout after merging current `main`:

```sh
./gradlew jvmTest --tests 'afield.ai.tools.WorkspaceSearchTest' --tests 'afield.ai.tools.WorkspaceSearchIntegrationTest'
./gradlew compileKotlinJvm
```

Both focused test classes and the full JVM source compilation passed. The merge regeneration
also passed the generated-reference, KDoc, box-idiom, citation and documentation guards.
