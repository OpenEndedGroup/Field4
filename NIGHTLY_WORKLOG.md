# Nightly worklog — kdoc-truth-and-guard

**Branch:** `nightly/2026-07-28/2-kdoc-truth-and-guard` (based on `1a347dc8`)
**Kind:** B — defect / ergonomics
**Mission:** make the generated documentation *true*, and make it stay true. A build-enforced KDoc
attachment guard; then fix the wrong published claims and the real code defects that last night's
KDoc-coverage campaign surfaced.

## Plan (written before execution, revised against the real code)

### Strand 1 — the durable one: a build-enforced KDoc attachment guard

`KDocExtractor.extractKDocs` attaches a comment by walking *forward* until `parseDeclaration`
recognises something. It does not stop at a line it cannot read — it skips it. So a KDoc above an
unparseable declaration is not merely dropped, it **slides onto the next declaration** and is
published as that symbol's prose (shipped on 2026-07-26: a `seg(...)` row captioned with
`FLine.hoverColor`).

New: `src/jvmMain/kotlin/afield/docs/KDocAttachmentGuard.kt`, Gradle task `checkKDocAttachment`,
wired into `check` beside `checkCodebaseReference`. Reports `DROPPED` and `STACKED` findings over
every `.kt` under `src/`; needs no server, no GPU, no network. Source-lexed (not grepped) so a
KDoc-shaped *string literal* is not mistaken for a comment and a closing marker *inside* a comment
really does end the block. Known-benign, with reasons in the file's own KDoc: file headers above
`package`, enum constants, and a file overview stacked above the first declaration's own KDoc.

### Strand 2 — the wrong published claims

Verified against the code before touching anything; case-by-case call on doc-vs-code.

### Strand 3 — the real code defects

`fbm`/`fbmPerlin`'s inert `amplitude`; `sampleZeroCrossings`'s interpolation; `kNearest`'s
`return@forEach`; `mat3`'s ignored `t`; `UKFOptions.kappa`; the stacked-KDoc sites.

Findings, decisions and evidence are recorded below as the work happened.

## Guard: first real hit list (before any source fix)

`./gradlew checkKDocAttachment`, over 624 files / 6750 KDoc blocks: **32 findings**.

Two systematic classes came out of that list and were fixed in `KDocExtractor.parseDeclaration`
itself rather than site by site:

- **`typealias` was not a declaration this parser could see** — 13 blocks. Every documented
  `typealias` in the tree (`linalg.kt`'s `Vec2`/`Vec3`/`Vec4`/`Quat`/`DualQuat`, `contours.kt`'s
  `ScalarField2D`, `stdlib.kt`'s `Vec2h`/`Vec3h`/`Vec4h`, `FunctionGraph.kt`'s `FnGraphRebuilder`,
  `ReturnValueRegistry`, `BooleanNetworkSequencing`, `units.kt`, `pf.kt`) had its KDoc dropped and
  its prose slid onto the next symbol — and `typealias` is in the reference generator's `TYPE_KINDS`,
  so those rows were published with the wrong summary or none.
- **`actual` / `expect` were missing from the modifier list** — a documented `actual fun` in a
  platform source set slid.
- Plus `constructor`, now recognised as itself purely so a KDoc above a secondary constructor *stops*
  there instead of sliding.

Guard fidelity fixes made while reading the list (both were false positives, both real behaviour of
the extractor): a multi-line annotation argument list (`@Target(` + a list of targets) is stepped
over as a unit, and an annotated enum constant (`@SerialName("wire") WIRE,`) is benign.

**32 → 11** after those. The remaining 11 are genuine per-site defects, fixed one at a time below.
