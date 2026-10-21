/*
Persistent bloom: a ring at radius 0.32 whose colour drifts round the palette
(`0.08t palette`), added on top of 96.5% of the previous frame. The ring remembers
every colour it has been, so it slowly burns towards white.

The program is in the [[property stackSL]] tab.
*/
stackFeedback()
