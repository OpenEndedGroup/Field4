# Clojure in a box

*A Clojure box runs Clojure in the same document, and the same process, as the Kotlin boxes around
it. It has outputs, can be launched and stopped, and can reach everything in Field.*

Field runs **Clojure 1.11.3** inside its own JVM. A Clojure box can hold a reference to the `Box` it
is running in, hand an `FLine` to Field's renderer, and call anything in Field's standard library
directly, with nothing to convert and no second runtime to start.

## A Clojure box is a child of a Clojure root

A box becomes Clojure by being childed to a Clojure root. Select a box and run
*Set Main Language: Clojure* from the command palette (`set-main-language-clojure`). Every box
childed to it from then on runs as Clojure. This works the same way it does for
[Python](python.md#a-python-box-is-a-child-of-a-python-root):

- **The root itself is not Clojure.** Only its children are. Clojure typed into the root box is
  compiled as Kotlin, and fails.
- **A box stays Clojure while it has any Clojure-root parent**, so a box childed to both the
  document root and a Clojure root runs as Clojure.

The setting is saved with the document, so the arrangement survives a reload.

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

A box is evaluated one top-level form at a time, so each line of output is placed beside the form
that was running when it was printed.

`print` without a newline is buffered until the next newline or the end of the run, so a bare
`(print …)` is attributed to whichever form was running when it finally flushed, which is usually
but not always its own.

## A box that prints nothing shows its last value

When a box prints nothing, the value of its last form appears beside it instead:

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

The value is printed the way Clojure prints it, so a vector appears as `[1 2 3]` rather than
`[1, 2, 3]`. As in a [Python cell](python.md#one-interpreter-one-namespace), it is not shown when
the box has already printed something, or when the value is `nil` (which is what `println` and
`(lines …)` return). A `def` returns the var it defined, so a box that only defines things shows
that var (`#'live.user/spiral`), as a REPL would.

## One namespace, every box

There is one Clojure runtime for the whole Field process, and every Clojure box in every open
document evaluates in one namespace, `live.user`. Two Clojure boxes are like two cells of one
notebook: a `def` in one box is visible in the next, and state persists between runs. It also
means a name you thought you had deleted is still there, and that two boxes can overwrite each
other's names.

`clojure.core` and Field's own `field.core` are referred into the namespace. Nothing else is added:
there is no per-box prelude and no implicit `(ns …)`.

## What a box can reach

Clojure's Java interop already reaches all of Field, and most of the time that is what to use:

```diffstatic {data-lang=clojure}
(.getName (box))                          ; box properties and topology
(.getChildren (box))
(doto (afield.graphics.FLine.)             ; Field's own types
  (.moveTo 0 0 0)
  (.lineTo 100 100 0))
```

Interop is awkward for two common Kotlin shapes: extension functions, which Clojure sees as static
methods on a class named after a file, and default arguments, which Clojure has to pass in full.
`field.core` wraps the ones you need most, and is already referred into every box:

<div class="field-reference" data-code-lang="clojure" markdown>

| form | what it does |
| --- | --- |
| `(box)` | Returns the `Box` this code is running in. |
| `(display v)`, `(display channel v)` | Renders a value in an output panel using Field’s value renderer. Supply a channel name to update that output independently. |
| `(lines ls :w 700 :h 350 :fit true)` | Draws the supplied `FLine` collection (or a single `FLine`) in a 700 × 350 output panel named `lines`. `:fit true` fits the drawing to the view; `:channel "name"` draws into a different panel. |
| `(polyline [[x y] …])` | Builds an `FLine` through the points in sequence. |
| `(color line r g b a)`, `(color line [r g b a])`, `(thickness line w)` | Sets line colour (RGBA, 0 to 1) or stroke thickness in pixels and returns the line, so `->` threads. |
| `(animate f)` | Builds a per-frame animation around `f`. Return the animation from the box to launch it. |
| `(frame)`, `(end)` | Inside an animation body: reads the current frame number, or stops now. |
| `(prop "name")`, `(prop! "name" v)` | Reads or writes a property of this box. `prop!` returns the value it wrote. |
| `(prop b "name")`, `(prop! b "name" v)` | The same, for another box `b`, such as one from `(.getParents (box))`. |

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
`clojure.pprint`, `clojure.edn`, `clojure.data.json`, `clojure.tools.reader`), so `require` works as
usual. You cannot add a new dependency from inside a box: the classpath is fixed when Field starts.

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

`(.getNodes spiral)` works because `polyline` returns a real `FLine`, with all of its own methods.
Everything on
[Drawing with FLines](fline_examples.md) is reachable from a Clojure box by interop; `field.core`
only covers the awkward corners.

## Launching an animation

A Clojure box animates the way a Kotlin box does: returning an animation from the box starts it.
`(animate f)` builds one that calls `f` once per frame. So `(animate …)` has to be the last form,
and you launch the box rather than run it. See
[Frames and the Animation Loop](animation_time_model.md).

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

The function runs every frame; `(def n 160)` ran once, when the box was launched.

**Stop** ends an animation. It does not interrupt a form that is still evaluating, so a box stuck
in an infinite `loop` cannot be stopped, just as a Kotlin box in a `while (true)` that never
suspends cannot.

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

The error sits on the line of the form that threw. The forms above it have already run and printed,
and the forms below it do not run.

Anything a box writes to `*err*` appears the same way, as an error against the line that wrote it.
That is how Clojure's own warnings reach you, such as the one for shadowing a `clojure.core` name
with a `def`.

A syntax error, such as an unclosed parenthesis, is reported with its line and column before
anything is evaluated, so none of the box runs. Other mistakes (an unresolvable symbol, a macro that
throws, a call with the wrong number of arguments) show up only when the form runs, after the forms
above it have run. A Clojure box therefore has no equivalent of the red underline a Kotlin box gets
before it runs. The message names the problem first and the place second, for example
`Unable to resolve symbol: no-such-function in this context — Syntax error compiling at (3:1).`:

<!--
FIELD_TEST
id: docs.clojure.unresolved
runner: backdoor-box
tags: [docs, clojure, inline-output]
timeoutSec: 120
waitSec: 1.5
expect:
  allowExecutionErrors: true
  outputContains:
    - "before"
    - "Unable to resolve symbol: no-such-function"
-->
```diffstatic_runner {data-lang=clojure screenshot="assets/clojure/unresolved.png" capture="editor" outputs="inline" expect-error=true wait="4" padding="10" timeout="240" embed=true name="clojure-unresolved" w="900" h="220" alt="A three-line Clojure box. Line 1 shows the chip before. Line 3 carries the error Unable to resolve symbol: no-such-function in this context, followed by where it was compiled."}
:::static
(println "before")

(no-such-function 3)
```

## The editor knows the language

Clojure code is highlighted, and the parentheses in the figures above are coloured by nesting depth,
as they are in every Field editor.

Completion comes from [Compliment](https://github.com/alexander-yakushev/compliment). It knows the
names in the box's namespace (even in a box that has never been run), shows each function's argument
vector and docstring, and offers local names in scope at the caret: a `let` binding, a `fn`
parameter or a `doseq` variable. The popup below was opened at the prefix `sp`:

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

Each row carries its argument vector, and the panel on the right shows the docstring. `spline` under
LOCAL is the `let` binding two lines up, and `spiral` under VALUES is a var an earlier box on this
page defined in the shared namespace.

If Compliment is not on the classpath, a Clojure box still runs and the popup is simply empty.

The editor also shows what each form evaluated to the last time the box ran: rest the cursor on a
form and the note beside it shows the value in bold with its type, how long ago that was, and
whether the form has changed since. The value is recorded as the box runs, not by evaluating the
form again, so an unrealised lazy sequence is named rather than printed. See
[Line notes](language_extensions.md#line-notes).

## What is not here

- **No per-box namespaces.** Every box shares `live.user`, as above.
- **No nREPL, and no connecting an editor to Field's runtime.** The box *is* the REPL.
- **No `add-libs`.** The classpath is fixed at process start.
- **No compile-time diagnostics.** See the note under "When something throws".
- **Stop does not interrupt an evaluation**, only an animation.

See also [Python in a Box](python.md) and [language extensions](language_extensions.md).
