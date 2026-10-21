/*
Feedback fog: each frame keeps 98.2% of the last one and adds a little moving fbm,
coloured by the cosine palette. A pixel settles where the decay and the addition
balance, about 0.08 / 0.018 = 4.4 times the palette colour, so most of it clips to
bright. Try `prev 0.9 *` for a thinner fog that follows the noise more closely.

The program is in the [[property stackSL]] tab.
*/
stackFeedback()
