# Python in a box

*The implementation is one file — `src/jvmMain/kotlin/afield/python/PythonInterface.kt`. Python is a
[promoted main language](language_extensions.md), which means a Python box has outputs, can be
stopped, and sits in the same document as your Kotlin, exactly like the Kotlin boxes around it.*

Field embeds CPython through [JEP](https://github.com/ninia/jep), in-process. A Python cell and a
Kotlin box are two objects in one JVM, which is why a cell can hold a reference to the `Box` it is
running in, and why `import numpy` costs what it costs in any other CPython. The cost of that is
paid at startup, where Field has to find an interpreter with JEP's native library already installed
in it. If your cells stay stubbornly Kotlin, [Setting Python up](#setting-python-up) at the end of
the page is where to go.

## A Python box is a child of a Python root

Start with the topology. **The language is conferred by a parent, not by the box itself.** You mark
a box as a language root, and its *children* become Python.

The action is on the right-click menu of a selected box: **Set Main Language: Python**. That sets
`mainLanguage` to `"python"` on the box you selected (`setMainLanguageFor` in
`MainLanguageRegistry.kt`), and every box childed to it from then on runs as Python.

Two consequences that are easy to get backwards:

- **The root itself is not Python.** `applyRuntimeToggle` (`LanguageRuntimeSupport.kt`) walks
  `root.children` and swaps the execution on each child; it never touches the root. A root box with
  Python in it is still a Kotlin box, and will fail to compile.
- **A cell does not have to be disconnected from anything.** A box stays Python while it still has
  *any* language-root parent: `if (child.parents.any { isRoot(it) == true }) return@forEach`. A
  notebook cell childed to both the document root and a Python root is Python, and the toggle is
  order-independent.

`mainLanguage` is persisted with the document (`installMainLanguageProperty`), so the arrangement
survives a reload. An older per-language flag, `Box.isPythonRoot`, is persisted alongside it and
still honoured: `pythonTopologyNeedsUpdating` counts a box as a root if either is set, and
`setLegacyRoot` keeps writing it. `mainLanguage` is the one to reach for.

The block below is a Python box's `code`, and the picture under it is that box's editor. Notice
where the output lands: a single line is a chip beside the line that printed it, and anything
multi-line gets a block of its own underneath.

```diffstatic_runner {data-lang=python screenshot="assets/python/first_cell.png" capture="editor" outputs="inline" wait="4" padding="10" w="900" h="400" timeout="240" requires="jep,python:numpy" embed=true name="python-first-cell" alt="A Python box printing its interpreter version and a three-by-four numpy array, the array rendered as an aligned monospace block."}
:::static
# A single-line print appears inline, beside the line that made it.
import platform
import numpy as np

print("python", platform.python_version())

# A multi-line one gets a block of its own, and keeps its columns.
a = np.arange(12).reshape(3, 4)
print(a)
```

The array keeps its columns because multi-line text is wrapped in a `<pre>` on the way out, and that
wrap is what makes a `DataFrame` or a 2-D array legible here at all. See [Output](#output).

## One interpreter, one namespace

There is exactly one interpreter for the whole Field process. `globalInterpreter` is created once,
on the first call to `interpreter()`, and every Python box in every open document shares it.

So **Python boxes share one global namespace.** A cell's code is executed with `globals()` as both
its globals *and* its locals (`executeFragment`), so a name bound in one box is visible in every
other box. There is a per-box `_pythonLocals` dictionary, handed to the interpreter as
`_local_storage_`, but the exec call that would have used it is commented out directly above the one
that runs. Two Python boxes are two cells of one notebook, not two notebooks.

State therefore persists between runs, which is what a notebook is for: import your libraries and
load your data in one cell, and iterate in another. It also means a name you thought you had deleted is still
there.

Try it yourself: make a Python root, put two cells under it, bind a name in the first, and read it
in the second.

A cell that prints nothing displays the value of its last expression instead:

```diffstatic_runner {data-lang=python screenshot="assets/python/last_expression.png" capture="editor" outputs="inline" wait="4" padding="10" w="900" h="300" timeout="240" requires="jep" embed=true name="python-last-expression" alt="A Python box whose final line is an expression, showing the resulting number beside it in the editor."}
:::static
# Nothing is printed, so the value of the last line is what you see.
import math

sum(math.sqrt(n) for n in range(1, 11))
```

The echo is conditional on having printed nothing at all: the last-expression value is published
under `if (!hasHadOutput)` in `executeFragment`, so a cell that has already printed will not also
echo its final value. That is why the array cell above showed no trailing `None`.

**Stop** does not restart the interpreter. It cancels the asyncio tasks that this box started, and
only those: `stop` runs `cancel_tasks('<box id>', timeout=1.5)`. Tasks are tagged with the id of the
box that created them, so stopping one cell leaves another cell's coroutines running. `async def`
works at all because Field installs a cooperative event loop at interpreter startup
(`CooperativeEventLoop`) whose `execute` pump is driven from the Field runloop rather than blocking
it.

## Output

Everything printed funnels through one Kotlin function, `formatOutput`. Python's `print` is
*replaced* at interpreter startup with `_field_print`, which sends text straight to the box's output
channel rather than to stdout, so the formatting decision is made in one place for both `print` and
the last-expression value.

`formatOutput` does three things, in this order:

| step | what it does | why |
|---|---|---|
| `truncateOutput` | clips to 40,000 characters and 400 lines, keeping head and tail with a marker between | shipping a huge output whole to the browser locks up the front end |
| `escapeOutputHtml` | escapes `&`, `<`, `>` | the output pane is HTML |
| the `<pre>` wrap | multi-line text only, into `<pre class="afield-python-output afield-appendable-output">` | a 2-D array or a `DataFrame` stays column-aligned instead of being flattened into `<br>`s |

**Carriage returns become a progress bar.** The stdout redirect is a framer (`OutputFramer`) that
splits on `\n` *and* `\r`. A frame ending in `\r` is an in-place update — the signature of
`tqdm` — and is routed to `deliverProgressText` instead of the output pane. If the frame parses as
a tqdm bar it becomes a real Field progress bar with that percentage; if it does not, it becomes an
indeterminate bar carrying the raw frame as its label. This is why a `tqdm` loop surfaces live
instead of arriving as one enormous string at the end.

### A figure that opens a browser gets embedded instead

Plotting libraries end `fig.show()` by asking the operating system to open a web browser. Field
intercepts that. `webbrowser.open`, `open_new`, `open_new_tab` and the controller returned by
`webbrowser.get` are all replaced at interpreter startup, and the page is embedded in the box's
output as a self-contained `srcdoc` iframe on a channel called `browser` (`_FIELD_EMBED_CHANNEL`).

The hard case is a library that starts a one-shot local HTTP server and then blocks until
someone fetches the URL — which is what plotly's default renderer does. `_field_open_url` fetches it
itself on a daemon thread: the GET satisfies the waiting server so the cell never hangs, and the
document that comes back is what gets embedded. No front end has to be present for any of that to
happen.

```diffstatic_runner {data-lang=python screenshot="assets/python/plotly_embed.png" capture="box" open-outputs="true" wait-output="true" wait-output-kind="display" wait-output-channel="browser" wait="6" padding="20" w="1280" h="420" timeout="300" requires="jep,python:numpy,python:plotly.graph_objects" embed=true name="python-plotly-embed" alt="A Python box whose plotly figure is rendered inside the box's own output pane as an embedded dark-themed line chart of a decaying sine wave."}
:::static
import numpy as np
import plotly.graph_objects as go

t = np.linspace(0, 4 * np.pi, 400)
fig = go.Figure(go.Scatter(x=t, y=np.sin(t) * np.exp(-t / 10), mode="lines"))
fig.update_layout(
    template="plotly_dark",
    margin=dict(l=55, r=20, t=25, b=40),
)
fig.show()
```

Nothing in that cell is Field-specific. It is the plotly you would write anywhere, and the
redirection happens underneath it.

## Python and Kotlin in one document

Because JEP is in-process, the JVM is importable. `java.lang`, and any class on Field's classpath,
resolves as a Python module.

```diffstatic_runner {data-lang=python screenshot="assets/python/jvm_interop.png" capture="editor" outputs="inline" wait="4" padding="10" w="900" h="340" timeout="240" requires="jep" embed=true name="python-jvm-interop" alt="A Python box importing java.lang.System and Field's own PythonInterface, printing the JVM version and the name of the box it is running inside."}
:::static
# java.lang, and anything else on Field's classpath, is importable.
from java.lang import System
from afield.python import PythonInterface

box = PythonInterface.thisBox          # the Box this cell is running in

print("\n".join([
    f"JVM       {System.getProperty('java.version')}",
    f"this box  {box.getName()}",
]))
```

`PythonInterface.thisBox` is the `Box` currently executing, set before each fragment runs, so a
Python cell can reach its own box and, through it, the rest of the document. Field's own startup
code uses the same handle to publish output through `box.getOutput().getProxy()` and to open the
embed channel.

The reverse direction has no bridge. There is no Kotlin-side helper for evaluating a string of
Python or reading a Python variable out of a box: Kotlin talks to Python by *being* the host, and
all the traffic on this page runs Python → JVM.

Completion inside a Python box is served by [jedi](https://jedi.readthedocs.io/), not by Field's
Kotlin machinery. `complete` builds a `jedi.Interpreter` over the box's source and the live
`locals()`, so completion sees the objects you created rather than only what it can infer
statically. Because it is jedi, `pip install jedi` is what turns it on.

## A notebook is a column of boxes

Dragging a `.ipynb` onto the canvas and choosing *Import as Python cells* builds the topology this
page opened with, automatically. `importNotebookCells` (`importing.kt`) creates one box named
`python root` with `isPythonRoot = true`, then walks the notebook: a `code` cell becomes a Python
cell connected under that root, and a `markdown` or `raw` cell becomes a `notes` box indented beside
the column so the prose reads inline.

The imported cells stay children of the document root as well as the Python root, which is the
[parenting rule](#a-python-box-is-a-child-of-a-python-root) above doing its job: the toggle keeps a
box in-language while it has *any* language-root parent, so there is nothing to detach.

Going the other way, *Export notebook (.ipynb)* on a selected python-root box walks its column
top-to-bottom and writes nbformat 4 (`exportNotebookCells`). Reading is more forgiving than writing:
the importer also accepts nbformat 3, where the cells live under `worksheets[].cells` and a cell's
source is keyed `input` rather than `source`.

[funFeatures](funFeatures.md#python) has the round-trip in context.

## What each line came to

Rest the cursor on a statement and the note beside it says what that statement came to the last time
the cell ran — an expression's value, the value a name was assigned, the function or class it defined,
or that it ran — with the value in bold. Here the cell is

    xs = [n * n for n in range(6)]
    total = sum(xs)
    def mean(v):
        return sum(v) / len(v)
    mean(xs)

under a comment line, it has run once, and the cursor rests on `total = sum(xs)`:

```diffstatic_runner {data-lang=kotlin capture="ui" region="window" clip-selector=".hoverlay-left" screenshot="assets/python/line_note.png" transcript="assets/transcripts/line_notes_python_setup.yaml" capture-box="line_notes_python" padding="10" wait="0" timeout="240" embed=true hidden=true name="python-line-note" alt="The note beside the cursor, resting on line 3 of the cell above: total = 55 · int, with 55 in bold, over a muted line reading line 3 · last run, and how many seconds ago."}
// Rest on line 3 through the keyboard, as a person would: to the top, then down two lines.
ui.openEditor("line_notes_python")
ui.pause(700)
ui.waitForSelector(".monaco-editor", 15000.0)
ui.clickSelector(".monaco-editor .view-lines")
ui.pause(200)
repeat(4) { ui.press("PageUp") }
ui.press("Home")
repeat(2) { ui.press("ArrowDown") }
ui.waitForSelector(".hoverlay-left .afield-line-note-value", 20000.0)
ui.pause(800)
```

The values are recorded as the cell runs, so asking about a line never runs it again, and a line changed
since the run says so. Only builtin values are printed — numbers, short strings, small lists and dicts
of them; anything else is named by its type, so a note never calls a DataFrame's or a tensor's
`__repr__`. To turn the recording off, start Field with `AFIELD_PYTHON_LINE_NOTES=0`. See
[Line notes](language_extensions.md#line-notes).

## Setting Python up { #setting-python-up }

Field needs an interpreter that has **JEP's native library** in it. Not merely a Python — a Python
where `pip install jep` has been run, so that a `libjep` file exists in its site-packages.

`pythonConfigChoices` tries the candidates in this order, and the first one with a `libjep` in it
wins:

1. `-Dafield.python.venv=…` / `-Dafield.python.executable=…`
2. `FIELD_PYTHON_VENV` / `FIELD_PYTHON_EXECUTABLE`
3. whatever `/welcome` last saved, in `~/.afield/python.properties`
4. `VIRTUAL_ENV`, then `CONDA_PREFIX`
5. `python3`, then `python`, on `PATH`

The `/welcome` panel has an **Install JEP** button. `installJep` runs `python -m ensurepip
--upgrade`, then `python -m pip install --upgrade jep`, with `JAVA_HOME` set to the running JVM's
own `java.home`, because jep is a JNI extension whose build needs `jni.h`. It then re-resolves, and
only persists your choice if a `libjep` really did appear. The pip step is given six minutes,
because it compiles C.

??? aside "Three things that go wrong"

    **`import jep` is the wrong way to check.** A *correctly* installed JEP raises `ImportError: Jep
    is not supported in standalone Python, it must be embedded in Java.` The thing to look for is the
    `libjep` file, which is also what `inspectPython` looks for.

    **Binding happens once, at startup.** Saving new settings returns `restartRequired` and the
    status line says so. Until you restart Field, `mainLanguage = python` is inert and your cells
    stay Kotlin, because `installPython` only registers the runtime when Python resolved at boot.

    **A stale `typing` backport breaks startup.** An Anaconda base environment carrying the PyPI
    `typing` package (a Python 3.4-era backport, obsolete since 3.5) fails interpreter startup with
    `AttributeError: type object 'Callable' has no attribute '_abc_registry'`. A clean `python -m
    venv` avoids it. `markPythonUnavailable` reports this as "found but failed to initialize" and
    names the interpreter it tried.

On macOS, if Xcode is the selected toolchain but Homebrew's Python was built against a Command Line
Tools SDK that is missing from the machine, the jep build fails with `fatal error: 'assert.h' file
not found`. Building with an explicit sysroot fixes it:

```diffstatic {data-lang=bash}
python3 -m venv .venv
CFLAGS="-isysroot $(xcrun --show-sdk-path)" .venv/bin/python3 -m pip install 'jep==4.3.1'
.venv/bin/python3 -m pip install jedi numpy      # completion, and something to print

# then point Field at it, and restart
export FIELD_PYTHON_VENV="$PWD/.venv"
```

Pin `jep` to the version of the jar Field ships — `libs/jep-4.3.1.jar`, named in `build.gradle.kts`.
A native library and a jar from different releases is a mismatch JEP does not check for you.

## Which code on this page runs { #which-code-on-this-page-runs }

The four Python blocks are executed by the docs build, in a real Field, as real Python boxes: a root
box with `mainLanguage = python`, and the block's text set as a child box's `code`.

Three of the pictures are the box's *editor*, because that is where `print` output and a last
expression land — beside the line that produced them. The plotly one is the box's output pane,
because an embedded page is a display channel rather than an inline annotation.

All four need JEP, so each declares `jep` in `requires`; the numpy and Plotly examples also declare
`python:numpy` and `python:plotly.graph_objects`. Preflight imports those modules in the JEP-capable
interpreter Field resolves, which is not the docs builder's own Python and usually not the same set
of packages. A recapture missing a prerequisite stops before Field starts rather than failing at the
capture itself; a cached build warns and carries on with the committed pictures.

To aim a docs build at a particular interpreter, set `FIELD_PYTHON_VENV` or
`FIELD_PYTHON_EXECUTABLE`. Activating a venv is not enough on its own: in
`PythonInterface.pythonConfigChoices` those two rank above the interpreter the welcome screen
persisted, and `VIRTUAL_ENV` and `CONDA_PREFIX` rank below it.

The one shell block is the install recipe and is illustration only — running it during a docs build
would compile a C extension.

Two claims here have no picture under them. A still taken after a `tqdm` loop finishes shows an
empty bar, and the shared global namespace needs two co-operating boxes where a runner fence builds
only one; both are things to run yourself rather than read.
`dev_docs/DOCS_PAGE_SNIPPET_COVERAGE.md` has the capture mechanics behind all of it.
