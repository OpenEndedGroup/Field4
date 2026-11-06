/*
Species may write into each other's channels. Here species 1 (cyan) also lays half a
mark of species 0's scent wherever it goes, and species 0 (amber) is repelled twice as
hard by cyan as before. Re-run with other numbers: both matrices are read every frame.
*/
physarum.settings.depositInto = listOf(
    vec(1f, 0f, 0f, 0f),
    vec(0.5f, 1f, 0f, 0f),
)
physarum.settings.attraction = listOf(
    vec(1f, -2f, 0f, 0f),
    vec(1f, 1f, 0f, 0f),
)
println("chase: species 1 follows species 0 and marks its channel")
