// Ask Field what prior the completion popup has, instead of trusting a number typed into a
// page. `CommonMemberPrior` is the object the completion server itself calls, once per
// candidate, on every keystroke — so this table is the popup's own arithmetic rather than a
// description of it. The category column is the same lookup that decides which SECTION a
// row renders under, and sectioning sits above ranking: a prior can order a row inside its
// section but can never lift it past a row in an earlier one.
val receiver = "afield.graphics.FLine"
val ranks = afield.CommonMemberPrior.ranksFor(receiver)

val cell = "padding:2px 20px 2px 0"
val rows = ranks.entries.joinToString("") { (member, position) ->
    val score = afield.CommonMemberPrior.scoreOf(position)
    val section = runCatching {
        afield.ai.KotlinSourceIndex.getCompletionCategoryForSymbol(member, declaredIn = receiver)
    }.getOrNull() ?: "no category here"
    "<tr><td style=\"$cell\">$position</td>" +
        "<td style=\"$cell\"><b>$member</b></td>" +
        "<td style=\"$cell\">$score</td>" +
        "<td style=\"padding:2px 0\">$section</td></tr>"
}

display("main", """
  <div style="font:12px/1.7 ui-monospace,monospace;color:#cfd8e3;padding:10px 14px">
    <div style="opacity:0.72">@CommonMembers on $receiver — ${ranks.size} members, first scores
      ${afield.CommonMemberPrior.BASE}, less ${afield.CommonMemberPrior.STEP} per position</div>
    <table style="margin-top:9px;border-collapse:collapse">
      <tr style="opacity:0.62;text-align:left">
        <th style="$cell">position</th>
        <th style="$cell">member</th>
        <th style="$cell">prior score</th>
        <th style="padding:2px 0">section it lands in</th>
      </tr>
      $rows
    </table>
  </div>
""")
