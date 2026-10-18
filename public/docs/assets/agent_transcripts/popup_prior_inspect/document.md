# Field Document: popup_prior_inspect

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 1 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
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
{"id" "agent_popup_prior_inspect" "index" 0 "name" "What prior does the popup have" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "What prior does the popup have" "_tombstone" false "frame" #afield/rect [80.0 80.0 400.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "agent_popup_prior_inspect_code_.kt" "lines" 46 "characters" 1986 "sha256" "102b23a946a14a4ac3b399839a8762971bcbba843a7324f44e77e2770d05269e"}]}
```

```kotlin [field-property box="agent_popup_prior_inspect" name="code" path="agent_popup_prior_inspect_code_.kt"]
/*
Ask Field what prior the completion popup has, instead of trusting a number typed into a
page. `CommonMemberPrior` is the object the completion server itself calls, once per
candidate, on every keystroke — so this table is the popup's own arithmetic rather than a
description of it. Change `receiver` to any qualified type name to ask about your own class.

What this cannot tell you is which SECTION each of these lands in, which is the other half
of where a row appears on screen. That answer belongs to KotlinCompletionSupport, and it is
`internal` — deliberately unreachable from a box. Use `FIELD_RANK_DUMP` for that; it prints
a `category=` beside every visible row. See public/docs/completion_ranking.md.
*/
val receiver = "afield.geometry.path.FLine"
val ranks = afield.runtime.completion.CommonMemberPrior.ranksFor(receiver)

// Sorted by position rather than trusting the map's iteration order, so the figure is the
// same every time it is drawn.
val prior =
    Table(listOf("position", "member", "prior score"))
        .rows(
            ranks.entries
                .sortedBy { it.value }
                .map { (member, position) ->
                    listOf(position, member, afield.runtime.completion.CommonMemberPrior.scoreOf(position))
                },
        )
        .role("member", CellRole.ACCENT)

// Position 1 scores BASE, and each position after it costs STEP.
check(
    ranks.all { (_, position) ->
        afield.runtime.completion.CommonMemberPrior.scoreOf(position) ==
            afield.runtime.completion.CommonMemberPrior.BASE -
                afield.runtime.completion.CommonMemberPrior.STEP * (position - 1)
    },
) {
    "the prior is no longer BASE less STEP per position"
}

markdown(
    "main",
    """
    {muted:@CommonMembers on ${receiver.md} — ${ranks.size} members, first scores ${afield.runtime.completion.CommonMemberPrior.BASE}, less ${afield.runtime.completion.CommonMemberPrior.STEP} per position}

    ${prior.h}
""",
)
```

