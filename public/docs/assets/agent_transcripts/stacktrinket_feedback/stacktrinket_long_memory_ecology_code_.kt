/*
Long memory ecology: the previous frame steers where it is read from next. Its own red
and green, then blue and alpha, become offsets into itself; two slow waves add a drift;
fbm warped by the old picture is mixed in; and `ema` stores the result as four
memories at rates from 0.006 to 0.09. `mirror` folds the total back into 0..1.

The fold is what decides the look. `dup average … + +` adds each pixel's average back
on top every frame, so its values keep climbing, and `mirror` folds them into grain
that never settles. Delete that line and the reads, which together take in about 1.65
times the old picture, slowly fill the memories instead, into a pale moving fog.

The program is in the [[property stackSL]] tab.
*/
stackFeedback()
