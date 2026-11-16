# Directory-backed AI4 context

A directory proxy reuses `file_proxy_mode` and `file_proxy_path`. It is one ordinary box,
not a copied tree or a box per file. Absolute, workspace and document-relative targets can
name directories. Copying an external directory into a document is deliberately refused.
The browser's directory-entry flag bypasses the extensionless-transcript FileReader path.

## Resolution and reads

`DirectoryProxyFiles` owns candidate enumeration, root-relative child resolution, exclusions
and basic nested `.gitignore` handling. Traversal and explicit child reads reject symlink
children, hidden/generated entries and parent/absolute path escapes. An explicitly selected
root may itself be a symlink; its canonical target is the granted root. Checks are cooperative,
not an atomic filesystem sandbox against concurrent replacement.

`resolveProxyAttachment` is shared by chat and the backdoor tool context. A proxy UID or unique
name reads a fresh catalogue; a `proxy:<uid>/<relative-path>` reference reads a child through
the same policy. Existing attachment excerpt/hash/pagination handling then applies unchanged.
These operations do not grant new filesystem-write tools or sandbox arbitrary Kotlin evaluation.

The displayed `contents` property is a generated description. Refresh paths do not recursively
walk the directory on the Field Runloop. The server refuses editor write-through to a directory
and re-sends its generated description rather than accepting a fake edited catalogue.

## Search

`WorkspaceSearchRequest(scope=FILE_PROXY, proxyBox=...)` selects exactly one directory proxy
in the attached document. Missing/ambiguous proxies never fall back to the workspace or cwd.
Matches keep root-relative paths and include canonical `attachmentRef` child references.
A path prefix narrows enumeration itself, not just filtering a root-wide capped scan. Existing
literal-search scopes are unchanged and never silently expand into external roots.

Semantic retrieval discovers saved proxies through document metadata. Directory children join
the workspace corpus with their owning document and box; the indexed path is the readable proxy
reference. Metadata also retains the underlying filesystem path and relative child path. Per-file
capsule identity includes the path so identical-content siblings do not overwrite one another.

The metadata-only corpus-fingerprint cache was insufficient for external files. Fingerprints now
rescan file membership and mtime/size after a five-second cache interval, independently of Field
metadata writes. This detects ordinary edits/additions/deletions, not adversarial same-size writes
with restored timestamps. Stale detection is not synchronous index rebuilding. Truncated directory
scans are recorded in index warnings, which survive in the index metadata/status message.

## Bounds and limitations

- Scan: 10,000 entries, 2,000 files, depth 32, two seconds per root/subtree; candidates <=2 MiB.
- Explicit child reads: <=10 MiB, with existing PDF text-layer extraction where applicable.
- Semantic children: bounded strict UTF-8 reads; binary/NUL/control-heavy content omitted;
  existing preview/fragment cap of 80,000 characters per file remains.
- Ignore files: <=64 KiB; basic globs/negation supported. Escaped patterns fail closed for the
  affected subtree. Global Git excludes and `.git/info/exclude` are not included.
- Absolute links remain machine-local when a document is shared. Directory snapshots/imports,
  source-tree symbol indexing and child-file editing are not added by this change.

## Verification

Focused storage/reader tests are `DirectoryProxyFilesTest`, `DirectoryProxyAttachmentTest`,
`DirectoryProxySearchTest` and `SemanticDirectoryProxyTest`. Existing `WorkspaceSearchTest` and
`AttachmentExcerptTest` cover unchanged literal-search and excerpt contracts. The `directory-proxy`
browser regression exercises an owned fixture directory; it makes no model request and is a
separate browser view, not the user's Electron tab.
