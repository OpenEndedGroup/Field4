# Field Document: python_cells

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 5 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "fileManagerAccessHistory" "mainLanguage"]}
```

## 1. Python root -- children of this box run as Python

<!-- field-box id="python_root" index="0" -->

### Narrative

From `code`

This box is the language ROOT, and is itself still Kotlin -- the toggle swaps the
execution on a root's CHILDREN, never on the root (LanguageRuntimeSupport.kt:56-69).
Its `mainLanguage` property is what makes the four boxes below Python.

```edn [field-box id="python_root" role="properties"]
{"id" "python_root" "index" 0 "name" "Python root -- children of this box run as Python" "parents" ["root"] "children" ["python_hello" "python_shared" "python_interop" "python_plotly"] "properties" {"children" #{#afield/box "python_hello" #afield/box "python_shared" #afield/box "python_interop" #afield/box "python_plotly"} "parents" #{#afield/box "root"} "name" "Python root -- children of this box run as Python" "_tombstone" false "frame" #afield/rect [60.0 40.0 340.0 100.0] "mainLanguage" "python" "isPythonRoot" true} "blockProperties" [{"name" "code" "language" "kotlin" "path" "python_root_code_.kt" "lines" 4 "characters" 280 "sha256" "ed659e63937a2e980dbf36b678538f53de10b48d3145947cdd76d1aca9242532"}]}
```

```kotlin [field-property box="python_root" name="code" path="python_root_code_.kt"]
// This box is the language ROOT, and is itself still Kotlin -- the toggle swaps the
// execution on a root's CHILDREN, never on the root (LanguageRuntimeSupport.kt:56-69).
// Its `mainLanguage` property is what makes the four boxes below Python.
println("the root stays Kotlin")
```

## 2. fig.show() lands in the box

<!-- field-box id="python_plotly" index="1" -->

### Narrative

From `code`

plotly's default renderer starts a one-shot local server and asks the OS to open a
browser. Field intercepts that and embeds the page in this box's "browser" channel.

```edn [field-box id="python_plotly" role="properties"]
{"id" "python_plotly" "index" 1 "name" "fig.show() lands in the box" "parents" ["root" "python_root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "python_root"} "name" "fig.show() lands in the box" "_tombstone" false "frame" #afield/rect [60.0 4040.0 340.0 100.0] "code_language" "Python"} "blockProperties" [{"name" "code" "language" "python" "path" "python_plotly_code_.kt" "lines" 9 "characters" 428 "sha256" "54b0f12ee381ddd02f0f47c9d4cdd943ece4a40a93e4a8e317541a97a895b237"}]}
```

```python [field-property box="python_plotly" name="code" path="python_plotly_code_.kt"]
# plotly's default renderer starts a one-shot local server and asks the OS to open a
# browser. Field intercepts that and embeds the page in this box's "browser" channel.
import numpy as np
import plotly.graph_objects as go

t = np.linspace(0, 4 * np.pi, 400)
fig = go.Figure(go.Scatter(x=t, y=np.sin(t) * np.exp(-t / 10), mode="lines"))
fig.update_layout(template="plotly_dark", margin=dict(l=55, r=20, t=25, b=40))
fig.show()
```

## 3. print, inline and in a block

<!-- field-box id="python_hello" index="2" -->

### Narrative

From `code`

A single-line print appears inline, beside the line that made it.

```edn [field-box id="python_hello" role="properties"]
{"id" "python_hello" "index" 2 "name" "print, inline and in a block" "parents" ["root" "python_root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "python_root"} "name" "print, inline and in a block" "_tombstone" false "frame" #afield/rect [60.0 1040.0 340.0 100.0] "code_language" "Python"} "blockProperties" [{"name" "code" "language" "python" "path" "python_hello_code_.kt" "lines" 9 "characters" 256 "sha256" "8c0f2ee8c19aae67337d034d1e7bc8325a1c9129b95025d914f51f089d747578"}]}
```

```python [field-property box="python_hello" name="code" path="python_hello_code_.kt"]
# A single-line print appears inline, beside the line that made it.
import platform
import numpy as np

print("python", platform.python_version())

# A multi-line one gets a block of its own, and keeps its columns.
a = np.arange(12).reshape(3, 4)
print(a)
```

## 4. the JVM is importable

<!-- field-box id="python_interop" index="3" -->

### Narrative

From `code`

JEP embeds CPython *in-process*, so java.lang -- and anything else on Field's
classpath -- is just another module, and this cell can reach its own Box.

```edn [field-box id="python_interop" role="properties"]
{"id" "python_interop" "index" 3 "name" "the JVM is importable" "parents" ["root" "python_root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "python_root"} "name" "the JVM is importable" "_tombstone" false "frame" #afield/rect [60.0 3040.0 340.0 100.0] "code_language" "Python"} "blockProperties" [{"name" "code" "language" "python" "path" "python_interop_code_.kt" "lines" 11 "characters" 370 "sha256" "37da8371909602b59bb8e4aa2ecfe5a033e0a4c11ddb7aae02ed7e2a2bf2fe4f"}]}
```

```python [field-property box="python_interop" name="code" path="python_interop_code_.kt"]
# JEP embeds CPython *in-process*, so java.lang -- and anything else on Field's
# classpath -- is just another module, and this cell can reach its own Box.
from java.lang import System
from afield.python import PythonInterface

box = PythonInterface.thisBox

print("\n".join([
    f"JVM       {System.getProperty('java.version')}",
    f"this box  {box.getName()}",
]))
```

## 5. the namespace is shared with cell 1

<!-- field-box id="python_shared" index="4" -->

### Narrative

From `code`

There is one interpreter for the whole Field process, and one global namespace.
`a` was never defined here -- it is still bound from cell 1. Run cell 1 first.

```edn [field-box id="python_shared" role="properties"]
{"id" "python_shared" "index" 4 "name" "the namespace is shared with cell 1" "parents" ["root" "python_root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "python_root"} "name" "the namespace is shared with cell 1" "_tombstone" false "frame" #afield/rect [60.0 2040.0 340.0 100.0] "code_language" "Python"} "blockProperties" [{"name" "code" "language" "python" "path" "python_shared_code_.kt" "lines" 7 "characters" 358 "sha256" "d0651379b2225bcf62fda643aeb0ec5f275c4477d8312911a0fa2e625617bb18"}]}
```

```python [field-property box="python_shared" name="code" path="python_shared_code_.kt"]
# There is one interpreter for the whole Field process, and one global namespace.
# `a` was never defined here -- it is still bound from cell 1. Run cell 1 first.
print("a is still here:", a.shape, a.sum())

# Which also means state survives between runs of this cell.
counter = globals().get("counter", 0) + 1
print("this cell has run", counter, "time(s)")
```

