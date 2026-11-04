# Field Document: ai4_cold_reader

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 1 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "_controlMidi" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. ColdReader

<!-- field-box id="ai4_cold_reader" index="0" -->

### Narrative

From `code`

A cold reader for the docs, as two tools for this box's AI chat. Ask it to cold-read a page;
it reads the page with cold_read_page and calls report_stall once per stall. Each stall is
appended to stalls.jsonl (path shown below) and listed in the "stalls" output.

```edn [field-box id="ai4_cold_reader" role="properties"]
{"id" "ai4_cold_reader" "index" 0 "name" "ColdReader" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "ColdReader" "_tombstone" false "frame" #afield/rect [80.0 80.0 300.0 80.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "ai4_cold_reader_code_.kt" "lines" 70 "characters" 3115 "sha256" "591aa97e780539f16dc72829ba64ef16c56975da71c37834487897b64bfc686b"}]}
```

```kotlin [field-property box="ai4_cold_reader" name="code" path="ai4_cold_reader_code_.kt"]
/*
A cold reader for the docs, as two tools for this box's AI chat. Ask it to cold-read a page;
it reads the page with cold_read_page and calls report_stall once per stall. Each stall is
appended to stalls.jsonl (path shown below) and listed in the "stalls" output.
*/
import java.io.File

val repo = File((thisBox.properties["coldReader.repo"] as? String) ?: System.getProperty("user.dir"))
val out = File(repo, "build/cold_reader/stalls.jsonl")
val categories: List<String> = [
    "undefined-term",
    "fence-unintroduced",
    "page-meta",
    "mechanism-first",
    "dense",
    "unparseable",
    "ceremony",
    "broken-markup",
    "false-claim",
]

val seen = mutableListOf<String>()
fun json(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

aiTool("cold_read_page") {
    describe(
        "Read one page of Field's documentation as it stood at a git revision, as numbered lines. " +
            "Read it as a newcomer would: in order, without the source and without the pages it links to.",
    )
    effects(ToolEffect.FILESYSTEM_READ)
    val page by param<String>("the page under public/docs, e.g. staging.md")
    val revision by param<String>("a git revision, e.g. a commit hash; HEAD for the current page", default = "HEAD")
    action {
        val p =
            ProcessBuilder("git", "-C", repo.path, "show", "${revision()}:public/docs/${page()}")
                .redirectErrorStream(true)
                .start()
        val text = p.inputStream.bufferedReader().readText()
        if (p.waitFor() != 0) return@action toolError(text.trim())
        text.lines().mapIndexed { i, l -> "${i + 1}: $l" }.joinToString("\n")
    }
}

aiTool("report_stall") {
    describe(
        "Record one place a newcomer reading the page would stall. Quote the page exactly. Categories: " +
            categories.joinToString(", ") +
            ". Report only what the page itself shows; one call per stall.",
    )
    effects(ToolEffect.FILESYSTEM_WRITE)
    val page by param<String>("the page, e.g. staging.md")
    val line by param<Int>("the line number cold_read_page showed")
    val category by param<String>("one of: " + categories.joinToString(", "))
    val exact by param<String>("the words on the page where the reader stalls, quoted exactly, at most a sentence")
    val note by param<String>("one sentence: why a newcomer stalls, and what would fix it")
    action {
        if (category() !in categories) return@action toolError("category must be one of $categories")
        out.parentFile.mkdirs()
        out.appendText(
            "{\"page\": ${json(page())}, \"line\": ${line()}, \"category\": ${json(category())}, " +
                "\"exact\": ${json(exact())}, \"note\": ${json(note())}, \"source\": \"ai4\"}\n",
        )
        seen += "<div>${page()}:${line()} <b>${category()}</b> — ${exact()}</div>"
        display("stalls", seen.joinToString(""))
        "recorded ${page()}:${line()} ${category()}"
    }
}

display("stalls", "<div>stalls go to ${out.path}</div>")
println("ColdReader tools registered; stalls go to $out")
```

