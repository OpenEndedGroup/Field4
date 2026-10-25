# Life

Conway's Game of Life in **frame** mode. `PREV(x, y)` is the grid as the last tick left it, so the
rule reads only the past and writes the present. Only cells that change are `SET`, so the note marks
every birth. The first tick seeds a random 35 % and returns, so the second tick reads that seed;
the madp original judged its seed in the same frame and drew nothing.
