# Python cells — a document you can open

Open `metadata.edn` in Field (**File ▸ Open**, or `./field.sh <path to this folder>/metadata.edn`).
The written-up version is [Python in a box](../../../../public/docs/python.md).

**This one has a prerequisite the other demos do not.** Field has to have found an interpreter with
JEP's native library in it *before it started*, or the four cells stay Kotlin and fail to compile.
The quickest way in:

```bash
python3 -m venv .venv
CFLAGS="-isysroot $(xcrun --show-sdk-path)" .venv/bin/python3 -m pip install 'jep==4.3.1'
.venv/bin/python3 -m pip install jedi numpy plotly
export FIELD_PYTHON_VENV="$PWD/.venv"        # then start Field
```

`jep` must match the jar Field ships (`libs/jep-4.3.1.jar`). The `-isysroot` is a macOS workaround
for a Homebrew Python pinned to a Command Line Tools SDK that is not installed; without it the build
stops at `fatal error: 'assert.h' file not found`. Cell 4 needs `plotly`, cell 1 needs `numpy`, and
completion inside a Python box needs `jedi`.

| box | what it does | what to notice |
| --- | --- | --- |
| **root** | carries `mainLanguage = python` | it is **not** itself Python. The toggle swaps the execution on a root's *children* and never on the root (`LanguageRuntimeSupport.kt:56-69`), so this box is still Kotlin and says so when you run it |
| **1 — print, inline and in a block** | `print` of a string, then of a 3×4 numpy array | where the output lands. One line becomes a chip beside the line that printed it; multi-line text gets a block of its own and keeps its columns, because `formatOutput` wraps it in a `<pre>` (`PythonInterface.kt:1367`) |
| **2 — the namespace is shared with cell 1** | reads `a`, which it never defined | there is one interpreter per Field *process* and one global namespace (`PythonInterface.kt:190`, `:1152`). Run cell 1 first, then this. Run this one twice and the counter goes up: state survives between runs |
| **3 — the JVM is importable** | `from java.lang import System`, and `PythonInterface.thisBox` | JEP is in-process, so the JVM is not somewhere else. This cell prints the JVM version and the name of the box it is running inside |
| **4 — fig.show() lands in the box** | an ordinary plotly figure | nothing in the cell is Field-specific. `webbrowser.open` is replaced at interpreter startup, so the page plotly wanted to open externally is embedded in this box's `browser` channel instead |

Cell 2 is the one to actually touch, and it is deliberately a child of **both** the document root and
the Python root — which is legal, and is the correction worth knowing: a box stays Python while it
still has *any* language-root parent (`LanguageRuntimeSupport.kt:79`). It does not have to be
disconnected from the document root, whatever older notes say.

The loose `*_code_.py` / `*_code_.kt` files are what Field loads; `metadata.edn` carries portable
inline copies of the same text, and `./gradlew checkAgentDemos` fails if the two ever disagree.

Verified on 2026-08-27 by loading this document headlessly and running boxes 1–3, which printed
`python 3.12.9`, the aligned array, `a is still here: (3, 4) 66`, and `JVM 25.0.2`. Box 4 is
exercised by the capture on the published page rather than here.
