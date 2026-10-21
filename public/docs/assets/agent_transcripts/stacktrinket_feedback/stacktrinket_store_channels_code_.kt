/*
Store channels. `0->` reads the red channel of this pixel's store, which here is the
previous frame, and `->0`, `->1`, `->2` write one value into red, green and blue. The
ring's brightness steps at random ten times a second (`|sh10`), and red keeps it as a
slow memory; green and blue are fixed fractions of it, so the colour is a warm orange.

The program is in the [[property stackSL]] tab.
*/
stackFeedback()
