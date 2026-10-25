# Spring mesh

Neighbours closer than 60 pixels are joined by springs that want to be 30 long, and `clump` pulls
each particle toward the middle of its crowd. The web knits for a few seconds, then clumping wins
and it draws itself into balls. Drag a long stroke through the window to watch it knit again, or
lower the clump strength in `update()` and the web lasts.
