# The anatomy of a small language in Field4

Field4 hosts an unusual number of small languages, and until this page there was no map. This is not
a survey — it is the thing to follow when you want to add one, or when you have found one and need to
know what else it touches.

Every `file:line` below was read in the worktree that shipped this page, and
`SmallLanguageAnatomyTest` (`src/jvmTest/kotlin/afield/docs/SmallLanguageAnatomyTest.kt`) re-checks
each one on every `./gradlew check`: it scrapes the ledger table out of *this file* and fails if a
cited line no longer contains its anchor token. Every count claimed in prose is asserted there too,
against a set derived from the code rather than a number typed in. So if this page is wrong, the
build is red. See "What the guard checks" at the end for exactly how far that goes.

## The spine: two routes, and they are not variants of each other

`public/docs/language_extensions.md` draws the distinction that organises everything else, and
§"The Other Side Of Promotion: FieldML" (`public/docs/language_extensions.md:280-304`) is the part to
read first. The two routes are:

**Route A — an embedded-region extension.** The language lives in one browser-side `.js` file, gets
registered with `window.FieldLanguageExtensions`, and appears *inside* another language's source: a
RectSL program is a Kotlin triple-quoted string whose first line is `#rectsl`. It ships as a
resource, can change shape in an afternoon, and never becomes part of the editor bindings. Its
diagnostics are lexical because that is all a `.js` file in the browser can see.

**Route B — a promoted main language.** The language is what a *box* is written in. It has a
`BoxExecution` on the JVM, a registered Monaco language, and its completion comes from the server —
which means completion can know inferred types, because the same compiler answers it.

The two are not points on a spectrum; they use disjoint machinery. Route A never touches
`BoxExecution`, and Route B never touches the extension registry. `language_extensions.md:267-278`
("When To Promote") is the decision.

### Who is on which route

There are **8** registered main languages (Route B). The list is derived, not curated — it is every
call to `registerMainLanguageRuntime`:

| id | `BoxExecution` | site | anchor |
| --- | --- | --- | --- |
| `morpho` | `MorphoExecution` | `src/jvmMain/kotlin/afield/morpho/MorphoInterface.kt:61` | `class MorphoExecution` |
| `nano` | `NanoExecution` | `src/jvmMain/kotlin/afield/nano/NanoInterface.kt:48` | `class NanoExecution` |
| `clojure` | `ClojureExecution` | `src/jvmMain/kotlin/afield/clojure/ClojureInterface.kt:410` | `class ClojureExecution` |
| `python` | `PythonExecution` | `src/jvmMain/kotlin/afield/python/PythonInterface.kt:195` | `class PythonExecution` |
| `fieldml` | `FieldMLExecution` | `src/jvmMain/kotlin/afield/ml/FieldMLInterface.kt:45` | `class FieldMLExecution` |
| `kotlinjs` | `KotlinJsExecution` | `src/jvmMain/kotlin/afield/remotejs/KotlinJs.kt:73` | `class KotlinJsExecution` |
| `forth` | `ForthExecution` | `src/jvmMain/kotlin/afield/forth/ForthInterface.kt:23` | `class ForthExecution` |
| `remotejs` | `RemoteJSExectution` | `src/jvmMain/kotlin/afield/remotejs/RemoteJS.kt:446` | `class RemoteJSExectution` |

and the `registerMainLanguageRuntime` call each one makes:

| id | site | anchor |
| --- | --- | --- |
| `morpho` | `src/jvmMain/kotlin/afield/morpho/MorphoInterface.kt:402` | `id = "morpho"` |
| `nano` | `src/jvmMain/kotlin/afield/nano/NanoInterface.kt:505` | `id = "nano"` |
| `clojure` | `src/jvmMain/kotlin/afield/clojure/ClojureInterface.kt:554` | `id = "clojure"` |
| `python` | `src/jvmMain/kotlin/afield/python/PythonInterface.kt:160` | `id = "python"` |
| `fieldml` | `src/jvmMain/kotlin/afield/ml/FieldMLInterface.kt:244` | `id = "fieldml"` |
| `kotlinjs` | `src/jvmMain/kotlin/afield/remotejs/KotlinJs.kt:64` | `id = "kotlinjs"` |
| `forth` | `src/jvmMain/kotlin/afield/forth/ForthInterface.kt:264` | `id = "forth"` |
| `remotejs` | `src/jvmMain/kotlin/afield/remotejs/RemoteJS.kt:145` | `id = "remotejs"` |

Kotlin is the ninth and is the *absence* of an entry: `mainLanguage = null` means Kotlin
(`MainLanguageRegistry.kt:51-56`), so there is no `KotlinExecution` runtime registered here.

**RectSL, StackSL and Brief are not on this list**, and that is the single most useful fact on this
page. None is a main language. None has a `BoxExecution`. They are Route A extensions with a Kotlin
compiler behind them, which is a third shape the two-route story does not quite predict — see
"The third shape" below.

## The integration points

This is the ledger. The guard test parses this exact table, so the `anchor` column is load-bearing:
it is a literal token that must still appear at (or within two lines of) the cited line.

| # | integration point | site | anchor |
| --- | --- | --- | --- |
| 1 | the `BoxExecution` contract every main language implements | `src/jvmMain/kotlin/afield/execution.kt:57` | `interface BoxExecution` |
| 2 | the per-box property that chooses the language | `src/jvmMain/kotlin/afield/runtime/MainLanguageRegistry.kt:22` | `var Box.mainLanguage` |
| 3 | the main-language registry itself | `src/jvmMain/kotlin/afield/runtime/MainLanguageRegistry.kt:26` | `fun registerMainLanguageRuntime` |
| 4 | the descriptor a language registers | `src/jvmMain/kotlin/afield/runtime/MainLanguageRegistry.kt:14` | `data class MainLanguageRuntime` |
| 5 | the language-root → children toggle that actually swaps `ke` | `src/jvmMain/kotlin/afield/runtime/LanguageRuntimeSupport.kt:63` | `child.ke = newExecution(child)` |
| 6 | the string the browser turns into a Monaco language id | `src/jvmMain/kotlin/afield/runtime/LanguageRuntimeSupport.kt:65` | `child.code_language = language` |
| 7 | the topology watcher that applies the toggle after a graph edit | `src/jvmMain/kotlin/afield/runtime/LanguageRuntimeSupport.kt:18` | `class TopologyWatcher` |
| 8 | the synchronous dispatch barrier automation uses instead | `src/jvmMain/kotlin/afield/runtime/MainLanguageRegistry.kt:37` | `fun refreshMainLanguageRuntimes` |
| 9 | the browser-side extension registry (Route A) | `src/commonMain/resources/languageExtensions.js:112` | `const registry = {` |
| 10 | the registry's global entry point | `src/commonMain/resources/languageExtensions.js:124` | `root.FieldLanguageExtensions = registry` |
| 11 | the list of editor language modules loaded at boot | `src/commonMain/resources/bootstrapMonaco.js:45` | `FIELD_LANGUAGE_MODULES` |
| 12 | the one-at-a-time module loader, and why it is not one `require` | `src/commonMain/resources/bootstrapMonaco.js:58` | `loadFieldLanguageModules` |
| 13 | the set of languages Field's own suggest widget serves | `src/commonMain/resources/fieldSuggest.js:7` | `DEFAULT_LANGUAGES` |
| 14 | the gate that consults it | `src/commonMain/resources/fieldSuggest.js:1143` | `this.languages.has` |
| 15 | client-side ranking (fuzzy + category + recency + contextScore) | `src/commonMain/resources/fieldSuggest.js:734` | `const rankItems` |
| 16 | the `complete` RPC the browser sends | `src/commonMain/resources/fieldSuggest.js:1289` | `requestWithReply("complete"` |
| 17 | the server handler for it | `src/jvmMain/kotlin/afield/Server.kt:2189` | `handlers["complete"]` |
| 18 | the polymorphic hop into the language's own completion | `src/jvmMain/kotlin/afield/Server.kt:2195` | `box.ke.complete` |
| 19 | the sibling hover/doc route | `src/jvmMain/kotlin/afield/Server.kt:2216` | `handlers["symbol-context"]` |
| 20 | how any language's result becomes box output | `src/jvmMain/kotlin/afield/execution.kt:1134` | `fun dispatchExecutionOutput` |
| 21 | how `print`/`error` inside a program reach the box | `src/jvmMain/kotlin/afield/execution.kt:1179` | `fun configureOutputProxies` |
| 22 | the Ktor route that serves your `.js` to the browser | `src/jvmMain/kotlin/afield/Server.kt:4647` | `src/commonMain/resources/` |
| 23 | the `Cache-Control` it sets, and the headless exception | `src/jvmMain/kotlin/afield/Server.kt:4627` | `val assetCache` |
| 24 | the per-box-property Monaco language table | `src/jsMain/kotlin/afield/BootstrapMonacoInit.kt:73` | `monacoPropertyLanguages` |

### Reading the ledger

Points 1-8 are the JVM side of Route B, 9-12 are Route A, 13-19 are the two completion routes, 20-21
are output — which both routes share, because output is about the *box*, not the language — and 22-24
are how your `.js` reaches the browser at all.

Two things worth pulling out of it.

**`code_language` is the whole browser handshake** (point 6). The JVM sets one string on the box; the
browser turns it into a Monaco language id (`BootstrapMonacoInit.kt:235-248` for the main editor,
`Main.kt:1800-1809` for the per-box editors, `.lowercase()` applied at `Main.kt:1809`). There is no
capability negotiation. If your Monaco language id and your `code_language` string do not match after
lower-casing, you get a working box with no highlighting and no error anywhere.

**Loading a language module is deliberately not one `require`** (point 12). The comment at
`bootstrapMonaco.js:52-57` is the scar: they used to be a single grouped `require` with `initMonaco()`
as the callback, so a module whose AMD factory throws — a Monarch grammar that does not compile, say —
never settles, the loader runs neither callback nor errorback, and the page comes up with a live
websocket, no editor and nothing in the console. They are loaded one at a time against a
`LANGUAGE_MODULE_DEADLINE_MS` (`bootstrapMonaco.js:50`) and the editor starts once they settle or the
deadline passes, naming what did not arrive. If you are adding a language module, this is the failure
mode that will cost you an afternoon: every UI symptom says "editor not synced yet".

## Route B, traced end to end: FieldML

FieldML is the fully-worked example, so follow it rather than inventing a shape. It is five stages, a
prelude in two halves, one `BoxExecution`, one Monaco module, and two coverage guards.

### The compiler

| stage | site | anchor |
| --- | --- | --- |
| the one function that runs all five stages | `src/jvmMain/kotlin/afield/ml/FieldMLCompiler.kt:26` | `fun compile(source: String)` |
| every intermediate form is kept, one field per stage | `src/jvmMain/kotlin/afield/ml/FieldMLCompiler.kt:14` | `data class CompilationResult` |
| 1. lex | `src/jvmMain/kotlin/afield/ml/lex/Lexer.kt:20` | `fun lex()` |
| 2. parse | `src/jvmMain/kotlin/afield/ml/parse/Parser.kt:54` | `fun parse()` |
| 3. infer | `src/jvmMain/kotlin/afield/ml/type/Inferencer.kt:52` | `fun infer(expr: Expr)` |
| 4. lower to core | `src/jvmMain/kotlin/afield/ml/core/Lowerer.kt:34` | `fun lower(expr: TExpr)` |
| 5. evaluate | `src/jvmMain/kotlin/afield/ml/core/Evaluator.kt:42` | `fun evaluate(expr: CoreExpr)` |

`CompilationResult` keeping *every* intermediate form is not tidiness. It is what lets one compile
serve three consumers: the evaluator wants `core`, the error reporter wants `diagnostics` with source
ranges, and the completion engine wants `typed`. A language that throws its AST away after lowering
cannot have type-aware completion no matter how good its inferencer is.

### The prelude, in two halves

This is the part that is easy to get wrong. FieldML's standard library exists twice, and the two must
agree name for name:

| half | site | anchor |
| --- | --- | --- |
| types, consumed by the inferencer | `src/jvmMain/kotlin/afield/ml/type/FieldMLTypePrelude.kt:67` | `val schemes: Map<String, Scheme>` |
| values, consumed by the evaluator | `src/jvmMain/kotlin/afield/ml/FieldMLRuntimePrelude.kt:33` | `fun values(` |
| where the type half enters | `src/jvmMain/kotlin/afield/ml/type/Inferencer.kt:54` | `FieldMLTypePrelude.schemes` |
| where the value half enters | `src/jvmMain/kotlin/afield/ml/core/Evaluator.kt:43` | `RuntimeEnv()` |

The runtime half takes `output: HasOutput?` and `box: Box?` (`FieldMLRuntimePrelude.kt:33`) — that is
how host effects get in. `display` (`FieldMLRuntimePrelude.kt:45-48`) and `draw`
(`FieldMLRuntimePrelude.kt:301-305`) are prelude natives closed over the box, not language features.
If you want a language to be able to draw, this is the seam: not the evaluator, the prelude.

Multi-argument natives are nested single-argument closures (`native1`/`native2`/`native3`,
`FieldMLRuntimePrelude.kt:423-433`), which is what makes `|>` and partial application work uniformly.

### The box runtime

| what | site | anchor |
| --- | --- | --- |
| the execution | `src/jvmMain/kotlin/afield/ml/FieldMLInterface.kt:45` | `class FieldMLExecution` |
| the name the editor sees | `src/jvmMain/kotlin/afield/ml/FieldMLInterface.kt:159` | `languageName` |
| the `@RunsAt(early)` installer | `src/jvmMain/kotlin/afield/ml/FieldMLInterface.kt:240` | `fun installFieldML` |
| the topology watcher it starts | `src/jvmMain/kotlin/afield/ml/FieldMLInterface.kt:254` | `installFieldMLPlugin` |
| prelude + evaluate, on the launch path | `src/jvmMain/kotlin/afield/ml/FieldMLInterface.kt:70` | `FieldMLRuntimePrelude.values` |
| the `value : Type` inline result | `src/jvmMain/kotlin/afield/ml/FieldMLInterface.kt:297` | `fun renderInlineResult` |
| only print it if the program printed nothing | `src/jvmMain/kotlin/afield/ml/FieldMLInterface.kt:174` | `output.printed` |
| diagnostics → box error output | `src/jvmMain/kotlin/afield/ml/FieldMLInterface.kt:203` | `reportDiagnostics` |

`TrackingOutput` (`FieldMLInterface.kt:270`) exists only to answer "did the program print anything",
so the automatic `value : Type` line does not duplicate a program's own output. That is a small idea
worth copying: an expression language wants to show its result, and a language with effects does not.

### Completion, which is the actual payoff

| what | site | anchor |
| --- | --- | --- |
| the engine | `src/jvmMain/kotlin/afield/ml/FieldMLCompletionEngine.kt:41` | `fun complete(request: CompletionRequest)` |
| the hover/doc-panel sibling | `src/jvmMain/kotlin/afield/ml/FieldMLCompletionEngine.kt:85` | `fun describeSymbolContext` |
| the `BoxExecution` override that routes into it | `src/jvmMain/kotlin/afield/ml/FieldMLInterface.kt:153` | `FieldMLCompletionEngine.complete` |

The engine answers from the *same type prelude the compiler uses*, so items carry their real inferred
types, and after a `|>` it ranks the functions whose last argument accepts the piped type. No
browser-side extension can do that, and that is the entire argument for Route B.

### The Monaco module

One file, `src/commonMain/resources/fieldml.js`, 91 lines, and it is all of FieldML's editor wiring:

| what | site | anchor |
| --- | --- | --- |
| the AMD module | `src/commonMain/resources/fieldml.js:1` | `bithero/fieldml` |
| language id registration | `src/commonMain/resources/fieldml.js:7` | `monaco.languages.register` |
| brackets, comments, auto-closing pairs | `src/commonMain/resources/fieldml.js:13` | `setLanguageConfiguration` |
| the Monarch tokenizer | `src/commonMain/resources/fieldml.js:45` | `setMonarchTokensProvider` |
| loaded at boot | `src/commonMain/resources/bootstrapMonaco.js:46` | `bithero/fieldml` |
| served by Field's own suggest widget | `src/commonMain/resources/fieldSuggest.js:17` | `fieldml` |
| one of only two languages with hover | `src/commonMain/resources/bootstrapMonaco.js:441` | `"kotlin", "fieldml"` |

Note what is *absent*: no `monaco.languages.registerCompletionItemProvider`. Field routes user-facing
completion through its own suggest widget, not Monaco's, and being in `DEFAULT_LANGUAGES`
(`fieldSuggest.js:7-20`) is the whole of the opt-in. There is exactly **one** remaining
`registerCompletionItemProvider` in the repo — `src/commonMain/resources/msl.js:280` — and
`MslLanguageSmokeTest.kt:39` pins it, so if you copy a completion provider from an existing language,
make sure it is not that one.

## Route A, traced: the extension registry

The whole registry is 136 lines (`src/commonMain/resources/languageExtensions.js`) and an extension is
a plain object with **8** optional hooks. They are derived from the `callExtension(extension, "…")`
calls rather than from the docs page, because the page is prose:

| hook | called from | site | anchor |
| --- | --- | --- | --- |
| `install` | once per editor, guarded by an install key | `src/commonMain/resources/languageExtensions.js:51` | `"install"` |
| `update` | supersedes the three `update*` hooks if present | `src/commonMain/resources/languageExtensions.js:62` | `"update"` |
| `updateEmbeddedHighlight` | on every editor/property/model change | `src/commonMain/resources/languageExtensions.js:65` | `"updateEmbeddedHighlight"` |
| `updateFlowLens` | value-flow / producer / consumer decorations | `src/commonMain/resources/languageExtensions.js:66` | `"updateFlowLens"` |
| `updateDiagnostics` | Monaco markers | `src/commonMain/resources/languageExtensions.js:67` | `"updateDiagnostics"` |
| `buildContextPayload` | the lower-left context badge; first non-null wins | `src/commonMain/resources/languageExtensions.js:73` | `"buildContextPayload"` |
| `provideCompletionItems` | FieldSuggest-shaped items, concatenated across extensions | `src/commonMain/resources/languageExtensions.js:84` | `"provideCompletionItems"` |
| `findEmbeddedRegions` | source ranges of this language inside the model | `src/commonMain/resources/languageExtensions.js:99` | `"findEmbeddedRegions"` |

Three properties of the registry that matter when you are writing one:

- **Every hook call is wrapped.** `callExtension` (`languageExtensions.js:8-17`) catches and
  `console.warn`s, so one experimental language cannot take the editor down. The corollary is that a
  broken hook fails *silently* to anyone not watching the console.
- **Registration is late-safe.** `register` (`:19-40`) installs and updates against
  `root.globalEditor` on a `setTimeout(…, 0)` if the editor already exists, and a
  `__fieldLanguageExtensionQueue` (`:131-133`) drains registrations that arrived before the module did.
  So an out-of-tree extension loaded from anywhere works.
- **Completion is additive, not exclusive.** `completionItems` (`:79-93`) concatenates every
  extension's suggestions. There is no "this region belongs to me" arbitration; an extension is
  responsible for returning nothing when the caret is not inside its own region.

### The two completion routes, and which to pick

| | Route A hook | Route B server |
| --- | --- | --- |
| where it runs | browser | JVM |
| entry point | `provideCompletionItems` (`languageExtensions.js:84`) | `BoxExecution.complete` (`execution.kt:89`) |
| what it can know | the text, lexically | whatever the compiler knows, including inferred types |
| ranking | client-side either way — `rankItems` (`fieldSuggest.js:734`) | client-side either way |
| latency | none | one websocket round trip |
| opt-in | `FieldLanguageExtensions.register` | membership of `DEFAULT_LANGUAGES` (`fieldSuggest.js:7`) |

Both routes deliver into the *same* widget and the *same* client-side ranking. The server does not
rank; it returns everything and `rankItems` scores by fuzzy match, category weight, recency and the
server-supplied `contextScore` (`fieldSuggest.js:734-783`). So a server-side engine's leverage is in
what it *knows*, not in what order it answers.

Field's suggest widget serves **14** languages: `bend`, `brief`, `clojure`, `fieldml`, `forth`, `glsl`,
`kotlin`, `kotlinjs`, `nano`, `python`, `rectsl`, `slang`, `stacksl`, `yaml`
(`fieldSuggest.js:7-21`). Note `rectsl`, `stacksl` and `brief` are in that set despite being Route A —
being in `DEFAULT_LANGUAGES` is about *which widget* handles the caret, not about where the items come
from.

And note the two exceptions, because "everything goes through FieldSuggest" is *almost* true: `msl` and
`morpho` have editor modules but are **not** in `DEFAULT_LANGUAGES`, so the gate at
`fieldSuggest.js:1143` returns false for them. MSL supplies its own Monaco provider (`msl.js:280`);
morpho has no completion at all. `dev_docs/COMPLETION_ENGINE.md:3-9` states the policy — that path is
retired, and a second completion path through Monaco's provider API is not to be added. Read it before
you copy MSL.

Conversely, `python`, `clojure`, `kotlin`, `yaml` and `bend` are in `DEFAULT_LANGUAGES` and have no
`bithero/*` module at all: Clojure's highlighting comes from stock Monaco's basic-languages bundle
(`bootstrapMonaco.js:83-84`), which is the cheapest possible answer and worth remembering before you
write a Monarch grammar for a language Monaco already knows.

### The editor's language modules

**15** editor language modules load at boot (`bootstrapMonaco.js:45-50`): `bithero/glsl`,
`bithero/slang`, `bithero/msl`, `bithero/forth`, `bithero/fieldml`, `bithero/languageExtensions`,
`bithero/stacksl`, `bithero/rectsl`, `bithero/fnrewrite`, `bithero/sonicpi`, `bithero/nano`,
`bithero/kotlinjs`, `bithero/morpho`, `bithero/petri`, `bithero/brief`.

That list is the honest census of the editor's language surface, and it does not line up with the
main-language list: `msl`, `stacksl`, `rectsl`, `fnrewrite` and `brief` have editor modules and no
`BoxExecution`; `clojure`, `python` and `remotejs` have a `BoxExecution` and no module of their own.
Neither asymmetry is a bug — it is what "small language" covers here.

## The third shape: a compiler behind a browser extension

RectSL and StackSL break the two-route story, and knowing how saves you from looking for a
`BoxExecution` that is not there. Both have a *full Kotlin implementation* — RectSL is a tokenizer, a
word-table VM and three backends — and both reach the editor through Route A, because a RectSL program
is not what a box is written in. It is a Kotlin triple-quoted string starting `#rectsl` inside a Kotlin
box.

| what | site | anchor |
| --- | --- | --- |
| RectSL compile entry point | `src/jvmMain/kotlin/afield/rectsl/RectSL.kt:1464` | `fun compileRectSL` |
| the one word-registration path | `src/jvmMain/kotlin/afield/rectsl/RectSL.kt:3396` | `internal fun word` |
| backend 1, the CPU `eval:` column | `src/jvmMain/kotlin/afield/rectsl/RectSL.kt:369` | `val eval: (Double) -> Double` |
| backend 2, the GLSL emitter | `src/jvmMain/kotlin/afield/rectsl/RectSLGlsl.kt:265` | `fun emitSdf` |
| backend 3, the differentiable `fn:` lowering | `src/jvmMain/kotlin/afield/rectsl/RectSLFn.kt:493` | `fun Track<Double>.toFn` |
| the editor extension object | `src/commonMain/resources/rectsl.js:836` | `const extension = {` |
| its standalone `.rectsl` Monaco language | `src/commonMain/resources/rectsl.js:864` | `registerRectSLLanguage` |
| the Kotlin side that reads the JS verbatim | `src/jvmMain/kotlin/afield/rectsl/RectSLEditorExtension.kt:15` | `fun javascript()` |
| the node probe that runs that JS for real | `src/jvmMain/kotlin/afield/examples/rectsl/RectSLEditorExtensionWorkout.kt:45` | `runNodeProbe` |
| RectSL's gallery harness | `src/jvmMain/kotlin/afield/four/fabricated/RectSLGalleryHarness.kt:67` | `object RectSLGalleryHarness` |
| StackSL's compiler | `src/jvmMain/kotlin/afield/stacksl/StackSL.kt:105` | `object StackSLCompiler` |
| StackSL's word table | `src/jvmMain/kotlin/afield/stacksl/StackSL.kt:2645` | `builtinWords` |
| StackSL's extension object | `src/commonMain/resources/stacksl.js:2111` | `stackSLLanguageExtension` |
| StackSL's Monaco language | `src/commonMain/resources/stacksl.js:7` | `monaco.languages.register` |
| StackSL's Monarch grammar | `src/commonMain/resources/stacksl.js:2134` | `setMonarchTokensProvider` |
| StackSL's document formatter | `src/commonMain/resources/stacksl.js:2194` | `registerDocumentFormattingEditProvider` |
| StackSL as a first-class box property | `src/jvmMain/kotlin/afield/Boxes.kt:464` | `"stackSL" to "stacksl"` |
| StackSL's gallery harness | `src/jvmMain/kotlin/afield/four/fabricated/StackSLGalleryHarness.kt:53` | `object StackSLGalleryHarness` |
| Brief's compile entry point | `src/jvmMain/kotlin/afield/brief/BriefLowering.kt:89` | `fun compileBrief` |
| Brief's one vocabulary table | `src/jvmMain/kotlin/afield/brief/Brief.kt:350` | `object BriefVerbs` |
| Brief's weight ladder | `src/jvmMain/kotlin/afield/brief/Brief.kt:134` | `enum class BriefStrength` |
| Brief's lowering into the Kotlin DSL | `src/jvmMain/kotlin/afield/brief/BriefLowering.kt:624` | `fun BriefProgram.toGeometryModel` |
| Brief's extension object | `src/commonMain/resources/brief.js:394` | `const extension = {` |
| its standalone `.brief` Monaco language | `src/commonMain/resources/brief.js:429` | `registerBriefLanguage` |
| the Kotlin side that reads the JS verbatim | `src/jvmMain/kotlin/afield/brief/BriefEditorExtension.kt:18` | `fun javascript()` |
| the node probe that runs that JS for real | `src/jvmMain/kotlin/afield/examples/brief/BriefEditorExtensionWorkout.kt:50` | `runNodeProbe` |

**Brief is the third member of this shape and the newest**, so it is the one to read if you are adding
one: a weighted constraint notation whose programs lower to `afield.stdlib.ceres.ConstraintScope`. It
was built after this page existed and followed it, which makes it a fair test of the page — the two
things it needed that the page did not predict are recorded in `dev_docs/brief_notation.md`
("What the anatomy page did not warn about").

Four things are worth copying from this shape.

**One registration path for words, even for extensions.** `RectSL.word` is `internal`, not `private`,
specifically so `RectSLPattern.kt` registers the musical vocabulary through the same call rather than a
parallel mechanism (`RectSL.kt:3394-3395` says so). A language that grows a second way to define a word
grows a second way to forget one.

**Three backends in one enum, one column each.** `TrackOp1`/`TrackOp2`/`TrackOp3`/`TimeWarpKind` each
carry `eval:`, `glsl:` and `fn:` side by side (`RectSL.kt:368-372` is `TrackOp1`'s constructor). Adding an
operator without a GLSL emission or a differentiability decision is then a compile error rather than a
runtime surprise — the refusals are *in the type* (`RectSLFn.kt:130`, `sealed interface
RectSLFnLowering`).

**The `.js` file is the single source of truth, and Kotlin reads it.**
`RectSLEditorExtension.javascript()` loads `/rectsl.js` off the classpath and falls back to the file on
disk, so the node-probe smoke test exercises the exact bytes that ship. `rectsl.js` self-installs under
bare `eval` and registers an AMD module when a loader is present (`rectsl.js:876-887`), which is what
makes it testable headlessly. Do this: a browser-side language you cannot test outside a browser will
not stay correct.

**A standalone Monaco language with no Monarch grammar.** `registerRectSLLanguage`
(`rectsl.js:864-880`) registers `rectsl` with a language *configuration* only — comments, brackets,
auto-closing — and deliberately no tokenizer, because the decoration highlighter already treats a whole
`rectsl` model as one scope. One tokenizer, two contexts. StackSL made the other choice and has a
Monarch grammar (`stacksl.js:2134`); both work, but see the warning below.

> **The Monarch trap.** A bad rule in any `bithero/*.js` Monarch grammar throws inside its AMD factory,
> which never settles, which wedges `initMonaco`: no editor, no console error, and every UI symptom
> reports "editor not synced yet". `bootstrapMonaco.js:52-57` is the comment left behind by that. If
> you add a Monarch grammar, this is the failure you are signing up for.

## The fourth shape: the host's own syntax, erased

`rewrite { }` is a small language whose surface syntax *is* Kotlin, and whose implementation deletes
itself before the compiler runs.

| what | site | anchor |
| --- | --- | --- |
| the authoring surface, all of it erased | `src/jvmMain/kotlin/afield/macros/RewriteDsl.kt:76` | `fun rewrite(rule: () -> Unit)` |
| the throw that means expansion did not happen | `src/jvmMain/kotlin/afield/macros/RewriteDsl.kt:62` | `private fun erased` |
| the metavariable kinds | `src/jvmMain/kotlin/afield/macros/RewriteDsl.kt:34` | `sealed interface MetaKind` |
| the expander | `src/jvmMain/kotlin/afield/macros/PatternRewrite.kt:76` | `object PatternRewrite` |
| where the REPL calls it | `src/jvmMain/kotlin/afield/KotlinInterface2.kt:2549` | `PatternRewrite.expand` |
| its smoke test | `src/jvmMain/kotlin/afield/PatternRewriteSmokeTest.kt:14` | `kotlin.pattern-rewrite.smoke` |

The design idea is worth stating plainly, because it is not obvious and it is cheap: **a rule body only
has to parse, never type-check.** PSI parsing runs no name resolution, so `from { max(x, y) }` parses
into a valid tree even though `max`, `x` and `y` resolve to nothing (`RewriteDsl.kt:15-18`). That is
what removes the need for an unquote syntax: the lambda's declared parameters *are* the metavariable
set, so a name is a hole iff it is one of them, and there is no `$` or `,` escape to design.

The corollary is the `erased(...)` throw (`RewriteDsl.kt:62-66`). Those functions must never run; if one
does, expansion did not happen, and the throw says exactly that instead of producing a wrong answer.
`fnrewrite.js` (`:495-506`) gives the same surface an editor extension, so the pattern-rewrite layer is
Route A *and* shape four at once.

## The contrasting axis: Python and Clojure

Python and Clojure are main languages that ship **no lexer, no parser and no Monaco module of their
own**. They wrap a host interpreter and use the *box-graph* mechanism instead:

| what | site | anchor |
| --- | --- | --- |
| the interpreter binding | `src/jvmMain/kotlin/afield/python/PythonInterface.kt:21` | `import jep.` |
| the legacy per-box root flag | `src/jvmMain/kotlin/afield/python/PythonInterface.kt:37` | `var Box.isPythonRoot` |
| the toggle predicate: root flag **or** `mainLanguage` | `src/jvmMain/kotlin/afield/python/PythonInterface.kt:179` | `it.isPythonRoot == true` |
| Clojure's equivalent | `src/jvmMain/kotlin/afield/clojure/ClojureInterface.kt:547` | `var Box.isClojureRoot` |

The mechanism to understand is `applyRuntimeToggle` (`LanguageRuntimeSupport.kt:43-89`). A *language
root* box confers its language on its **children**, and a child stays in that language while it still
has *any* language-root parent (`:79`). The comment there explains why: a notebook cell can be a child
of both the document root and a python-root, and making the language follow the language-root parent —
rather than exclusive parenting — is what makes the toggle order-independent.

The practical consequence, which has cost time before: to make a box run Python you must child it to a
python-root **and** disconnect it from the document root, or the next `applyRuntimeToggle` pass flips it
back to Kotlin.

This axis is the cheapest way to add a language to Field and the one that gains the least: you get
execution and output for free and no editor intelligence at all.

## Docs capture: how a language's page stays true

A language's public page is prose plus fenced blocks, and a fenced block is either shown or *run*.

Note the path: the hooks module is `public/docs_hooks.py`, **not** `tools/docs_hooks.py`.
`tools/build_zensical_docs.py` only imports and drives it.

| what | site | anchor |
| --- | --- | --- |
| the fence names | `public/docs_hooks.py:42` | `RUNNER_TARGETS` |
| the two that execute | `public/docs_hooks.py:48` | `RUNNER_FENCES` |
| the function that finds and runs them | `public/docs_hooks.py:1899` | `def process_markdown` |
| where the build imports the hooks | `tools/build_zensical_docs.py:211` | `import docs_hooks` |
| the full attribute set of a runner fence | `public/docs_hooks.py:217` | `class RunnerOptions` |
| where every fence attribute is parsed | `public/docs_hooks.py:2121` | `def parse_runner_options` |
| where `data-lang=` is read | `public/docs_hooks.py:2311` | `def fence_language_key` |
| **per-language wrapping of the fence body** | `public/docs_hooks.py:2316` | `def wrap_runnable_code_for_language` |
| **per-language box construction** | `public/docs_hooks.py:3840` | `elif language == "fieldml":` |
| the accepted `capture=` modes | `public/docs_hooks.py:2144` | `if capture not in` |
| `capture="ui"` drives the live editor | `public/docs_hooks.py:1564` | `drive the live editor` |
| the still capture for `capture="box"` | `public/docs_hooks.py:4031` | `screenshot-box` |

`diffstatic {data-lang=x}` is highlighted and not executed; `diffstatic_runner {…}` is executed in a
real box in a real Field and its screenshot, video or audio is captured. There are **6** `capture=`
modes — `box`, `window`, `window-editor`, `editor`, `native-window`, `ui` — and for a language page you
almost always want `capture="box"`.

**The two rows in bold are the ones that will surprise you.** Getting a new language's snippets to
capture is *not* just a matter of writing `data-lang=mylang`:

- `wrap_runnable_code_for_language` (`:2239-2289`) has an explicit branch per language that needs its
  body wrapped before it will run — `nano` (`:2241`), `morpho` (`:2264`), `forth` (`:2276`) — and
  otherwise passes the body through verbatim (`:2289`).
- `build_backdoor_command` has a branch that builds a *different box graph* per language. FieldML's
  (`:3758-3790`) creates a root box, sets `mainLanguage fieldml` on it, creates the code box, and
  connects them — because a main language is conferred by a language-*root* parent, so a docs capture
  of a Route B language needs two boxes, not one. The plain single-box path is the `else` at `:3791`.

So the docs-capture cost of a new main language is one branch in each of those two functions. That is
the honest number, and it is not zero.

Two rules that are not negotiable here:

- **Never fake a capture.** If the pipeline cannot produce the picture, fix the code or the pipeline, or
  omit the picture. An honest gap beats a fake one, and a fake screenshot of a language is a lie about
  the language.
- **Make the page's snippets executable by a test too.** A capture proves a snippet ran *once*, on the
  day the asset was generated. `FieldMLSmokeExamplesTest` and RectSL's `checkPublicDocsExamples` prove
  they still run today, and they cost nothing to run because they never start a server.

## Adding a language, in the order that works

Traced against FieldML, which is the only one that went the whole way.

1. **Write the compiler as a pure function of text.** `FieldMLCompiler.compile`
   (`FieldMLCompiler.kt:26`) takes a `String` and returns a `CompilationResult` with *every*
   intermediate form. No `Box`, no output, no server. Everything below depends on this being callable
   from a plain unit test.
2. **Split the prelude into types and values.** Two maps keyed by the same names
   (`FieldMLTypePrelude.kt:67`, `FieldMLRuntimePrelude.kt:33`). Do this before the prelude is big; it is
   painful to split later, and it is what makes step 6 possible.
3. **Take host effects as prelude parameters, not as language features.** `values(output, box)` —
   `display` and `draw` are natives closed over the box (`FieldMLRuntimePrelude.kt:45`, `:301`).
4. **Implement `BoxExecution` and register it.** `class FieldMLExecution` (`FieldMLInterface.kt:45`),
   `registerMainLanguageRuntime` in an `@RunsAt(early)` installer (`:238-248`), a `TopologyWatcher` in a
   `@RunsAt(plugins)` one (`:251`). Reuse `applyRuntimeToggle`; do not write your own root propagation.
5. **Ship the Monaco module and add it to two lists.** One `.js` under
   `src/commonMain/resources/` (`fieldml.js`, 91 lines), then `FIELD_LANGUAGE_MODULES`
   (`bootstrapMonaco.js:46`) and `DEFAULT_LANGUAGES` (`fieldSuggest.js:17`). Make your Monaco language
   id equal your `code_language` string, lower-cased.
6. **Route completion into the compiler's own knowledge.** Override `complete` to call an engine that
   reads the same prelude (`FieldMLInterface.kt:153` → `FieldMLCompletionEngine.kt:41`). This is the
   payoff and the reason for steps 1 and 2.
7. **Write the page, then the guard, in that order and the same commit.** A cheatsheet whose rows are
   compared to the prelude in both directions (`FieldMLCheatsheetCoverageTest.kt:31-51`) and an
   executes-every-snippet test (`FieldMLSmokeExamplesTest.kt:61`).

Steps 1-4 give you a language. Step 6 is what makes it worth having promoted. Step 7 is what stops it
quietly becoming a lie.

## The coverage-guard pattern

Every small language here ships a public page, and every public page rots. The pattern each one uses
is the same idea in three different shapes, and picking the wrong shape is how a page goes stale
without the build noticing.

**Shape 1 — exact set, both directions.** `FieldMLCheatsheetCoverageTest` scrapes the cheatsheet's
`| name | type |` rows and compares them to `FieldMLTypePrelude.schemes` in *both* directions: a
prelude name missing from the page fails (`FieldMLCheatsheetCoverageTest.kt:37-40`), a page row the
prelude no longer has fails (`:43-46`), and each row's printed type must equal `printSignature`
exactly (`:48-50`). Duplicate rows fail too (`:26`). No count is hardcoded, so the number is derived
rather than maintained — the effectively-pinned figure today is **95** prelude names.

This is the strongest shape and it is available whenever the surface is a *data structure*. If your
language has a prelude map, use this.

**Shape 2 — every operator must have a worked example.** RectSL's guard
(`RectSLExamples.kt:441-464`) scrapes the cheatsheet's operator tables, builds a corpus out of the
gallery's `.rectsl` programs plus the code blocks of `rectsl_examples.md`, canonicalises aliases, and
fails when a documented operator appears in no example. It deliberately checks a *floor* rather than a
count (`operators.size > 100`, `:448`), because the scrape itself can silently break and a floor
catches that where an equality would just have to be updated.

This shape is for a surface that is a vocabulary rather than a typed map. Note that it does not
verify the example *is* an example of that operator — only that the token occurs — which is a real
limitation and the reason the examples are also executed.

**Shape 3 — execute every snippet on the page.** `FieldMLSmokeExamplesTest.kt:61-80` compiles *and
evaluates* every `data-lang=fieldml` block in both public pages against a throwaway `Box`, so
`draw`/`display` snippets exercise the real host path. Its snippet regex (`:93`) accepts both
`diffstatic` and `diffstatic_runner` on purpose, so a block does not escape the check by not being a
capture. RectSL does the same for its page (`RectSLExamples.kt:388-410`).

**Shape 4 — a vocabulary mirrored across the JVM/browser boundary, in both directions.** `fnrewrite.js`
keeps a curated `opWords` list (`fnrewrite.js:21`) that has to stay in step with
`afield.stdlib.OpRegistry`. `FunctionsGraphRewriteTest.editorOpVocabularyMirrorsTheRegistryInBothDirections`
(`FunctionsGraphRewriteTest.kt:105`) reads the `.js` file from Kotlin, scrapes the list, then asserts
forward (every editor op is known to the registry) *and* reverse (every transmutable target appears in
the editor). This is the shape to use whenever a duplicated list is the price of the browser/JVM split —
which for a Route A language it always is, because the word table exists twice.

Shapes 1 and 3 are complements, not alternatives: shape 1 says the page lists the right names, shape 3
says the page's code still runs. A language with only one of them has a known hole.

**Shape 1 has a second use that is not about a page at all.** `BriefCoverageTest` applies it between two
*implementations*: it reflects over `ConstraintScope`'s declared members and requires the notation's
table to classify every one, then asserts a floor on the covered fraction (31 of 37 relations, 83.8%,
floor 82%). The failure it catches is the one a page-oriented guard cannot see — a verb added to the
Kotlin DSL and not to the notation breaks nothing, because the Kotlin caller is fine and the notation
merely cannot say the new thing. Reach for this whenever one surface is meant to cover another.

There are **9** coverage guards over these languages' pages and vocabularies:

| guard | shape | site | anchor |
| --- | --- | --- | --- |
| FieldML cheatsheet ↔ type prelude | 1 | `src/jvmTest/kotlin/afield/ml/tests/FieldMLCheatsheetCoverageTest.kt:16` | `fieldml_cheatsheet.md` |
| FieldML operators are documented | 1 | `src/jvmTest/kotlin/afield/ml/tests/FieldMLCheatsheetCoverageTest.kt:63` | `\|>` |
| every FieldML doc snippet compiles and evaluates | 3 | `src/jvmTest/kotlin/afield/ml/tests/FieldMLSmokeExamplesTest.kt:61` | `docsFieldMLExamplesCompileAndEvaluate` |
| every RectSL cheatsheet operator has a worked example | 2 | `src/jvmMain/kotlin/afield/examples/rectsl/RectSLExamples.kt:441` | `checkCheatsheetCoverage` |
| the RectSL operator scrape has not silently broken | 2 | `src/jvmMain/kotlin/afield/examples/rectsl/RectSLExamples.kt:448` | `operators.size > 100` |
| fnrewrite's editor op list ↔ `OpRegistry`, both ways | 4 | `src/jvmTest/kotlin/afield/stdlib/FunctionsGraphRewriteTest.kt:105` | `editorOpVocabularyMirrorsTheRegistryInBothDirections` |
| this page's own citations | 1 | `src/jvmTest/kotlin/afield/docs/SmallLanguageAnatomyTest.kt:34` | `class SmallLanguageAnatomyTest` |
| Brief's constructs ↔ `ConstraintScope`'s members | 1 | `src/jvmTest/kotlin/afield/brief/BriefCoverageTest.kt:35` | `class BriefCoverageTest` |
| `brief.js`'s vocabulary ↔ `BriefVerbs`, both ways | 4 | `src/jvmTest/kotlin/afield/brief/BriefEditorExtensionTest.kt:39` | `theEditorVocabularyMirrorsBriefVerbsInBothDirections` |

RectSL's guard runs as a `FIELD_TEST` catalog entry rather than a JUnit test —
`RectSLExamples.kt:13-23` declares `id: rectsl.examples.smoke`, `runner: jvm-main` — so it is invoked
by `python3 tools/run_field_tests.py --id rectsl.examples.smoke`, not by `./gradlew check`. That is a
consequence of it needing the compiler *and* the workspace assets. If you are adding a guard and it
only needs source files, prefer a `jvmTest` so it runs in `check`.

### The gap this survey found

**StackSL's public pages are unguarded.** `public/docs/stacksl_cheatsheet.md` and
`public/docs/stacksl_examples.md` have no coverage guard of any shape: a repo-wide search for either
filename under `src/` finds one hit, and it is a prose cross-link in
`StdlibReferenceGenerator.kt:1128`. StackSL's smoke
(`src/jvmMain/kotlin/afield/examples/stacksl/StackSLSmokeTest.kt:13`, id `stacksl.compiler.smoke`) does
compile every gallery program (`:842`) and check the workspace metadata (`:872`), so the *gallery* is
guarded — but nothing compares the cheatsheet to `builtinWords`, and nothing compiles the examples page.

This is the older sibling being the less-guarded one, which is the usual direction: RectSL's guard was
written because RectSL's cheatsheet was drifting, and StackSL never had that particular bad day. Shape 1
is directly available — `StackSL.kt:2645 builtinWords` is a `Set<String>`, exactly the data structure
that makes an exact-set assertion possible. It is the highest-value guard missing from this repo's
language surface, and this page does not fix it.

## Serving the `.js`, and the cache trap

Ledger points 22-24. `Server.kt:4647` serves `src/commonMain/resources/` at the site root, and
`bootstrapMonaco.js:19-23` maps the AMD prefix `bithero` to `./`, which is the whole of why
`define("bithero/fieldml", …)` resolves to `fieldml.js`. Adding a language module means dropping a file
in that directory; there is nothing to register on the server side.

The trap is `assetCache` (`Server.kt:4627-4643`). An interactive browser gets `Cache-Control: no-store`
(`:4631`), but a `HeadlessChrome` user agent — every docs capture and every Playwright verification —
gets `public, max-age=3600` on `.js` (`:4635`, applied at `:4640-4641`). So a headless run can serve the *previous*
version of your language module for an hour, and the symptom is that a correct fix looks broken. If a
headless check disagrees with an interactive one about your `.js`, suspect this before your code.

## What the guard checks

`SmallLanguageAnatomyTest` holds this page to the code in six ways. Two of them are worth knowing
precisely, because they bound what "this page is checked" means.

1. **Citations.** Every table row whose last two cells are `` `path:line` `` and `` `anchor` `` is
   re-checked: the anchor must occur within ±2 lines of the cited line. There are **134** checked
   citations. The slack is deliberate — reformatting inside a function should not fail, moving a
   function should. A `|` in an anchor is written `\|` and unescaped by the test.
2. **Derived counts.** Four numbers on this page are computed from the code and compared to the prose:
   the **8** main languages (scraped from `registerMainLanguageRuntime` call sites), the **14** editor
   language modules (from `FIELD_LANGUAGE_MODULES`), the **14** languages the suggest widget serves
   (from `DEFAULT_LANGUAGES`), and the **8** optional hooks (from `callExtension` calls). Each also
   asserts the individual names appear on the page, so adding a language fails the build until it is
   documented here.
3. **The Monaco-provider claim.** `msl.js` must remain the only editor resource registering a Monaco
   completion provider.
4. **Table sizes.** **134** checked citations and **9** coverage guards, both asserted, so deleting a
   ledger row is as loud as breaking one.

What it does **not** check, stated so nobody assumes otherwise:

- **Prose claims outside a table.** Every `file:line` written in a sentence rather than a table cell is
  unguarded. They were verified by hand when this page was written and will rot. If a claim matters,
  move it into a table.
- **That the description is true.** The guard proves the cited line still contains the anchor token; it
  cannot prove the sentence next to it describes what that code does.
- **Anything in `public/docs/`.** The two `language_extensions.md` section citations are line *ranges*
  in prose, not table rows, and are not checked.
