/*
The breeding rules are settings too. ColorRpnBreeding's weights say how often each kind of
mutation happens (the demo's mix: num 33%, var 19%, op 20%, splice 10%, copy 6%, drop 6%,
add 4.5%, swap 1.5%). Here only numbers change and nothing is borrowed from the other
parent, and nudgeLength = false stops the length being topped up with random words, so every
child keeps its parent's shape: the same rings and bands at different frequencies.
*/
colorRpnEvolution(
    "r 12 * sin a 9 * cos + 0.6 0.5 hsl",
    "x y + 7 * 2.2 fbm 0.65 0.55 hsl",
    ColorRpnBreeding(
        mutationRate = 0.25,
        seedSplice = 0.0,
        weights = mapOf("num" to 1.0),
        nudgeLength = false,
    ),
)
