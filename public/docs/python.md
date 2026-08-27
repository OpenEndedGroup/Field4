# Python in a box

*The implementation is one file — `src/jvmMain/kotlin/afield/python/PythonInterface.kt`. Python is a
[promoted main language](language_extensions.md), so a Python box is a box like any other: it has
outputs, it can be stopped, and it sits in the same document as your Kotlin.*

Field embeds CPython through [JEP](https://github.com/ninia/jep), in-process. That is the whole
design, and everything else on this page follows from it. There is no subprocess, no socket, no
kernel protocol. A Python cell and a Kotlin box are two objects in one JVM, which is why a Python
cell can hold a reference to the `Box` it is running in, and why `import numpy` costs what it costs
in any other CPython.

The price is that Python has to be *found* before Field starts. [Setting it
up](#setting-python-up) is the last section, and it is the part most likely to bite you first.

## A Python box is a child of a Python root

The sharp edge is the topology, and it is worth getting right before anything else. **The language
is conferred by a parent, not by the box itself.** You mark a box as a language root, and its
*children* become Python.

The action is on the right-click menu of a selected box: **Set Main Language: Python**. That sets
`mainLanguage` to `"python"` on the box you selected (`MainLanguageRegistry.kt:68-82`), and every box
childed to it from then on runs as Python.

Two consequences that are easy to get backwards:

- **The root itself is not Python.** `applyRuntimeToggle` walks `root.children` and swaps the
  execution on each child (`LanguageRuntimeSupport.kt:56-69`); it never touches the root. A root box
  with Python in it is still a Kotlin box, and will fail to compile.
- **A cell does not have to be disconnected from anything.** A box stays Python while it still has
  *any* language-root parent — `if (child.parents.any { isRoot(it) == true }) return@forEach`
  (`LanguageRuntimeSupport.kt:79`). A notebook cell childed to both the document root and a Python
  root is Python, and the toggle is order-independent. This changed in `b10a90e8b`; older notes
  describing exclusive parenting as a requirement are describing the previous behaviour.

`mainLanguage` is persisted with the document (`MainLanguageRegistry.kt:46`), so the arrangement
survives a reload. There is an older per-language flag, `Box.isPythonRoot`
(`PythonInterface.kt:37`), also persisted (`:156`); it is still honoured
(`:179`) and is what `setLegacyRoot` writes (`:163`), but `mainLanguage` is the one to reach for.

The first cell, run by the docs build in a real Field. The block below is the box's `code`; the
picture under it is that same box's editor, with the output the run actually produced. Notice where
the output lands: a single line is a chip beside the line that printed it, and anything multi-line
gets a block of its own underneath.

```diffstatic_runner {data-lang=python screenshot="assets/python/first_cell.png" capture="editor" wait="4" padding="10" w="900" h="400" timeout="240" requires="jep" embed=true name="python-first-cell" alt="A Python box printing its interpreter version and a three-by-four numpy array, the array rendered as an aligned monospace block."}
:::static
# A single-line print appears inline, beside the line that made it.
import platform
import numpy as np

print("python", platform.python_version())

# A multi-line one gets a block of its own, and keeps its columns.
a = np.arange(12).reshape(3, 4)
print(a)
```

The array keeps its columns. That is not the browser being clever — multi-line text is wrapped in a
`<pre>` on the way out, which is the whole reason a `DataFrame` or a 2-D array is legible here at
all. See [Output](#output).

## One interpreter, one namespace

There is exactly one interpreter for the whole Field process. `globalInterpreter`
(`PythonInterface.kt:190`) is created once, on the first call to `interpreter()`, and every Python
box in every open document shares it.

It is worth being blunt about what that implies, because it is the most surprising thing here:
**Python boxes share one global namespace.** A cell's code is executed with `globals()` as both its
globals *and* its locals (`PythonInterface.kt:1152`), so a name bound in one box is visible in every
other box. There is a per-box `_pythonLocals` dictionary (`:38`) and it is handed to the interpreter
as `_local_storage_` (`:1148`), but the exec call that would have used it is commented out directly
above the one that runs. Two Python boxes are two cells of one notebook, not two notebooks.

State therefore persists between runs, which is the point: import your libraries and load your data
in one cell, and iterate in another. It also means a name you thought you had deleted is still
there.

A cell that prints nothing displays the value of its last expression instead:

```diffstatic_runner {data-lang=python screenshot="assets/python/last_expression.png" capture="editor" wait="4" padding="10" w="900" h="300" timeout="240" requires="jep" embed=true name="python-last-expression" alt="A Python box whose final line is an expression, showing the resulting number beside it in the editor."}
:::static
# Nothing is printed, so the value of the last line is what you see.
import math

sum(math.sqrt(n) for n in range(1, 11))
```

Only if it printed nothing. The last-expression value is published under `if (!hasHadOutput)`
(`PythonInterface.kt:1186-1194`), so a cell that has already printed will not also echo its final
value — which is why the array cell above showed no trailing `None`.

**Stop** does not restart the interpreter. It cancels the asyncio tasks that this box started, and
only those: `cancel_tasks('<box id>', timeout=1.5)` (`PythonInterface.kt:1263-1274`). Tasks are
tagged with the id of the box that created them, so stopping one cell leaves another cell's
coroutines running. `async def` works at all because Field installs a cooperative event loop at
interpreter startup (`CooperativeEventLoop`, `:285`) whose `execute` pump is driven from the Field
runloop rather than blocking it.

## Output

Everything printed funnels through one Kotlin function, `formatOutput` (`PythonInterface.kt:1367`).
Python's `print` is *replaced* at interpreter startup with `_field_print` (`:1014-1023`), which sends
text straight to the box's output channel rather than to stdout, so the formatting decision is made
in one place for both `print` and the last-expression value.

`formatOutput` does three things, in this order:

| step | what it does | why |
|---|---|---|
| `truncateOutput` (`:1379`) | clips to 40,000 characters and 400 lines, keeping head and tail with a marker between | enormous output used to be shipped whole to the browser and lock up the front end |
| `escapeOutputHtml` (`:1396`) | escapes `&`, `<`, `>` | the output pane is HTML |
| the `<pre>` wrap (`:1371`) | multi-line text only, into `<pre class="afield-python-output">` | a 2-D array or a `DataFrame` stays column-aligned instead of being flattened into `<br>`s |

**Carriage returns become a progress bar.** The stdout redirect is a framer that splits on `\n` *and*
`\r` (`OutputFramer`, `:52-85`). A frame ending in `\r` is an in-place update — the signature of
`tqdm` — and is routed to `deliverProgressText` (`:90-105`) instead of the output pane. If the frame
parses as a tqdm bar it becomes a real Field progress bar with that percentage; if it does not, it
becomes an indeterminate bar carrying the raw frame as its label. This is why a `tqdm` loop surfaces
live instead of arriving as one enormous string at the end.

No capture of a progress bar appears on this page. A progress bar is transient by construction, and
a still taken after the cell finishes shows an empty bar — an honest screenshot of it would have to
be a video timed to catch the middle of a loop, which the still-capture path cannot do.

### A figure that opens a browser gets embedded instead

Plotting libraries end `fig.show()` by asking the operating system to open a web browser. Field
intercepts that. `webbrowser.open`, `open_new`, `open_new_tab` and the controller returned by
`webbrowser.get` are all replaced at startup (`PythonInterface.kt:1100-1104`), and the page is
embedded in the box's output as a self-contained `srcdoc` iframe on a channel called `browser`
(`:1037`).

The interesting case is a library that starts a one-shot local HTTP server and then blocks until
someone fetches the URL — which is what plotly's default renderer does. Field fetches it itself on a
daemon thread (`:1076-1085`): the GET satisfies the waiting server so the cell never hangs, and the
document that comes back is what gets embedded. The cell does not depend on a front end being
present at all, which is precisely why the capture below can exist.

```diffstatic_runner {data-lang=python screenshot="assets/python/plotly_embed.png" capture="box" open-outputs="true" wait-output="true" wait-output-kind="display" wait-output-channel="browser" wait="6" padding="20" w="1280" h="420" timeout="300" requires="jep" embed=true name="python-plotly-embed" alt="A Python box whose plotly figure is rendered inside the box's own output pane as an embedded dark-themed line chart of a decaying sine wave."}
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

Because JEP is in-process, the JVM is not somewhere else — it is importable. `java.lang`, and any
class on Field's classpath, resolves as a Python module.

```diffstatic_runner {data-lang=python screenshot="assets/python/jvm_interop.png" capture="editor" wait="4" padding="10" w="900" h="340" timeout="240" requires="jep" embed=true name="python-jvm-interop" alt="A Python box importing java.lang.System and Field's own PythonInterface, printing the JVM version and the name of the box it is running inside."}
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

`PythonInterface.thisBox` (`PythonInterface.kt:1412`) is the `Box` currently executing — set before
each fragment runs (`:1149`) — so a Python cell can reach its own box and, through it, the rest of
the document. Field's own startup code uses exactly this handle to publish output
(`box.getOutput().getProxy()(...)`, `:1020`) and to open the embed channel (`:1042-1043`).

The reverse direction has no bridge. There is no Kotlin-side helper for evaluating a string of
Python or reading a Python variable out of a box; Kotlin talks to Python by *being* the host, and the
traffic on this page all runs Python → JVM. Whether a Kotlin-callable evaluation helper would be
worth adding is a design question, not a limitation anyone has hit and documented — treat that as a
conjecture rather than a claim.

Completion inside a Python box is served by [jedi](https://jedi.readthedocs.io/), not by Field's
Kotlin machinery: `complete` builds a `jedi.Interpreter` over the box's source and the live
`locals()` (`PythonInterface.kt:1277-1300`). Because the interpreter is live, completion sees objects
you actually created rather than only what it can infer statically — and because it is jedi, `pip
install jedi` is what turns it on.

## Setting Python up { #setting-python-up }

Field needs an interpreter that has **JEP's native library** in it. Not merely a Python — a Python
where `pip install jep` has been run, so that a `libjep` file exists in its site-packages.

The candidate list is tried in this order, and the first one with a `libjep` in it wins
(`PythonInterface.kt:1685-1701`):

| # | candidate |
|---|---|
| 1 | `-Dafield.python.venv=…` / `-Dafield.python.executable=…` |
| 2 | `FIELD_PYTHON_VENV` / `FIELD_PYTHON_EXECUTABLE` |
| 3 | whatever `/welcome` last saved, in `~/.afield/python.properties` |
| 4 | `VIRTUAL_ENV`, then `CONDA_PREFIX` |
| 5 | `python3`, then `python`, on `PATH` |

The `/welcome` panel has an **Install JEP** button, and it is worth knowing what it actually does
(`installJep`, `PythonInterface.kt:1552-1607`): it runs `python -m ensurepip --upgrade`, then `python
-m pip install --upgrade jep`, with `JAVA_HOME` set to the running JVM's own `java.home` — jep is a
JNI extension and its build needs `jni.h`. It then re-resolves, and only persists your choice if a
`libjep` really did appear. The pip step is given six minutes, because it compiles C.

Three things that go wrong, all observed on macOS while writing this page:

- **`import jep` is the wrong way to check.** A *correctly* installed JEP raises `ImportError: Jep is
  not supported in standalone Python, it must be embedded in Java.` The thing to look for is the
  `libjep` file, which is also what Field looks for (`:1771`).
- **Binding happens once, at startup.** Saving new settings returns `restartRequired`
  (`PythonInterface.kt:1540`) and the status line says so. Until you restart Field, `mainLanguage =
  python` is inert and your cells stay Kotlin, because the runtime is only registered when Python
  resolved at boot (`installPython`, `:157`).
- **A stale `typing` backport breaks startup.** An Anaconda base environment carrying the PyPI
  `typing` package (a Python 3.4-era backport, obsolete since 3.5) fails interpreter startup with
  `AttributeError: type object 'Callable' has no attribute '_abc_registry'`. A clean `python -m venv`
  avoids it. Field reports this as "found but failed to initialize" and names the interpreter it
  tried (`markPythonUnavailable`, `:1506`).

On macOS, if Xcode is selected but Homebrew's Python was built against a Command Line Tools SDK that
is no longer installed, the jep build fails with `fatal error: 'assert.h' file not found`. Building
with an explicit sysroot fixes it:

```diffstatic {data-lang=bash}
python3 -m venv .venv
CFLAGS="-isysroot $(xcrun --show-sdk-path)" .venv/bin/python3 -m pip install 'jep==4.3.1'
.venv/bin/python3 -m pip install jedi numpy      # completion, and something to print

# then point Field at it, and restart
export FIELD_PYTHON_VENV="$PWD/.venv"
```

Pin `jep` to the version of the jar Field ships — `libs/jep-4.3.1.jar` (`build.gradle.kts:918`).
A native library and a jar from different releases is a mismatch JEP does not check for you.

## Which code on this page runs { #which-code-on-this-page-runs }

The four Python blocks are executed by the docs build, in a real Field, as real Python boxes: a root
box with `mainLanguage = python`, and the block's text set as a child box's `code`. Nothing on this
page is a hand-written transcript of a session.

Three of the pictures are the box's *editor*, because that is where `print` output and a last
expression land — beside the line that produced them. The plotly one is the box's output pane,
because an embedded page is a display channel rather than an inline annotation.

They need JEP, so each carries `requires="jep"` and a docs build without a resolvable JEP stops in a
second rather than failing at the capture. The one shell block is illustration — it is the install
recipe, and running it during a docs build would compile a C extension.

Two claims on this page are deliberately *not* backed by a picture, and both are called out where
they appear: the tqdm progress bar, because a still cannot honestly show a transient bar; and the
shared global namespace, because demonstrating it needs two co-operating boxes and the capture path
builds one.
