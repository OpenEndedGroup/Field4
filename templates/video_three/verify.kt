// Execute in the whole-ring rules box of a disposable launched carousel.
val preserved = groupLooks.scope.capture()
val preservedCooling = carousel.surfaces.map { it.shadowCooling }
val preservedSpeed = carousel.source.speed
try {
    val wave = sourceBlock { screenNo: Int -> 0.1 + 0.04 * screenNo }
    groupLooks.group["output.shadowCooling"] = wave
    carousel.surfaces.forEachIndexed { i, s -> check(kotlin.math.abs(s.shadowCooling - (.1 + .04 * i)) < 1e-6) }
    val file = java.nio.file.Files.createTempDirectory("video-three-rule-proof").resolve("wave")
    groupLooks.scope.capture().save(file)
    groupLooks.group["output.shadowCooling"] = 0.0
    groupLooks.scope.loadKey(file)()
    carousel.surfaces.forEachIndexed { i, s -> check(kotlin.math.abs(s.shadowCooling - (.1 + .04 * i)) < 1e-6) }
    val beforeBad = carousel.surfaces.map { it.shadowCooling }
    val rejected = runCatching {
        groupLooks.scope.Key("output.shadowCooling" to .8, "flash.onFrames" to 0.0, "flash.offFrames" to 0.0)()
    }
            .exceptionOrNull()!!
    check(rejected.message!!.contains("screen index 0"))
    check(carousel.surfaces.map { it.shadowCooling } == beforeBad)
    check(carousel.source.speed == preservedSpeed)
} finally {
    preserved()
}
check(carousel.surfaces.map { it.shadowCooling } == preservedCooling)
println(
    "Eight real surfaces: function application, native save/reload, flash validation and unchanged shared speed passed",
)
