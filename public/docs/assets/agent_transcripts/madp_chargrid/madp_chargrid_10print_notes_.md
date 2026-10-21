# 10 PRINT

The program the whole demo is named after: `10 PRINT CHR$(205.5+RND(1)); : GOTO 10` picks one of two
diagonals at random, forever. In **stream** mode every `SET` waits a tick, so the maze types itself
out one cell at a time and scrolls when it reaches the bottom. Each diagonal is its own pulse note,
so the maze is also a random two-note melody. Try a third glyph, or `fps = 120.0`.
