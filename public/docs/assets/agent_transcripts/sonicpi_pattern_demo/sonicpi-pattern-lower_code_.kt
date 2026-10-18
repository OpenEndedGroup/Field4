/*
Pat plays Sonic Pi — the program, before you hear it.

Field's pattern algebra keeps musical structure exact in rational cycle space. Sonic Pi has 66
synths, 42 FX, 206 samples and a sample-accurate scheduler. This is the join: a pattern is
lowered to ordinary Sonic Pi source that you can read, paste into a #sonicpi region, and edit.

  public/docs/sonic_pi.md — "Field patterns as a Sonic Pi sequencer"

Nothing here needs Sonic Pi installed. It is a text transformation.
*/

/*
A triplet inside a binary bar — three even hits inside the third quarter. This is exactly where
a lowering that wrote `sleep 0.333` would start drifting, and it is why the emitter writes
Rational(1, 3) instead.
*/
val groove = pat("bd ~ [bd bd bd] bd")

println(groove.sonicPiSource(cps = 0.5, cycles = 1, name = "groove"))

// use_bpm makes one Sonic Pi beat exactly one Field cycle, so the sleeps above ARE the pattern's
// cycle fractions. Nothing about them depends on the tempo — change cps and only use_bpm moves.

// Atoms resolve against the *installed* vocabulary, in order: a real sample name, then a real
// synth name, then a note, then the drum-machine aliases.
println()
println(SonicPiVocabulary)
val kit = SonicPiInstruments()
listOf("bd", "hh", "bd_tek", "tb303", "c4", "60", "nonesuch").forEach {
    println("  %-10s -> %s".format(it, kit.resolve(it) ?: "NOTHING — this atom would be silent"))
}
