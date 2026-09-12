// The map, printed from the code rather than from a page. Seven ways to make samples, five ways
// out, four ways in — and 9 of the 16 on the one seam that matters.
SoundPathMap.Role.values().forEach { role ->
    println("${role.label}:")
    SoundPathMap.PATHS.filter { it.role == role }.forEach { p ->
        println("  %-20s %-19s %s  needs: %s".format(
            p.id, p.timing.label, if (p.onSeam) "[on the seam]" else "[off the seam]", p.needs))
        println("      ${p.what}")
        println("      ${p.file} — ${p.entryPoint}${if (p.page == null) "   (NO PAGE DOCUMENTS THIS)" else ""}")
    }
}
println()
println("${SoundPathMap.PATHS.count { it.onSeam }} of ${SoundPathMap.PATHS.size} paths meet AudioPatchSpec.Renderer.render(outL, outR, numFrames, ctx)")
println("no published page mentions: ${SoundPathMap.PATHS.filter { it.page == null }.map { it.id }}")
