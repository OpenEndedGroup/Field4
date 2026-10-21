# Feedback fog

Keeps 98.2% of the last frame and adds a little moving fbm noise, coloured by the cosine palette.
Each pixel settles where the decay and the addition balance, about 4.4 times the palette colour,
so most of it is pushed to bright. Try `prev 0.9 *` for a thinner fog that follows the noise
closely.
