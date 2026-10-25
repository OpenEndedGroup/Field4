/*
Four memory bands: red, green, blue and alpha are four separate memories of one slowly
moving fbm field, each offset a little. `0.015_0.18` is a range vector,
vec4(0.015, 0.07, 0.125, 0.18): as the `ema` rate it makes red the slowest memory and
alpha the fastest, so the colours separate where the field moves.

The program is in the [[property stackSL]] tab.
*/
stackFeedback()
