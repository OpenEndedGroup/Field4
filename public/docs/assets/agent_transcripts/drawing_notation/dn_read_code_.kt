// Reading the drawing: every mark, hand-drawn or not, with what it is over and what it joins.
for (m in notation.marks) {
    val where = m.box?.let { " over '${it.name}'" } ?: ""
    val joins = if (m.kind == MarkType.ARROW) " from '${m.fromBox?.name}' to '${m.toBox?.name}'" else ""
    println("${m.kind.name.lowercase()} ${m.label ?: ""}$where$joins")
}
