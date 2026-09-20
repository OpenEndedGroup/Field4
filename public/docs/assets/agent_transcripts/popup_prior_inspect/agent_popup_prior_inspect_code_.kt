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

// Position 1 scores BASE, and each position after it costs STEP.
check(ranks.all { (_, position) ->
    afield.CommonMemberPrior.scoreOf(position) ==
        afield.CommonMemberPrior.BASE - afield.CommonMemberPrior.STEP * (position - 1)
}) { "the prior is no longer BASE less STEP per position" }

markdown("main", """
    {muted:@CommonMembers on ${receiver.md} — ${ranks.size} members, first scores ${afield.CommonMemberPrior.BASE}, less ${afield.CommonMemberPrior.STEP} per position}

    ${prior.h}
""")
