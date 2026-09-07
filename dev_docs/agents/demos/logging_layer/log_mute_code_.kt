import afield.ai.core.ConsoleMute

// The first of the three matchers, and the strictest: isMuted is
//
//     fun isMuted(channel: String): Boolean = channel in channels
//
// Exact membership of a set of whole channel strings. Nothing is split, so you cannot
// mute a *tag* — only the exact spelling written at the call site.
val spellings = listOf("info,boxes", "boxes,info", "info,boxes,extra", "info,Boxes", "boxes")

display("main", Table(
    listOf("channel string", "isMuted"),
    title = "ConsoleMute compares the whole channel string",
    note = "the set this JVM booted with: " + ConsoleMute.channels.sorted().joinToString(", ")
)
    .rows(spellings.map { listOf(it, ConsoleMute.isMuted(it)) })
    .role("channel string", CellRole.ACCENT)
    .roleBy("isMuted") { if (it == true) CellRole.WARN else CellRole.OK })

// Change the set with -Dafield.log.mute=a;b or AFIELD_LOG_MUTE=a;b (semicolon
// separated, exact strings), or a blank value to mute nothing.
