# Store channels

`0->` reads the red channel of the previous frame at this pixel; `->0`, `->1` and `->2` write one
value into red, green and blue. The ring's brightness jumps at random ten times a second (`|sh10`),
red keeps it as a slow memory, and green and blue are fixed fractions of red, so the ring is a warm
orange.
