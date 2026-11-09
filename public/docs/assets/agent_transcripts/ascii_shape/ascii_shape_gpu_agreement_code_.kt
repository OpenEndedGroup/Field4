// Twice a second: read back one frame's scene with the glyphs the match pass chose for it, convert
// the same pixels on the CPU, exhaustively and through the cache, and count the cells that agree.
frames {
    while (true) {
        val readback = ascii.readCells(scene, cells)
        while (!readback.isDone) frame()
        val (picture, chars) = readback.get()
        // the match box's contrast and directional
        val exact =
            ascii.convert(picture, chars.cols, contrast = 3.0, directional = 3.0, rows = chars.rows, exact = true)
        val cached = ascii.convert(picture, chars.cols, contrast = 3.0, directional = 3.0, rows = chars.rows)
        display(
            "agreement",
            "GPU = CPU exact on ${(100 * chars.agreement(exact)).f2}% of cells, " +
                "= CPU cached on ${(100 * chars.agreement(cached)).f2}%",
        )
        repeat(30) { frame() }
    }
}
