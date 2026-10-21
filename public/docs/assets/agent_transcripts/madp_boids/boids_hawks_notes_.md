# Hawks

Predator and prey. Two hawks `chase` the nearest starling, leading its motion, and `eat` one on
contact; then they rest (`b.cool` counts down, and the hawk dims) before hunting again. The
eaten respawn at an edge, and the metrics card counts them. Starlings `flee` anything hawk-shaped
within 90 pixels, so the flock splits around each pass and heals behind it. Tick **inspect** and
click a starling to see what it notices.
