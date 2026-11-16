# Field Document: postscript_demo

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 6 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "_controlMidi" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_panel" "ai4_continuation" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "ai4_garden" "fileManagerAccessHistory" "kitRole" "mainLanguage"]}
```

## 1. postscript — children of this box are PostScript

<!-- field-box id="ps_root" index="0" -->

### Narrative

From `code`

This root stays Kotlin; its `mainLanguage` makes the boxes under it PostScript.

```edn [field-box id="ps_root" role="properties"]
{"id" "ps_root" "index" 0 "name" "postscript — children of this box are PostScript" "parents" ["root"] "children" ["ps_double" "ps_arrays" "ps_tree" "ps_rosette" "ps_error"] "properties" {"children" #{#afield/box "ps_double" #afield/box "ps_arrays" #afield/box "ps_tree" #afield/box "ps_rosette" #afield/box "ps_error"} "parents" #{#afield/box "root"} "name" "postscript — children of this box are PostScript" "_tombstone" false "frame" #afield/rect [40.0 40.0 420.0 80.0] "mainLanguage" "postscript"} "blockProperties" [{"name" "code" "language" "kotlin" "path" "ps_root_code_.kt" "lines" 2 "characters" 110 "sha256" "96345da7fff50fcd9c5ec9d6883adcdc8c2215ac83275089f424c78c66e1e77e"}]}
```

```kotlin [field-property box="ps_root" name="code" path="ps_root_code_.kt"]
// This root stays Kotlin; its `mainLanguage` makes the boxes under it PostScript.
println("PostScript root")
```

## 2. double

<!-- field-box id="ps_double" index="1" -->

```edn [field-box id="ps_double" role="properties"]
{"id" "ps_double" "index" 1 "name" "double" "parents" ["root" "ps_root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "ps_root"} "name" "double" "_tombstone" false "frame" #afield/rect [180.0 200.0 220.0 80.0] "code_language" "PostScript"} "blockProperties" [{"name" "code" "language" "postscript" "path" "ps_double_code_.kt" "lines" 3 "characters" 55 "sha256" "38230c0d807a19a4ed220fc605b680c5a8e6c66bd91d5a44d10c8e6d1581cea3"}]}
```

```postscript [field-property box="ps_double" name="code" path="ps_double_code_.kt"]
% Run the box: ok · <1> 6
/double {2 mul} def
3 double
```

## 3. tree

<!-- field-box id="ps_tree" index="2" -->

```edn [field-box id="ps_tree" role="properties"]
{"id" "ps_tree" "index" 2 "name" "tree" "parents" ["root" "ps_root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "ps_root"} "name" "tree" "_tombstone" false "frame" #afield/rect [440.0 200.0 220.0 80.0] "code_language" "PostScript"} "blockProperties" [{"name" "code" "language" "postscript" "path" "ps_tree_code_.kt" "lines" 14 "characters" 319 "sha256" "827cbe0e012c90be5fae9fbcae273d48ca5ba605884f3e1bbd439aa1cadf6bed"}]}
```

```postscript [field-property box="ps_tree" name="code" path="ps_tree_code_.kt"]
/branch { % length depth branch
  dup 0 gt {
    exch dup 0 0 moveto 0 exch lineto stroke
    dup 0 exch translate
    0.7 mul exch 1 sub
    gsave 25 rotate 2 copy branch grestore
    gsave -25 rotate branch grestore
  } { pop pop } ifelse
} def

0.55 0.85 0.4 setrgbcolor
2 setlinewidth
300 50 translate
100 8 branch
```

## 4. rosette

<!-- field-box id="ps_rosette" index="3" -->

```edn [field-box id="ps_rosette" role="properties"]
{"id" "ps_rosette" "index" 3 "name" "rosette" "parents" ["root" "ps_root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "ps_root"} "name" "rosette" "_tombstone" false "frame" #afield/rect [440.0 320.0 220.0 80.0] "code_language" "PostScript"} "blockProperties" [{"name" "code" "language" "postscript" "path" "ps_rosette_code_.kt" "lines" 7 "characters" 140 "sha256" "7b9038919b42f6019dfd467f3c48c6760ae37f5f7b1b63a2e55622fdeadb3466"}]}
```

```postscript [field-property box="ps_rosette" name="code" path="ps_rosette_code_.kt"]
200 200 translate
1.5 setlinewidth
0 1 35 {
  36 div 1 1 sethsbcolor
  newpath 0 0 moveto 120 0 60 40 0 60 curveto stroke
  10 rotate
} for
```

## 5. error

<!-- field-box id="ps_error" index="4" -->

```edn [field-box id="ps_error" role="properties"]
{"id" "ps_error" "index" 4 "name" "error" "parents" ["root" "ps_root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "ps_root"} "name" "error" "_tombstone" false "frame" #afield/rect [700.0 200.0 220.0 80.0] "code_language" "PostScript"} "blockProperties" [{"name" "code" "language" "postscript" "path" "ps_error_code_.kt" "lines" 4 "characters" 77 "sha256" "2034743e285c267bdf094c6388081b61137f28ff1a61caa4103f8aff0bf27021"}]}
```

```postscript [field-property box="ps_error" name="code" path="ps_error_code_.kt"]
% lineto with no current point: line 3 is marked
newpath
10 10 lineto
stroke
```

## 6. arrays and control flow

<!-- field-box id="ps_arrays" index="5" -->

```edn [field-box id="ps_arrays" role="properties"]
{"id" "ps_arrays" "index" 5 "name" "arrays and control flow" "parents" ["root" "ps_root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "ps_root"} "name" "arrays and control flow" "_tombstone" false "frame" #afield/rect [180.0 320.0 220.0 80.0] "code_language" "PostScript"} "blockProperties" [{"name" "code" "language" "postscript" "path" "ps_arrays_code_.kt" "lines" 6 "characters" 253 "sha256" "f8e6cac2bb570348423249c5193b99d973b3361f75dd3624cc3d17f05917a67f"}]}
```

```postscript [field-property box="ps_arrays" name="code" path="ps_arrays_code_.kt"]
/fib {dup 2 ge {1 sub dup 1 sub fib exch fib add} {} ifelse} def
[0 1 10 {fib} for] ==
[0 1 9 {2 copy add} repeat] ==
/reverse {[exch dup length 1 sub -1 0 {1 index exch get exch} for pop]} def
[11 22 33] reverse ==
0 { 1 add dup 10 ge {exit} if } loop
```

