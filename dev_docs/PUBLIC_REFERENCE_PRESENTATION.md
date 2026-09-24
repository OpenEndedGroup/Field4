# Public reference presentation

The September 2026 sweep started with a FieldML syntax reference rendered as fifteen
large headings, each followed by an unhighlighted fragment. The source was an ordinary
Markdown table. A global `:has(td code)` rule hid its headers and rearranged its cells,
so the same styling also damaged comparison and measurement tables.

## Authoring rule

A code reference entry needs a highlighted example or signature **and** one or two
sentences explaining its behavior. Include the result, argument order, units, boundary
condition or a reason to choose it where those help a reader. A type alone is not an
explanation. Keep measurements and comparisons as real tables with descriptive headers.

For an example/explanation pair, use:

```markdown
<div class="field-reference" data-code-lang="fieldml" markdown>

| Example | What it means |
| --- | --- |
| `let x = 6 in x * 7` | **Local binding.** Limits the name to the expression after `in`. This evaluates to `42`. |

</div>
```

Use `data-code-lang` without `field-reference` for other code tables that should keep
their columns on a phone. Choose the actual language: `kotlin`, `fieldml`, `stacksl`,
`rectsl`, `petri`, `fnrewrite` or another grammar the existing code player supports. Do not guess a language
for paths, keyboard shortcuts, shell options or mixed-language comparisons. Already
highlighted declarations in generated reference pages retain their existing tokens.

The shared highlighter uses the same loaded library as the code player. FieldML has
its own grammar for its keywords, types, operators and nested comments. There is no
language auto-detection and no replacement of a code fragment's text with a title.
In Zensical tables, pipes inside backticks do not need backslash escaping; an escaped
pipe is rendered with the literal backslash. Inspect the result after editing syntax.

## Sweep scope

The initial source census found 1,167 code-containing Markdown tables on 96 pages
outside asset bundles: 251 tables on 76 pages outside `reference/`, and 916 on the 20
generated reference pages. The 251 include generated API inventories as well as authored
guides; these counts are an inventory, not a claim that every row lacked documentation.

The shared layout replacement covers all of these, preserving table semantics and
column headings. The original FieldML pass explains all 15 syntax forms and 113 prelude
entries. The approved 31-guide sweep now applies the same treatment to:

- Language guides: StackSL cheatsheet and examples, RectSL, Petri, FieldML's host bridge,
  Clojure, Functions/FnRewrite, pattern rewriting and pattern language.
- Geometry: FLine, mesh, spatial geometry, imaging, solid notation, geometry from disk,
  Three.js output and vision geometry.
- Runtime and audio: animation time, running code, file resolution, GPU state, Faust,
  ONNX, Interaction Nets, spatial Pat, sound, sound paths and audio rendering/analysis.
- Settings: neural particles, path tracing and state estimation.

Grouped API name lists in imaging, mesh, spatial geometry and geometry loading now have
individual entries, organized by task, with explanations grounded in the owning source
and links back to its API reference. FLine's terse transform, path-editing, Boolean and
measurement panels now explain copying, parameterization and approximation limits.
Existing useful prose, comparison columns and executable capture examples are retained.

Kotlin reference fragments distinguish calls, types and punctuation while retaining
Kotlin's string/comment handling. Petri and FnRewrite have explicit grammars. The renderer
comparison allocates space to its call column and scrolls within the table on phones;
two-column references instead stack code and explanation together.

The vector API treatment lives in `tools/linalg-generator`: examples and heap mutation
notes come from its algebra model, and the emitter owns the layout. Regenerating produces
no changes to the vector implementation. The Field Audio service changes live in
`services/field-audio-ai`: endpoint docstrings, schema descriptions and the docs generator
now explain all 19 HTTP operations, 33 schema rows, 16 job states and 16 error codes.
The public service reference is synchronized from that generated output. These source
changes are inside the service submodule and must be retained with its generated contract.

## Remaining source documentation

The proposal's count of 1,803 empty generated summaries was incorrect. A fresh census of
actual highlighted declaration rows finds **5,486 empty summaries across 233 Kotlin source
files**. This is a count of empty cells, not unique user-facing entry points; it includes
constructor properties and overloads. Nonempty but unhelpful prose needs review too.

This source-KDoc work is **not completed by the guide sweep**. The shared layout fix applies
to those pages, but adding explanations requires reading the owning declarations and
regenerating with `generateCodebaseReference`. Do not fill cells with descriptions inferred
only from member names or hand-edit generated reference Markdown. The existing
[kdoc worklist](kdoc_worklist.md) is a source-oriented starting point; its declaration
counts differ from the rendered-cell census.

## Verification

Build the whole site with existing media:

```sh
FIELD_DOCS_RUNNER_MODE=never uv run --project . --group docs python tools/build_zensical_docs.py --clean --strict
```

Inspect `fieldml_cheatsheet/#syntax`, a three-column language reference, a generated
Kotlin declaration reference and a wide comparison table at desktop and phone widths.
Check that code has distinct token colours, text survives highlighting unchanged,
headers remain visible on comparison tables, long declarations wrap, and overflow stays
inside the table. Example/explanation references stack compactly on phones, keeping the
code and its explanation together; their headers remain available to assistive tools.

Verification recorded for this sweep:

- Strict HTML build passed, with 633 built pages and all 1,621 local targets resolving.
  The existing theme-only `404.html#__skip` exception and four ray-tracing placeholder
  figures remain; this change did not regenerate captures.
- `generateLinalg` and `checkGeneratedLinalg` passed. The Field Audio deterministic docs
  tests and public snapshot synchronization checks passed.
- `FieldMLCheatsheetCoverageTest` and `StackSLVocabularyCoverageTest` passed: seven tests,
  no failures. Their readers now understand a name/type pair and an optional example
  line in a reference cell.
- `tools/test_docs_reference_highlighting.cjs` passes against the site's highlight.js
  11.8.0 build, covering token colors, comments, embedded Kotlin, HTML escaping and
  unchanged code text.
- Six representative pages were checked at 1280px and 390px in a real browser, with
  screenshots reviewed: audio renderers, FieldML syntax, spatial geometry, vector API,
  service contract and RectSL. No page-wide overflow or JavaScript errors occurred.
