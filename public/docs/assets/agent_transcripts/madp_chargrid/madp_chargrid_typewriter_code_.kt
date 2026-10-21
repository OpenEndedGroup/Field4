// A C64 boot screen typed out in stream mode, then 10 PRINT forever. type() is an ordinary local
// suspend function: SET waits a tick, and `delay` more waitFrame()s slow the typing down.
charStream(glyphs = CHARGRID_ASCII) {
    clearGrid()

    suspend fun type(text: String, color: String? = null, delay: Int = 1) {
        for (ch in text) {
            if (ch == '\n') newLine() else SET(glyphIndex(ch), color)
            repeat(delay) { waitFrame() }
        }
    }

    type("**** CHARGRID 64 BASIC V2 ****\n", "#6a9fdb", 2)
    type("\n")
    type("40K RAM SYSTEM  38911 BASIC BYTES FREE\n", "#6a9fdb", 2)
    type("\nREADY.\n", "#50fa7b", 2)
    repeat(30) { waitFrame() }

    type("> 10 PRINT CHR$(205.5+RND(1));\n")
    type("> 20 GOTO 10\n")
    type("> RUN\n")
    repeat(12) { waitFrame() }

    while (true) {
        val spin = if (rand() < 0.5) "transform:rotate(45deg)" else null
        SET(glyphIndex(if (rand() < 0.5) '/' else '\\'), "#50fa7b", spin)
        waitFrame()
    }
}
