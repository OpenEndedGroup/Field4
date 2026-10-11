# Field Document: ai4_chat_archive

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 4 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. chat-lens-viewer

<!-- field-box id="chat-lens-viewer" index="0" -->

```edn [field-box id="chat-lens-viewer" role="properties"]
{"id" "chat-lens-viewer" "index" 0 "name" "chat-lens-viewer" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "chat-lens-viewer" "_tombstone" false "frame" #afield/rect [80.0 240.0 420.0 60.0] "autorun" false} "blockProperties" [{"name" "code" "language" "kotlin" "path" "chat-lens-viewer_code_.kt" "lines" 6 "characters" 231 "sha256" "9a5a7570af6f9a4ae9254513544d3e6c8b21a33cfddae7a827e1edc196464166"}]}
```

```kotlin [field-property box="chat-lens-viewer" name="code" path="chat-lens-viewer_code_.kt"]
import afield.chatlog.*
import java.io.File

val assets = File("public/docs/assets/ai4_chat_archive")
registerChatlogTools(File(assets, "chats"), lensDir = File(assets, "lenses"))
showChatLens("harbour", "storyline", height = 600)
```

## 2. chat-archive-outline

<!-- field-box id="chat-archive-outline" index="1" -->

```edn [field-box id="chat-archive-outline" role="properties"]
{"id" "chat-archive-outline" "index" 1 "name" "chat-archive-outline" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "chat-archive-outline" "_tombstone" false "frame" #afield/rect [-400.0 40.0 420.0 60.0] "autorun" false} "blockProperties" [{"name" "code" "language" "kotlin" "path" "chat-archive-outline_code_.kt" "lines" 6 "characters" 290 "sha256" "caf90a9eb40eb60bddd4dae89c7d5da51824895bf6b5847da164fb126240b332"}]}
```

```kotlin [field-property box="chat-archive-outline" name="code" path="chat-archive-outline_code_.kt"]
import afield.chatlog.*
import java.io.File

val harbour = File("public/docs/assets/ai4_chat_archive/chats/harbour")
val tree = ChatArchive.tree(ChatArchive.chats(harbour).single())
display("outline", "<pre style='margin:8px'>" + ChatLogViews.outline(tree).replace("<", "&lt;") + "</pre>")
```

## 3. chat-archive-read

<!-- field-box id="chat-archive-read" index="2" -->

```edn [field-box id="chat-archive-read" role="properties"]
{"id" "chat-archive-read" "index" 2 "name" "chat-archive-read" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "chat-archive-read" "_tombstone" false "frame" #afield/rect [80.0 40.0 420.0 60.0] "autorun" false} "blockProperties" [{"name" "code" "language" "kotlin" "path" "chat-archive-read_code_.kt" "lines" 7 "characters" 365 "sha256" "3ec01f9a8914c9a90ec69b05783cd31a4504c4f29e16659452b531925718b30f"}]}
```

```kotlin [field-property box="chat-archive-read" name="code" path="chat-archive-read_code_.kt"]
import afield.chatlog.*
import java.io.File

val harbour = File("public/docs/assets/ai4_chat_archive/chats/harbour")
val tree = ChatArchive.tree(ChatArchive.chats(harbour).single())
val page = ChatLogViews.read(tree, "t-23d59848-f38.b3", "t-23d59848-f38.b8", detail = "full")
display("night", "<pre style='margin:8px'>" + page.text.replace("<", "&lt;") + "</pre>")
```

## 4. chat-archive-lens-stats

<!-- field-box id="chat-archive-lens-stats" index="3" -->

```edn [field-box id="chat-archive-lens-stats" role="properties"]
{"id" "chat-archive-lens-stats" "index" 3 "name" "chat-archive-lens-stats" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "chat-archive-lens-stats" "_tombstone" false "frame" #afield/rect [-400.0 240.0 420.0 60.0] "autorun" false} "blockProperties" [{"name" "code" "language" "kotlin" "path" "chat-archive-lens-stats_code_.kt" "lines" 9 "characters" 530 "sha256" "b6d564678150f6cade045c5985c540a873187a5a906c57ff72f6b2969f374e8d"}]}
```

```kotlin [field-property box="chat-archive-lens-stats" name="code" path="chat-archive-lens-stats_code_.kt"]
import afield.chatlog.*
import java.io.File

val assets = File("public/docs/assets/ai4_chat_archive")
val tree = ChatArchive.tree(ChatArchive.chats(File(assets, "chats/harbour")).single())
val lens = File(assets, "lenses/harbour/storyline.md").readText()
val problems = ChatLogViews.lensProblems(tree, lens).ifEmpty { listOf("none") }
val stats = ChatLogViews.lensStats(tree, lens).entries.joinToString("\n") { (k, v) -> "$k: $v" }
display("lens", "<pre style='margin:8px'>problems: ${problems.joinToString("; ")}\n$stats</pre>")
```

