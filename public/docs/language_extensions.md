# Field Language Extensions

Field's Monaco editor has a small browser-side extension registry for languages
that are useful before they are fully integrated into Field. It is intended for
experiments like RectSL: a language can supply syntax highlighting for embedded
regions, diagnostics, and a flow/context lens without editing the Kotlin/JS
Monaco bindings for each new language.

StackSL uses this API internally while keeping its older global hooks available.
That makes StackSL both a real consumer and the reference implementation.

## Loading

The registry is available in the editor page as:

```js
window.FieldLanguageExtensions
```

It is also exposed through convenience functions:

```js
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

## Extension Contract

An extension is a plain JavaScript object:

```js
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
    // Return Monaco completion items when the cursor is inside this language.
    return { suggestions: [] }
  }
})
```

`context` contains:

```js
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

## Embedded Regions

Embedded language support starts with a region detector. A region should include
absolute offsets into the Monaco model so later hooks can map tokens back to
editor positions:

```js
{
  source: "0 0 xy 1 square",
  bodyStart: 125,
  bodyEnd: 142,
  headerStart: 108,
  headerEnd: 115
}
```

The registry annotates returned regions with:

```js
{
  languageExtensionId: "rectsl",
  languageLabel: "RectSL"
}
```

## RectSL Sketch

This example detects Kotlin triple-quoted strings whose first non-empty line is
`#rectsl`.

```js
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

```js
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

## Flow And Context Lenses

`updateFlowLens(...)` is for visual relationships in the text: stack consumers,
track producers, duplicated rectangles, mirrored keyframes, or timing
transformations. Use Monaco decorations, preferably with an editor-local
decoration collection:

```js
const state = editor._rectslFlow || (editor._rectslFlow = editor.createDecorationsCollection([]))
state.set(decorations)
```

`buildContextPayload(...)` feeds the small editor context badge:

```js
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

## Completion Items

`provideCompletionItems(...)` lets an out-of-tree language answer before Field's
server-side completion path. This is useful for embedded DSLs, because the
language often knows its local vocabulary without needing Kotlin compiler
context:

```js
provideCompletionItems(editor, model, position, { monaco }) {
  const region = findRectSLEmbeddedRegions(model).find((r) => {
    const offset = model.getOffsetAt(position)
    return r.bodyStart <= offset && offset <= r.bodyEnd
  })
  if (!region) return { suggestions: [] }

  return {
    suggestions: [{
      label: "square",
      insertText: "square",
      kind: monaco.languages.CompletionItemKind.Function,
      detail: "size --",
      documentation: {
        value: "**square** `size --`\n\nSet width and height to the same value.",
        isTrusted: true,
      },
    }]
  }
}
```

RectSL's incubating extension keeps a word-documentation table beside the
highlighter and flow lens, so Monaco completion, token colors, and the context
badge all describe the same language.

## When To Promote

Keep a language out of tree while its syntax and semantics are moving quickly.
Promote it into Field when one of these becomes true:

- It needs a durable property language in `monacoPropertyLanguages`.
- Its compiler/runtime should ship with Field.
- Its diagnostics need server-side data.
- Other Field systems need to call it directly.

Until then, the extension registry lets the language feel native in the editor
without becoming part of the editor bindings.
