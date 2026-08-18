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
