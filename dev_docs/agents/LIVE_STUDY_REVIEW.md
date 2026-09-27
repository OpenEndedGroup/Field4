# Live studies: what made them hard, and what was fixed before doing more

Written 2026-09-26, after building "Talking a shader into existence" (StackSL) on top of "Talking a
function into existence", and before doing the same for RectSL. Everything below was hit while
building, recording or documenting those two; each item names where it bit.

## How it looks from the outside

**Starting a study takes a ritual.** The StackSL page asks a reader to download a zip, unzip it into
the workspace, open it, launch the window box, run the tools box (in that order), select the program
box and press command/ctrl-4. The function page is the same minus the window. Both pages also carry
the warning "rerun the tools box in the document you want to chat with", because the last tools box
run wins for every open document.

**Live coding from scratch took three boxes and two shader files.** A person who wanted to type
`uv fbm palette` and see it needed a window box (8 lines), a live box (13 lines plus two shader files
carrying the `{{ stack.auto.program }}` template), and a program box. A person who wants an assistant on that
program also needs the 140-line tools box. None of these is discoverable; there is no "StackSL box".
Field's own idiom for a small language, the main-language root (Forth, Nano, FieldML, Morpho, ...),
is not offered for StackSL or RectSL.

**The editor does not tell the truth about StackSL.** `stacksl.js` shows warnings from its own
JavaScript re-analysis. The real compiler's errors ("shadows a built-in", unknown word, a definition
that leaves two values) appear only in study tool results, so a person typing in the program box
sees a window that silently keeps the last program that compiled.

**The card is a wall.** For the recorded aurora it shows 95 sliders labelled `k0`..`k94`, and 9 of its
13 stage tiles are the same psychedelic ramp: a line that ends in a store (`>name`) leaves only the
program's input on top, so the tile shows the input. The same property made the first stage
comparison in the tiles session vacuous (now flagged). Colour literals are three unlabelled sliders.

**The tools are boilerplate in the document.** 140 lines (StackSL) and 198 lines (function) of request
classes and registrations are copied into every document, held equal across copies by generator
scripts. Their descriptions drifted (three still said "CPU reference" after the GPU path landed), and
the Kotlin is compiled by nothing (`AgentDemoRegistrationTest.UNVERIFIED`). The extension story the
pages tell, "a document can teach the assistant a new tool", is real, but it does not need every
default tool pasted into every document to be told.

## How it feels to an agent

**Tools are global by name.** `AgentToolExtensions` keys registrations on the tool's simple name, so
two open study documents fight over `InspectStackSLStudy`. `ToolExecutionContext` carries the
document but not the box the conversation was about, so a tool could not scope itself to "the program
you are talking about".

**The window dies between invocations.** A named document opened by the backdoor, with no browser
page attached, detaches when the client disconnects, and `SessionInformation.cleanup()` disposes
every renderer it owns. Each recorded turn had to relaunch the window. The relaunch then reports
`ok:false`, because the Vulkan loader's informational log arrives on the box's error channel, so
every launch needed `--no-fail-on-error` plus `wait-native-renderer` as the real check.

**GPU evidence has a thread trap.** `StackSLGpuRasterizer` waits for a frame that the main thread
draws; called on the main thread it silently falls back to the CPU and blocks the thread. The card
did exactly that until its render moved to the viewer's own thread.

## What it costs on disk

Every sidecar is written twice: as its file and as a `#afield/file-inline` copy inside
`metadata.edn`, and both get backups. The AI4 chat history is a sidecar carrying every image as
base64, so the aurora document's 4.6 MB chat was rewritten into `metadata.edn` on each save: 127 MB
of `backups/` for five turns. A long agent session on one document is a disk problem.

## What it costs to publish

Each demo grew its own recorder, person-action driver, capture script, packager, transcript renderer
and document generator (five scripts for StackSL, four for functions), all near copies. Their
`--check` modes were gated by nothing until `tools/test_stacksl_conversation.py`; the function
packager's check is failing on `main` today (`conversation.css` drifted from the renderer's CSS).
Opening a recorded example needs "copy the folder into your workspace first", because continuing the
conversation writes into it.

## What to fix, in order

1. Scope tools: `ToolExecutionContext` learns the focused document, box and property; registrations
   may carry a scope, and dispatch picks the registration whose gate passes.
2. StackSL as a main language: children of a StackSL root run as StackSL, with the real compiler's
   diagnostics, a live preview in the box's output, and, under a window, the program live in that
   window. The live box and its shader files move into the library.
3. Study tools as library code, registered once and offered when the conversation is focused on a
   study box. Documents shrink to the window and the program; a document still adds its own tools
   in a few lines. The same for the function study.
4. Opening a study document starts it (window `autorun`), so the reader's steps are "open, talk".
5. Truthful editing: compiler diagnostics for a definition that does not leave one value and for a
   mark named `x`/`y`/`z`/`w`; the editor shows the real compiler's errors.
6. A card a person can read: stage tiles show the value a store line stored; knobs grouped under the
   line and comment they belong to; colours as colours.
7. Storage: large sidecars are referenced from `metadata.edn` rather than inlined.
8. Automation: the loader log is not an error; the backdoor can keep a document's window alive.
9. One conversation-demo toolkit for recording, acting, capturing, packaging and checking, used by
   both existing demos and gated in `checkPythonTools`.
10. Then RectSL, built on all of the above from the start.

## Done, 2026-09-26

1. `ToolExecutionContext.focus` (`Tools.kt#ToolFocus`), set by AI4 for a box chat and by the
   backdoor's `focusBox`; `AgentToolExtensions.register(…, scope, …)` with dispatch to the newest
   registration whose gate passes (`AgentToolExtensionsTest#twoDocumentsKeepTheirOwnToolOfTheSameName`).
2. `StackSLPrograms.kt#installStackSLLanguage`: StackSL main language, `StackSLExecution`
   (diagnostics on lines, follow edits, run = check in) and `StackSLLive` (the live window binding).
3. `StackSLStudyTools.kt` and `FunctionStudyTools.kt`: library tools gated on the focus, one study per
   box under `studies/<box id>` (`SourceStudy.kt#BoxStudies`), guides served by `StackSLStudyGuide`
   and `FunctionStudyGuide`, "Make a Function Study". The four StackSL documents and both function
   documents lost their tools and live boxes; the StackSL starter is two boxes.
4. The StackSL window and program autorun; a function study draws its viewer when the document opens.
5. `StackSLStudy.diagnose`, including a targeted message for a mark named after a lane
   (`StackSLStudyTest#aMarkNamedAfterALaneIsExplained`); the runtime reports on lines as you type.
   Unbalanced definitions are reported by `diagnose`, not yet by the compiler itself.
6. `StackSLOutline.stageProgram`: store lines show and compare the value stored; the card groups
   knobs under their line's comment, offers colour swatches, and reaches every number.
7. `saving.kt#referencesSidecar`: chat histories and sidecars over 256 KB are referenced from
   `metadata.edn`; the function example's `metadata.edn` went from 1.4 MB to 11 KB.
8. `Instance.kt`: only error/warning/printf Vulkan messages reach a box; `AgentOpenDocument.keepRunning`
   keeps a document's windows between automation invocations; `run`/`launch` follow a box's
   `default_property`.
9. `tools/conversation_demo.py` (record, act, pages, zips, checks) replaced five near-duplicate
   scripts; `tools/test_conversation_demos.py` gates both demos (the function packager's drift is
   fixed).

## RectSL, built on the above — 2026-09-27

"Talking a scene into existence" (`public/docs/rectsl_conversation.md`) took the new pieces as they
were: a `rectSL` box property (sidecar `.rectsl`, Monaco language `rectsl`), "Set Main Language:
RectSL" (`RectSLPrograms.kt`), fifteen library tools gated on the focus (`RectSLStudyTools.kt`), a
card, one study per box, a two-box starter that opens by itself, and the conversation toolkit for
recording, acting, capturing and packaging. Three sessions were recorded against the running window.

What building it turned up, and what was fixed rather than worked around:

- **RectSL could not say where anything was.** Its tokens carried no positions, so a problem had no
  line and a rectangle no source. The tokenizer now records offsets, the evaluator the token it
  failed on, and the scene each node as it is made (`RectSLSource.kt#compileRectSLWithSource`): the
  editor marks problems on their lines, and Trace, Timeline and Compare name each rectangle's line.
- **"+y renders downward" was false.** A memory from before the readback fix of 2026-08-28 said so,
  and a docs page named the fit figure's rows upside down because of it. `rectsl.study.gpu-agreement`
  now pins +y up on the GPU, in the window and in the CPU reference.
- **Linear-light blending made faint things loud.** The first recorded turn's 2.5% glows came out as
  maroon boxes, because the sRGB window blends in linear light. The study and the window now draw
  into an 8-bit target and blend on the typed values, as the RectSL gallery and design tools do; the
  smoke pins a 3% wash. The first turn was recorded again.
- **The analysis counted off-wall rectangles as visible**, so a scene whose rectangles had all left
  looked full and `MostlyBlank` could not fire (`RectSL.kt#FrameSemanticMetrics`). Its overlap
  warning also fired on every backdrop and every composed object; the study recounts it between
  objects of comparable size.
- **A page that attached late had no card** — the "not done" item above, which also broke the
  recorder's card clicks. `BoxStudies.show` remembers each card and replays it to a page attaching
  to a live document. A card drawn before its window existed said "CPU reference" for good; it is
  now redrawn once the window is up, and fallback frames are no longer cached.
- **The recorder relaunched a window but not the program in it**; it now starts the study box too.
- **Exports wrote a PNG per frame** and ran at two frames a second; they stream raw frames to ffmpeg.
- **Image captions printed times to three significant figures**, so 17.933 s read "18.0".
- **Alt-key property switching was matched by position** (`BootstrapMonacoInit.kt`); inserting `msl`
  had moved Alt-P and Alt-D down a property. It is by name now.

Not done: `box.display` from library code still needs the box to have run (library code uses
`BoxStudies.show`); only study cards are replayed to a late page, not box outputs in general; the
StackSL compiler itself still prunes an unbalanced definition's surplus silently; the RectSL editor
extension's own diagnostics stay lexical, beside the runtime's real ones.
