# Four memory bands

Red, green, blue and alpha are four separate memories of one moving fbm field. The range vector
`0.015_0.18` is `vec4(0.015, 0.07, 0.125, 0.18)`. As the `ema` rate it makes red the slowest memory
and alpha the fastest, so the colours separate where the field moves.
