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
a `category=` beside every visible row. See public/docs/how_the_popup_decides.md.

```edn [field-box id="agent_popup_prior_inspect" role="properties"]
{"id" "agent_popup_prior_inspect" "index" 0 "name" "What prior does the popup have" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "What prior does the popup have" "_tombstone" false "frame" #afield/rect [80.0 80.0 680.0 190.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "agent_popup_prior_inspect_code_.kt" "lines" 34 "characters" 1741 "sha256" "ed63a03d22e892d1965001a2f20197bdf7f6e89b28d1414f66aac0478f2848ce"}]}
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
// a `category=` beside every visible row. See public/docs/how_the_popup_decides.md.
val receiver = "afield.graphics.FLine"
val ranks = afield.CommonMemberPrior.ranksFor(receiver)

val cell = "padding:2px 20px 2px 0"
val rows = ranks.entries.joinToString("") { (member, position) ->
    val score = afield.CommonMemberPrior.scoreOf(position)
    "<tr><td style=\"$cell\">$position</td>" +
        "<td style=\"$cell\"><b>$member</b></td>" +
        "<td style=\"padding:2px 0\">$score</td></tr>"
}

display("main", """
  <div style="font:12px/1.7 ui-monospace,monospace;color:#cfd8e3;padding:10px 14px">
    <div style="opacity:0.72">@CommonMembers on $receiver — ${ranks.size} members, first scores
      ${afield.CommonMemberPrior.BASE}, less ${afield.CommonMemberPrior.STEP} per position</div>
    <table style="margin-top:9px;border-collapse:collapse">
      <tr style="opacity:0.62;text-align:left">
        <th style="$cell">position</th>
        <th style="$cell">member</th>
        <th style="padding:2px 0">prior score</th>
      </tr>
      $rows
    </table>
  </div>
""")
```

