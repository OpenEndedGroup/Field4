# Field Document: popup_prior_inspect

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 1 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "fragment" "stackSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "ai4_auto_name" "ai4_auto_name_signature" "fileManagerAccessHistory"]}
```

## 1. What prior does the popup have

<!-- field-box id="agent_popup_prior_inspect" index="0" -->

### Narrative

From `code`

Ask Field what prior the completion popup has, instead of trusting a number typed into a
page. `CommonMemberPrior` is the object the completion server itself calls, once per
candidate, on every keystroke — so this table is the popup's own arithmetic rather than a
description of it. Change `receiver` to any qualified type name to ask about your own class.

What this cannot tell you is which SECTION each of these lands in, which is the other half
of where a row appears on screen. That answer belongs to KotlinCompletionSupport, and it is
`internal` — deliberately unreachable from a box. Use `FIELD_RANK_DUMP` for that; it prints
a `category=` beside every visible row. See public/docs/completion_ranking.md.

```edn [field-box id="agent_popup_prior_inspect" role="properties"]
{"id" "agent_popup_prior_inspect" "index" 0 "name" "What prior does the popup have" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "What prior does the popup have" "_tombstone" false "frame" #afield/rect [80.0 80.0 300.0 50.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "agent_popup_prior_inspect_code_.kt" "lines" 31 "characters" 1735 "sha256" "9abc89fb22d2b377e6ba96561cdef1b6f7c3edc66bdc826bf733789d4f265952"}]}
```

```kotlin [field-property box="agent_popup_prior_inspect" name="code" path="agent_popup_prior_inspect_code_.kt"]
// Ask Field what prior the completion popup has, instead of trusting a number typed into a
// page. `CommonMemberPrior` is the object the completion server itself calls, once per
// candidate, on every keystroke — so this table is the popup's own arithmetic rather than a
// description of it. Change `receiver` to any qualified type name to ask about your own class.
//
// What this cannot tell you is which SECTION each of these lands in, which is the other half
// of where a row appears on screen. That answer belongs to KotlinCompletionSupport, and it is
// `internal` — deliberately unreachable from a box. Use `FIELD_RANK_DUMP` for that; it prints
// a `category=` beside every visible row. See public/docs/completion_ranking.md.
val receiver = "afield.graphics.FLine"
val ranks = afield.CommonMemberPrior.ranksFor(receiver)

// Sorted by position rather than trusting the map's iteration order, so the figure is the
// same every time it is drawn.
val prior = Table(listOf("position", "member", "prior score"))
    .rows(ranks.entries.sortedBy { it.value }.map { (member, position) ->
        listOf(position, member, afield.CommonMemberPrior.scoreOf(position))
    })
    .role("member", CellRole.ACCENT)

// The arithmetic the heading claims, checked rather than described.
check(ranks.all { (_, position) ->
    afield.CommonMemberPrior.scoreOf(position) ==
        afield.CommonMemberPrior.BASE - afield.CommonMemberPrior.STEP * (position - 1)
}) { "the prior is no longer BASE less STEP per position" }

markdown("main", """
    {muted:@CommonMembers on ${mdEscape(receiver)} — ${ranks.size} members, first scores ${afield.CommonMemberPrior.BASE}, less ${afield.CommonMemberPrior.STEP} per position}

    ${prior.h}
""")
```

