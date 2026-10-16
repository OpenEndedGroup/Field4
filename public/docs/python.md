# Python in a box

A Python box sits in the same document as your Kotlin boxes, has outputs, and can be stopped, like
any other box. Python is one of Field's [main languages](language_extensions.md). Field runs CPython
inside its own process, through [JEP](https://github.com/ninia/jep), so a Python cell can reach the
box it is running in, and `import numpy` behaves as it does anywhere else.

Python needs an interpreter with JEP installed. If your cells keep running as Kotlin, see
[Setting Python up](#setting-python-up) at the end of the page.

## A Python box is a child of a Python root

A box gets its language from its parent. You mark one box as a Python root, and its *children* run
as Python.

Select a box, right-click, and choose **Set Main Language: Python**. Every box you connect under it
from then on runs as Python. The setting is saved with the document.

Two things follow:

- **The root itself is not Python.** Python code typed into the root box is compiled as Kotlin, and
  fails.
- **A cell can have other parents.** A box runs as Python as long as *any* of its parents is a Python
  root, so a cell connected under both the document root and a Python root is Python.

The code below is a Python box's `code`, and the picture under it is that box's editor. A single
line of output appears beside the line that printed it; anything longer gets a block of its own
underneath.

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

Multi-line output keeps its columns, so a `DataFrame` or a 2-D array stays legible. See
[Output](#output).

## One interpreter, one namespace

Field runs one Python interpreter, shared by every Python box in every open document. A name bound
in one box is visible in every other box: two Python boxes are two cells of one notebook.

State persists between runs, as in any notebook: import your libraries and load your data in one
cell, and iterate in another. A name you bound earlier stays bound until you `del` it or restart
Field.

Try it yourself: make a Python root, put two cells under it, bind a name in the first, and read it
in the second.

A cell that prints nothing displays the value of its last expression instead:

```diffstatic_runner {data-lang=python screenshot="assets/python/last_expression.png" capture="editor" outputs="inline" wait="4" padding="10" w="900" h="300" timeout="240" requires="jep" embed=true name="python-last-expression" alt="A Python box whose final line is an expression, showing the resulting number beside it in the editor."}
:::static
# Nothing is printed, so the value of the last line is what you see.
import math

sum(math.sqrt(n) for n in range(1, 11))
```

A cell that has printed something does not also show its last value, which is why the array cell
above shows no trailing `None`.

**Stop** does not restart the interpreter. It cancels the asyncio tasks this box started, and only
those, so stopping one cell leaves another cell's coroutines running. `async def` and `await` work
in a cell; Field drives the event loop from its own frame loop.

## Output

`print` and a cell's last value both go to the box's output, not to the terminal. Very long output
is clipped to its first and last lines, with a marker between, so a huge print cannot lock up the
browser. Multi-line text is shown in a monospace block, so arrays and tables keep their columns.

**A `tqdm` loop becomes a progress bar.** A line that ends in a carriage return (`\r`) rather than a
newline is treated as an in-place update. If it looks like a `tqdm` bar, Field shows a progress bar
with that percentage; otherwise it shows a bar labelled with the text. Either way you see progress
while the loop runs, not one long string at the end.

### A figure that opens a browser gets embedded instead

Plotting libraries end `fig.show()` by asking the operating system to open a web browser. In Field,
the page is embedded in the box's output instead, on a channel called `browser`. This works for
libraries that open a file and for ones, like plotly, that start a small local web server and wait
for a browser to fetch the page.

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

Nothing in that cell is Field-specific; it is the plotly you would write anywhere.

## Python and Kotlin in one document

A Python cell can import Java and Kotlin classes: `java.lang`, and any class Field itself uses, can be
imported as a Python module. The cell below prints the Java version and the name of its own box.

```diffstatic_runner {data-lang=python screenshot="assets/python/jvm_interop.png" capture="editor" outputs="inline" wait="4" padding="10" w="900" h="340" timeout="240" requires="jep" embed=true name="python-jvm-interop" alt="A Python box importing java.lang.System and Field's own PythonInterface, printing the JVM version and the name of the box it is running inside."}
:::static
# java.lang, and anything else on Field's classpath, is importable.
from java.lang import System
from afield.lang.python import PythonInterface

box = PythonInterface.thisBox          # the Box this cell is running in

print("\n".join([
    f"JVM       {System.getProperty('java.version')}",
    f"this box  {box.getName()}",
]))
```

`PythonInterface.thisBox` is the box the cell is running in, so a Python cell can reach its own box
and, through it, the rest of the document.

The bridge runs one way. Kotlin has no helper for running a string of Python or reading a Python
variable out of a box.

Completion inside a Python box comes from [jedi](https://jedi.readthedocs.io/). It looks at the
objects your cells have actually created, not only at the source. Run `pip install jedi` in Field's
interpreter to turn it on.

## A notebook is a column of boxes

Drag a `.ipynb` onto the canvas and choose *Import as Python cells*. Field makes a box named
`python root` and connects a Python cell under it for each code cell; each Markdown cell becomes a
`notes` box beside the column, so the prose reads inline. The cells stay connected to the document
root too, which is fine: a cell is Python while
[any of its parents](#a-python-box-is-a-child-of-a-python-root) is a Python root.

To go the other way, select the python root and choose *Export notebook (.ipynb)*. It writes the
column top to bottom as a standard notebook. Import also reads older notebooks (nbformat 3).

[funFeatures](funFeatures.md#python) has the round-trip in context.

## What each line came to

Rest the cursor on a statement and the note beside it says what that statement came to the last time
the cell ran: an expression's value, the value a name was assigned, the function or class it
defined, or simply that it ran. The value is in bold. Here the cell is

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
since the run says so. Only simple values are printed (numbers, short strings, small lists and
dicts of them); anything else, such as a DataFrame or a tensor, is named by its type. To turn the recording off, start Field with `AFIELD_PYTHON_LINE_NOTES=0`. See
[Line notes](language_extensions.md#line-notes).

## Setting Python up { #setting-python-up }

Field needs a Python where `pip install jep` has been run, so that JEP's native library (a `libjep`
file) is in its site-packages.

Field looks for an interpreter in this order, and uses the first one that has `libjep`:

1. `-Dafield.python.venv=…` / `-Dafield.python.executable=…`
2. `FIELD_PYTHON_VENV` / `FIELD_PYTHON_EXECUTABLE`
3. `VIRTUAL_ENV`, then `CONDA_PREFIX`, so an environment active in the shell that started Field wins
4. whatever `/welcome` last saved, in `~/.afield/python.properties`
5. `python3`, then `python`, on `PATH`

The `/welcome` panel has an **Install JEP** button. It installs pip if needed, then runs
`pip install jep` against Field's own Java, since JEP compiles C code that needs it. The install can
take a few minutes. Your choice of interpreter is saved only if the install worked.

Three things commonly go wrong:

- **`import jep` is the wrong way to check.** A correctly installed JEP raises `ImportError: Jep is
  not supported in standalone Python, it must be embedded in Java.` Look for the `libjep` file
  instead.
- **Field picks its Python at startup.** After changing the settings, restart Field; until then your
  cells keep running as Kotlin. The status line says when a restart is needed.
- **An old `typing` package breaks startup.** An Anaconda base environment with the PyPI `typing`
  package installed fails with `AttributeError: type object 'Callable' has no attribute
  '_abc_registry'`. Use a clean `python -m venv` instead. Field reports this as "found but failed to
  initialize" and names the interpreter it tried.

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

Install the `jep` version that matches the jar Field ships (`libs/jep-4.3.1.jar`). JEP does not
check that its native library and jar come from the same release.

??? aside "Agents: how this page's pictures are made"

    The docs build runs each Python block in a real Field, as a child of a Python root. Each declares
    `jep` (and `python:numpy` / `python:plotly.graph_objects`) in `requires`; a recapture without them
    stops before Field starts, and a cached build keeps the committed pictures. The shell block is
    illustration only. The `tqdm` progress bar and the shared namespace have no picture: a still
    after the loop shows an empty bar, and a runner fence builds only one box.
    `dev_docs/DOCS_PAGE_SNIPPET_COVERAGE.md` has the mechanics.
