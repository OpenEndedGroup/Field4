# Field language extensions

Write a few lines of your own language inside a triple-quoted string in a Kotlin
box and Monaco sees one long string, coloured all the way through. Field's editor
keeps a small browser-side registry that lets you change that, for languages that
are useful before they are fully integrated into Field. Register an extension and
your language gets syntax highlighting inside the embedded region, its own
diagnostics, and a flow/context lens, and nobody has to edit the Kotlin/JS Monaco
bindings to add the next one. RectSL is the experiment this was built for.

StackSL uses this API internally while keeping its older global hooks available.
That makes StackSL both a real consumer and the reference implementation.

## Loading

The registry is available in the editor page as:

```diffstatic {data-lang=javascript}
window.FieldLanguageExtensions
```

It is also exposed through convenience functions:

```diffstatic {data-lang=javascript}
window.installLanguageExtensions(editor, monaco)
window.updateLanguageExtensions(editor)
window.buildLanguageContextPayload(editor, resolveContext, options)
window.fieldLanguageCompletionItems(editor, model, position, context)
window.findLanguageEmbeddedRegions(model, optionalLanguageOrExtensionId)
```

The core editor calls `installLanguageExtensions(...)` when Monaco starts and
`updateLanguageExtensions(...)` when the selected property or model language
changes. An out-of-tree extension that loads after Monaco starts can still call
`FieldLanguageExtensions.register(...)` and then update the current editor.

## Extension contract

An extension is a plain JavaScript object:

```diffstatic {data-lang=javascript}
FieldLanguageExtensions.register({
  id: "rectsl",
  label: "RectSL",
  languageIds: ["kotlin", "rectsl"],

  install(editor, monaco, registry) {
    // Add editor actions or event listeners.
  },

  findEmbeddedRegions(model) {
    // Return source regions embedded in the current Monaco model.
    return []
  },

  updateEmbeddedHighlight(editor, context) {
    // Set Monaco decorations for embedded source regions.
  },

  updateDiagnostics(editor, context) {
    // Set Monaco markers.
  },

  updateFlowLens(editor, context) {
    // Set decorations for value flow, consumers, producers, or timing links.
  },

  buildContextPayload(editor, resolveContext, options) {
    // Return { key, editor, html, isLiveValue } for the lower-left context badge.
    return null
  },

  provideCompletionItems(editor, model, position, context) {
    // Return FieldSuggest-compatible completion items when the cursor is inside this language.
    return { suggestions: [] }
  }
})
```

`context` contains:

```diffstatic {data-lang=javascript}
{
  editor,
  model,
  monaco,
  registry,
  options
}
```

Each hook is optional. Hook failures are caught and reported to the browser
console so one experimental language does not take down the editor.

## Embedded regions

Embedded language support starts with a region detector. A region should include
absolute offsets into the Monaco model so later hooks can map tokens back to
editor positions:

```diffstatic {data-lang=javascript}
{
  source: "0 0 xy 1 square",
  bodyStart: 125,
  bodyEnd: 142,
  headerStart: 108,
  headerEnd: 115
}
```

The registry annotates returned regions with:

```diffstatic {data-lang=javascript}
{
  languageExtensionId: "rectsl",
  languageLabel: "RectSL"
}
```

## RectSL sketch

This example detects Kotlin triple-quoted strings whose first non-empty line is
`#rectsl`.

```diffstatic {data-lang=javascript}
const rectslHeader = /^(?:[ \t]*(?:\r?\n|$))*[ \t]*#rectsl\b[^\n]*(?:\r?\n|$)/i

function findRectSLEmbeddedRegions(model) {
  if (!model || model.getLanguageId() !== "kotlin") return []

  const source = model.getValue()
  const regions = []
  const triple = /"""/g
  let match
  while ((match = triple.exec(source)) !== null) {
    const contentStart = match.index + 3
    const close = source.indexOf('"""', contentStart)
    if (close < 0) break

    const content = source.slice(contentStart, close)
    const header = rectslHeader.exec(content)
    if (header) {
      const headerOffset = contentStart + header[0].search(/#rectsl\b/i)
      regions.push({
        source: content.slice(header[0].length),
        bodyStart: contentStart + header[0].length,
        bodyEnd: close,
        headerStart: headerOffset,
        headerEnd: headerOffset + "#rectsl".length,
      })
    }
    triple.lastIndex = close + 3
  }
  return regions
}
```

Register it:

```diffstatic {data-lang=javascript}
FieldLanguageExtensions.register({
  id: "rectsl",
  label: "RectSL",
  languageIds: ["kotlin"],
  findEmbeddedRegions: findRectSLEmbeddedRegions,

  updateEmbeddedHighlight(editor, { model, monaco }) {
    const decorations = []
    for (const region of findRectSLEmbeddedRegions(model)) {
      const start = model.getPositionAt(region.headerStart)
      const end = model.getPositionAt(region.headerEnd)
      decorations.push({
        range: new monaco.Range(start.lineNumber, start.column, end.lineNumber, end.column),
        options: {
          inlineClassName: "afield-stacksl-token-directive",
          hoverMessage: { value: "Embedded RectSL" },
        },
      })
    }
    const state = editor._rectslDecorations || (editor._rectslDecorations = editor.createDecorationsCollection([]))
    state.set(decorations)
  },

  updateDiagnostics(editor, { model, monaco }) {
    const markers = []
    for (const region of findRectSLEmbeddedRegions(model)) {
      // Call an out-of-tree RectSL parser/compiler here and convert diagnostics
      // to Monaco marker ranges using model.getPositionAt(...).
    }
    monaco.editor.setModelMarkers(model, "rectsl-diagnostics", markers)
  },
})
```

## Flow and context lenses

`updateFlowLens(...)` is for visual relationships in the text: stack consumers,
track producers, duplicated rectangles, mirrored keyframes, or timing
transformations. Use Monaco decorations, preferably with an editor-local
decoration collection:

```diffstatic {data-lang=javascript}
const state = editor._rectslFlow || (editor._rectslFlow = editor.createDecorationsCollection([]))
state.set(decorations)
```

`buildContextPayload(...)` feeds the small editor context badge:

```diffstatic {data-lang=javascript}
buildContextPayload(editor, resolveContext) {
  const model = editor.getModel()
  const position = editor.getPosition()
  const offset = model.getOffsetAt(position)
  const region = findRectSLEmbeddedRegions(model).find((r) => r.bodyStart <= offset && offset <= r.bodyEnd)
  if (!region) return null

  const resolved = resolveContext ? resolveContext(editor) : null
  const key = `${resolved?.uid || "rectsl"}::${model.getVersionId()}::${offset}`
  return {
    key,
    editor,
    isLiveValue: false,
    html: `<div class="afield-stacksl-context"><strong>RectSL</strong> track/keyframe context goes here</div>`,
  }
}
```

For RectSL, useful context payloads might include the current word's stack
effect, the selected rectangle/group, sampled track value at the current time, or
the source and target keyframes of a timing transform.

## Completion items

`provideCompletionItems(...)` lets an out-of-tree language answer before Field's
server-side completion path. This is useful for embedded DSLs, because the
language often knows its local vocabulary without needing Kotlin compiler
context. FieldSuggest renders these suggestions; they are not registered with
Monaco's built-in suggest widget:

```diffstatic {data-lang=javascript}
provideCompletionItems(editor, model, position, { monaco }) {
  const region = findRectSLEmbeddedRegions(model).find((r) => {
    const offset = model.getOffsetAt(position)
    return r.bodyStart <= offset && offset <= r.bodyEnd
  })
  if (!region) return { suggestions: [] }

  return {
    suggestions: [{
      label: "->square",
      insertText: "->square",
      kind: monaco.languages.CompletionItemKind.Function,
      detail: "size --",
      documentation: {
        value: "**->square** `size --`\n\nSet width and height to the same value.",
        isTrusted: true,
      },
      data: { rectslEffect: "mutator" },
    }]
  }
}
```

RectSL's incubating extension keeps a word-documentation table beside the
highlighter and flow lens, so FieldSuggest completion, token colors, and the
context badge all describe the same language. Field's user-facing completion
goes through FieldSuggest, not Monaco's built-in suggest widget.

## Line notes: what a line did { #line-notes }

Rest the cursor on a line of a box's program, or open completion there, and Field shows a note about
that line beside the cursor and at the top of the completion popup: a sentence, and a small picture
when the language has one to draw. Which languages give notes, and what each note says:

| language | the note for a line | picture |
| --- | --- | --- |
| StackSL | the stage the line ends, drawn, or the value it stores in a mark | the stage |
| RectSL | the scene built up to the line's statement, the rectangles it makes outlined | the scene |
| ForthField | the data stack after the line on the last run, or the words it defined | the stack, top uppermost |
| Clojure | what each top-level form evaluated to on the last run, and its type | — |
| Python | what each top-level statement came to on the last run: an expression's value, the name it assigned, what it defined, or just that it ran | — |
| FieldML | the value each top-level `let` bound on the last run, and its type; the final expression's value | — |
| Datalog | a rule: the facts its body derives in the box's model; a query: its answers; a fact: whether it is in the model and which rules read it | the derived rows, as a table |

**A note never runs your code.** StackSL and RectSL can say what part of a program does without
running it, because a partial StackSL program is still a pure shader and a partial RectSL scene a
pure description. Forth, Clojure, Python and FieldML programs print, draw, define things and change
state that outlives the run, so their notes are *recorded*: as the box runs, each top-level step reports what it
came to, and the note reads that record back. It says so — "last run, 12 s ago" — follows its line
when you edit above it, and when the line itself has changed since, says "changed since the last run"
before telling you what the old text did. A run that failed marks the line it stopped on, and Forth
marks the lines after it as not reached. Datalog needs no record: a Datalog box accumulates one
model, and asking it is pure; a rule you have edited but not run is reported as not in the model
rather than answered for. Directives such as `#reset` are never run for a note.

Two details of the recording are worth knowing. A Clojure value is printed under a bounded
`*print-length*`, and a lazy sequence nothing has realised is named, not printed — printing it would
realise it, running code the program never ran. A Python note prints a value only when it is a
builtin number, a string, or a small list, tuple, set or dict of those; anything else — a DataFrame,
a tensor, an object of your own class — is named by its type, because printing it would run its
`__repr__`. Only a plain name is read back after an assignment, so recording evaluates nothing the
statement did not, and a cell's docstring and `from __future__` imports are left exactly where Python
needs them. Setting `AFIELD_PYTHON_LINE_NOTES=0` in Field's environment turns Python's recording off.

Kotlin box code has no notes of its own yet. A Kotlin box is compiled as one snippet and only its
final value comes back, so a per-line value would need the box's source rewritten before
compilation. Inside a Kotlin box, a `#stacksl` or `#rectsl` string still gets its language's notes.
Morpho and Nano boxes have none.

A box's AI chat can read the same notes: the `ReadLineNotes` tool is offered in a conversation on any
program that has them, and returns each line's caption with its picture as an image.

## When to promote

Keep a language out of tree while its syntax and semantics are moving quickly.
Promote it into Field when one of these becomes true:

- It needs a durable property language in `monacoPropertyLanguages`.
- Its compiler/runtime should ship with Field.
- Its diagnostics need server-side data.
- Other Field systems need to call it directly.

Until then, the extension registry lets the language feel native in the editor
without becoming part of the editor bindings.

## What a promoted language looks like: FieldML

FieldML is a main language rather than an embedded region, so it does not use
the registry described above. It takes the built-in route instead, which
replaces all three of the registry hooks:

- **Highlighting** comes from a registered Monaco language rather than an
  embedded-region matcher. `src/commonMain/resources/fieldml.js` calls
  `monaco.languages.register` and installs a Monarch grammar with its own
  keywords, type names and operators, plus a language configuration for
  brackets, auto-closing pairs and the `//` and `(* *)` comment forms.
- **Completion and hover come from the server**, not from a JS
  `completionItems` hook. A FieldML box's execution routes `complete` and
  `describeSymbolContext` into `FieldMLCompletionEngine`, which answers from the
  same type prelude the compiler uses. Items carry their real inferred types,
  and when the caret follows a `|>` the engine ranks the functions whose last
  argument accepts the piped type.
- **Diagnostics are real type errors**, not lexical guesses. Running a box
  compiles it, so the messages come from the inferencer with source ranges.

The trade is the usual one. An extension-registry language ships as a `.js`
file and can change shape in an afternoon; a promoted language has a compiler,
a type checker and a prelude to keep in step, and gains completion that
actually knows what a name means. See the
[FieldML Cheatsheet](fieldml_cheatsheet.md) for the surface that buys.
