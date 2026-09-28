# Clojure in a box

*The implementation is one file, `src/jvmMain/kotlin/afield/clojure/ClojureInterface.kt`, plus one
namespace of Clojure, `src/jvmMain/resources/field/core.clj`. Clojure is a
[promoted main language](language_extensions.md), so a Clojure box has outputs, can be launched, can
be stopped, and sits in the same document as the Kotlin boxes around it.*

Field runs **Clojure 1.11.3** in its own JVM, and nothing in Field implements a Lisp: `clojure.jar`
is on the classpath, and the file above is the adapter between Field's box-execution contract and
`clojure.java.api.Clojure`. So a Clojure box and a Kotlin box are two objects in one process. A box
can hold a reference to the `Box` it is running in, hand an `FLine` to Field's renderer, and call
anything in Field's standard library, with no bridge, no serialisation and no second runtime to
start.

Everything below is real output from a running box.

## A Clojure box is a child of a Clojure root

**The language is conferred by a parent, not by the box itself.** Select a box and run
*Set Main Language: Clojure* from the command palette (`set-main-language-clojure`); that writes
`mainLanguage = "clojure"` on it, and every box childed to it from then on runs as Clojure.

Two consequences, the same two [Python](python.md#a-python-box-is-a-child-of-a-python-root) has:

- **The root itself is not Clojure.** `applyRuntimeToggle` (`LanguageRuntimeSupport.kt`) walks
  `root.children` and swaps the execution on each child; it never touches the root. Put Clojure in
  the root box and it is still a Kotlin box, and will fail to compile.
- **A box stays Clojure while it has *any* Clojure-root parent**, so a box childed to both the
  document root and a Clojure root is Clojure, and the toggle is order-independent.

`mainLanguage` is persisted with the document, so the arrangement survives a reload. An older
per-language flag, `Box.isClojureRoot`, is still honoured (`clojureTopologyNeedsUpdating` counts a
box as a root if either is set), but `mainLanguage` is the one to reach for.

## Output lands beside the form that made it

The block below is a Clojure box's `code`, and the picture under it is that box's editor.

<!--
FIELD_TEST
id: docs.clojure.output
runner: backdoor-box
tags: [docs, clojure, inline-output]
timeoutSec: 180
waitSec: 2.0
expect:
  outputContains:
    - "clojure 1.11.3"
    - "4 edges"
-->
```diffstatic_runner {data-lang=clojure screenshot="assets/clojure/output.png" capture="editor" outputs="inline" wait="4" padding="10" timeout="240" embed=true name="clojure-output" w="900" h="360" alt="A Clojure box in Field's editor. Beside line 2 a chip reads clojure 1.11.3, and beside the last line a chip reads 4 edges. The parentheses are coloured by nesting depth."}
:::static
;; Each line of output lands beside the form that made it.
(println "clojure" (clojure-version))

(def modules {:app [:ui :store], :ui [:util], :store [:util]})

(println (count (mapcat val modules)) "edges")
```

That placement is why a box is **not** evaluated by handing the whole buffer to `load-string`. `ClojureHost.evalPage` runs `load-string`'s own loop
written out. Read one top-level form, evaluate it, read the next: while a form is running, the line
it started on is known. `*out*` is bound to a writer that flushes on newline and tags each
flush with that line. The line comes from the form's own reader metadata (an
`indexing-push-back-reader`, the same reader the syntax check uses), not from counting text.

`print` without a newline is buffered until the next newline or the end of the run, so a bare
`(print …)` is attributed to whichever form was running when it finally flushed, which is usually
but not always its own.

## A box that prints nothing shows its last value

<!--
FIELD_TEST
id: docs.clojure.last-value
runner: backdoor-box
tags: [docs, clojure, inline-output]
timeoutSec: 120
waitSec: 1.5
expect:
  outputContains:
    - "385"
-->
```diffstatic_runner {data-lang=clojure screenshot="assets/clojure/last_value.png" capture="editor" outputs="inline" wait="4" padding="10" timeout="240" embed=true name="clojure-last-value" w="900" h="240" alt="A Clojure box whose only form is a threaded expression, with the result 385 shown beside it in the editor."}
:::static
;; Nothing is printed, so the value of the last form is what you see.
(->> (range 1 11)
     (map #(* % %))
     (reduce +))
```

The echo is `pr-str` of the value, not Java's `toString`, so a vector arrives as `[1 2 3]` rather
than `[1, 2, 3]`. It is withheld in two cases, both of them the ones a
[Python cell](python.md#one-interpreter-one-namespace) makes: when the box has already printed
something, and when the value is `nil`. `println` and `(lines …)` both evaluate to `nil`, so
without that second rule almost every box that draws something would carry the word "nil" beside
its last line. A `def` is not one of those: it evaluates to the var it defined, so a box that only
defines things still echoes something (`#'live.user/spiral`), exactly as a REPL would.

## One namespace, every box

There is one Clojure runtime for the whole Field process, and every Clojure box in every open
document evaluates in **one namespace**, `live.user`. Clojure namespaces are per-JVM, and
`ClojureExecution` asks for that one name, so two Clojure boxes are two cells of one notebook, not
two notebooks: a `def` in one box is visible in the next, and state persists between runs. It also
means a name you thought you had deleted is still there, and that two boxes can fight over one
name.

The namespace is initialised once, the first time it is used, with `clojure.core` and Field's own
`field.core` referred into it. That is the only preparation: there is no per-box prelude, no
implicit `(ns …)`, and no rewriting of the text you wrote.

## What a box can reach

Clojure's Java interop already reaches all of Field, and most of the time that is what to use:

```diffstatic {data-lang=clojure}
(.getName (box))                          ; box properties and topology
(.getChildren (box))
(doto (afield.graphics.FLine.)             ; Field's own types
  (.moveTo 0 0 0)
  (.lineTo 100 100 0))
```

What interop is *bad* at is Kotlin's two favourite shapes: an extension function, which compiles to
a static method on a class named after a *file*, and a default argument, which is not a default at
all from outside Kotlin, so every parameter has to be passed. `field.core` wraps exactly those, and is
referred into every box, so there is nothing to require:

<div class="field-reference" data-code-lang="clojure" markdown>

| form | what it does |
| --- | --- |
| `(box)` | Returns the `Box` this code is running in. |
| `(display v)`, `(display channel v)` | Renders a value in an output panel using Field’s value renderer. Supply a channel name to update that output independently. |
| `(lines ls :w 700 :h 350 :fit true)` | Draws the supplied `FLine` collection in a 700 × 350 output. `:fit true` fits the drawing to the view. |
| `(polyline [[x y] …])` | Builds an `FLine` through the points in sequence. |
| `(color line r g b a)`, `(thickness line w)` | Sets line colour or stroke thickness and returns the line, so `->` threads. |
| `(animate f)` | Builds a per-frame animation around `f`. Return the animation from the box to launch it. |
| `(frame)`, `(end)` | Inside an animation body: reads the current frame number, or stops now. |
| `(prop "name")`, `(prop! "name" v)` | Reads or writes a property of this box. |

</div>

Properties are the box's own storage, and they outlive a run:

<!--
FIELD_TEST
id: docs.clojure.box-object
runner: backdoor-box
tags: [docs, clojure, inline-output]
timeoutSec: 120
waitSec: 1.5
expect:
  outputContains:
    - "run number 1"
    - "characters of Clojure"
-->
```diffstatic_runner {data-lang=clojure screenshot="assets/clojure/box_object.png" capture="box" open-outputs=true trim=true wait="3" wait-output=true wait-output-kind="all" timeout="240" embed=true name="clojure-box-object" w="820" h="80" alt="A Clojure box's summary output panel, showing the map it displayed: :name clojure-box-object, :runs 1, :language Clojure, rendered as three rows with their Clojure types named."}
:::static
;; The box is a JVM object. Its properties are its storage, and they outlive a run.
(def me (box))

(prop! "runs" (inc (or (prop "runs") 0)))

(println (str (.getName me) ": run number " (prop "runs")
              ", " (count (prop "code")) " characters of Clojure"))

(display "summary" {:name (.getName me) :runs (prop "runs") :language (prop "code_language")})
```

Run it twice and the count goes up: the second run is the same namespace talking to the same box.

Clojure's own libraries are on the classpath too (`clojure.string`, `clojure.set`, `clojure.walk`,
`clojure.pprint`, `clojure.edn`, `clojure.data.json`, `clojure.tools.reader`), so `require` is just
`require`. What you cannot do is add a *new* dependency from inside a box: the classpath is
Field's, fixed when the process started.

<!--
FIELD_TEST
id: docs.clojure.libraries
runner: backdoor-box
tags: [docs, clojure, inline-output]
timeoutSec: 120
waitSec: 1.5
expect:
  outputContains:
    - "a / b / c"
    - "\"forms\":3"
-->
```diffstatic_runner {data-lang=clojure screenshot="assets/clojure/libraries.png" capture="editor" outputs="inline" wait="4" padding="10" timeout="240" embed=true name="clojure-libraries" w="900" h="280" alt="A Clojure box requiring clojure.string, clojure.set and clojure.data.json, printing a joined set and a JSON object beside the lines that produced them."}
:::static
;; Clojure's own libraries are on Field's classpath, so `require` is just `require`.
(require '[clojure.string :as str]
         '[clojure.set :as set]
         '[clojure.data.json :as json])

(println (str/join " / " (map name (sort (set/union #{:a :b} #{:b :c})))))
(println (json/write-str {:box (.getName (box)) :forms 3}))
```

## Drawing

An `FLine` is Field's geometry type and a Clojure box makes them like anything else. `polyline`
builds one from points, `color` and `thickness` style it, and `lines` puts it in an output panel.

<!--
FIELD_TEST
id: docs.clojure.spiral
runner: backdoor-box
tags: [docs, clojure, display]
timeoutSec: 180
waitSec: 2.0
expect:
  outputContains:
    - "500 points"
-->
```diffstatic_runner {data-lang=clojure screenshot="assets/clojure/spiral.png" capture="box" open-outputs=true trim=true wait="3" wait-output=true wait-output-kind="display" timeout="240" embed=true name="clojure-spiral" w="760" h="80" alt="The lines output panel of a Clojure box, holding a yellow Archimedean spiral of five hundred points: four turns, drawn anticlockwise from a tight centre, on black."}
:::static
;; A spiral, built in Clojure, drawn by Field.
(def spiral
  (polyline (for [i (range 500)
                  :let [t (* i 0.05)
                        r (* t 5.5)]]
              [(+ 350 (* r (Math/cos t)))
               (+ 175 (* r (Math/sin t)))])))

(println (count (.getNodes spiral)) "points")

(lines [(-> spiral (color 0.98 0.86 0.3 0.9) (thickness 2))] :w 700 :h 350)
```

`(.getNodes spiral)` in the middle there works because `polyline` handed back a real `FLine`, and
from that moment it is an ordinary JVM object with its own API. Everything on
[Drawing with FLines](fline_examples.md) is reachable from a Clojure box by interop; `field.core`
only covers the awkward corners.

## Launching an animation

A Clojure box launches by the same rule a Kotlin box does: **returning** a `Frames` is what starts
it. `(animate f)` builds one that calls `f` once per frame, and nothing in `ClojureExecution` says
the word "animate": the value goes to `ReturnValueRegistry`, which is the same code that drives a
Kotlin box's `animate { }`. See [Frames and the Animation Loop](animation_time_model.md).

So `(animate …)` has to be the **last** form, and you have to *launch* the box rather than run it.

<!--
FIELD_TEST
id: docs.clojure.animate
runner: backdoor-box
tags: [docs, clojure, display]
execution: launch
timeoutSec: 180
waitSec: 4.0
expect:
  outputContains:
    - "frame "
-->
```diffstatic_runner {data-lang=clojure screenshot="assets/clojure/animate.png" capture="box" execution="launch" open-outputs=true trim=true wait="4" wait-output=true wait-output-kind="display" timeout="300" embed=true name="clojure-animate" w="780" h="80" alt="A launched Clojure box with two output panels: one reading a frame number in the hundreds, the other a pale blue travelling sine wave."}
:::static
;; Returning an animation is what launches it.
(def n 160)

(animate
  (fn []
    (let [t (* 0.08 (frame))]
      (display "frames" (str "frame " (frame)))
      (lines [(-> (polyline (for [i (range n)]
                              [(* 4.4 i) (+ 100 (* 70 (Math/sin (+ t (* i 0.06)))))]))
                  (color 0.35 0.78 1.0 0.95)
                  (thickness 2))]
             :w 720 :h 200))))
```

The frame number in that figure is the proof: the box ran its function that many times before the
picture was taken. `(def n 160)` ran once, when the box was launched.

**Stop** cancels the launch by the standard barrier, bumping the box's launch epoch and every
strand's generation, which is what each `Frames` is watching. It does not interrupt an
*evaluation*: a Clojure form runs to completion on the runloop, so a box in an infinite `loop` is no
more stoppable than a Kotlin box in a `while (true)` that never suspends.

## When something throws

An error is reported against the form that threw, not the top of the box:

<!--
FIELD_TEST
id: docs.clojure.error
runner: backdoor-box
tags: [docs, clojure, inline-output]
timeoutSec: 120
waitSec: 1.5
expect:
  # The point of this example is the throw, so the error is the expectation rather than the failure.
  allowExecutionErrors: true
  outputContains:
    - "this line runs"
    - "Divide by zero"
  outputNotContains:
    - "this line does not"
-->
```diffstatic_runner {data-lang=clojure screenshot="assets/clojure/error.png" capture="editor" outputs="inline" expect-error=true wait="4" padding="10" timeout="240" embed=true name="clojure-error" w="900" h="280" alt="A four-line Clojure box. Line 1 shows the chip this line runs, and line 3 — the division — carries the error Divide by zero. Line 4's print never ran."}
:::static
(println "this line runs")
(def parts 0)
(/ 100 parts)
(println "this line does not")
```

Three things that figure shows, in the order they matter:

1. **The line is the failing form's.** An `ArithmeticException` carries no position at all, so the
   line comes from the read-eval loop: whichever form was running. Clojure *does* report a line and
   column for anything the reader or the compiler rejected, and that one is used where it exists.
2. **The forms above it already ran**, and printed. Evaluation is form by form, not a batch.
3. **The forms below it did not.** A throw stops the run.

Anything a box writes to `*err*` arrives the same way, as an error against the line that wrote it.
That is how Clojure's own warnings reach you, like the one for shadowing a `clojure.core` name with
a `def`.

A syntax error is caught before anything is evaluated, by a read-only pass over the text
(`ClojureHost.compileOnly`), and it carries the reader's line and column. That pass is a *reader*,
though, not a compiler. An unresolvable symbol, a macro that throws, a call with the wrong arity:
none of that is known until the form is evaluated, so a Clojure box has no equivalent of the red
squiggle a Kotlin box gets before it runs.

## The editor knows the language

Clojure's Monaco grammar is stock, so the editor needs no module of its own for it, and the
parentheses in the figures above are coloured by nesting depth. That is Monaco's
`bracketPairColorization` and is on for every Field editor.

Completion comes from [Compliment](https://github.com/alexander-yakushev/compliment), asked for the
symbol under the caret, and it is told three things that make the difference between a popup that is
useful and one that is merely present:

- **the namespace, initialised**: the one the box actually evaluates in, with `clojure.core` and
  `field.core` already referred, so completion works in a box that has never been run;
- **`:extra-metadata`**, so each candidate carries its `arglists` and its docstring, which is what
  the panel beside the popup shows;
- **the code up to the caret, as `:context`**, with the prefix replaced by Compliment's
  `__prefix__` marker and the brackets still open at the caret closed for it. That is what lets it
  offer a `let` binding, a `fn` parameter or a `doseq` variable, none of which any amount of
  namespace introspection can know. Two details, both learned the hard way: the context has to
  *read*, and a context that does not read is answered with globals only and no complaint — so
  "locals sometimes work" is the shape this failure takes. And the text *after* the caret is
  dropped, because in a real editor that text is not yours: auto-closing has already put a `)`
  where your `[` wants its `]`.

```diffstatic_runner {data-lang=kotlin capture="ui" region="window" clip-selector=".afield-suggest" screenshot="assets/clojure/completion.png" transcript="assets/transcripts/clojure_completion_setup.yaml" padding="6" wait="0" timeout="180" embed=true hidden=true name="clojure-completion" alt="Field's completion popup in a Clojure box at the prefix sp, grouped by kind. Under FUNCTIONS: spit, split-at, split-with and special-symbol?, each with its argument vector in grey underneath. Then LOCAL, holding spline — the let binding two lines up. Then VALUES, holding a var named spiral, and TYPES, holding a class. The panel to the right reads clojure.core/spit, its argument vector, and its docstring."}
// An ordinary prefix, in code with a `let` binding in scope, so one figure carries both halves of
// what a Clojure candidate is: the shape of one (name, argument vector, docstring) and where it
// came from (core, the binding two lines up, and — as `spiral` under VALUES — a var an earlier box
// on this page left in the shared namespace).
ui.eval("window.localStorage.removeItem('field.suggest.recency.v1'); var s=document.createElement('style');s.textContent='.tick_text{display:none !important}';document.head.appendChild(s);return 'setup';")
ui.openEditor("clojure_completion")
ui.pause(900)
ui.waitForSelector(".monaco-editor", 15000.0)
ui.clickSelector(".monaco-editor")
ui.pause(300)
ui.press("Control+A")
ui.type("(let [spline (polyline [[0 0] [10 10]])]\n  (lines [sp")
ui.pause(400)
// Escape first: typing does not open the popup, and opening it once on the finished text is the
// only way to be sure the request the figure shows is the one for the caret the figure shows.
ui.press("Escape")
ui.pause(200)
ui.press("Control+Space")
// No warm-completion here: Clojure's candidates come from Compliment in Field's own JVM rather
// than from the Analysis API sidecar, so there is nothing to pre-warm — but the first call does
// load `compliment.core`, which is why this waits for a row rather than guessing a pause.
ui.waitForSelector(".afield-suggest-row", 20000.0)
ui.pause(1400)
```

Three of those groups are the three inputs above, in one popup. Each row carries its own argument
vector and the panel carries the docstring, which is `:extra-metadata` arriving —
`clojure.core/spit [f content & options]` is Compliment's answer verbatim, not a signature Field
reconstructed. **`spline` under LOCAL is the `let` binding two lines up**, which is `:context` being
read. And `spiral` under VALUES is a var an earlier box on this page defined, which is the shared
namespace showing through.

Completion is the one part of this language that is allowed to be absent: if Compliment is not on
the classpath, a Clojure box still runs, and the popup is empty rather than broken.

The editor also shows what each form evaluated to the last time the box ran: rest the cursor on a
form and the note beside the cursor says so, the value in bold with its type, how long ago that was, and
whether the form has changed since. It is recorded as the box runs, never by evaluating the form
again, which is why an unrealised lazy sequence is named rather than printed — see
[Line notes](language_extensions.md#line-notes).

## What is not here

- **No per-box namespaces.** Every box shares `live.user`, as above.
- **No nREPL, and no connecting an editor to Field's runtime.** The box *is* the REPL.
- **No `add-libs`.** The classpath is fixed at process start.
- **No compile-time diagnostics.** See the note under "When something throws".
- **`stop` does not interrupt an evaluation**, only a launch.

`dev_docs/small_language_anatomy.md` is the map of the integration points a language in Field has to
touch, and Clojure is one of its rows.
