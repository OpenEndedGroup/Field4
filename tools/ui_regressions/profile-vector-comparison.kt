// Run once after the transcript, then again after relaunching the vector box with reuse = true.
val sample = CoroutineProfiles.request(thisBox.inside.filename, CoroutineProfileRequest())
check(!sample.recording)
val lanes = sample.lanes.filter { it.boxId == "profile_vectors" }.map { it.id }.toSet()
val observed = sample.spans.filter { it.lane in lanes }
check(observed.size > 30 && observed.all { it.allocatedBytes >= 0 })
val mean = observed.map { it.allocatedBytes.toDouble() }.average()
val baseline = thisBox.getPropertyRaw("profile_baseline_bytes") as? Double
if (baseline == null) {
    thisBox.setPropertyRaw("profile_baseline_bytes", mean)
    println("PROFILE_BASELINE_BYTES_PER_RESUME=$mean")
} else {
    check(mean < baseline * 0.1) { "Reuse allocated $mean bytes per resume versus $baseline before" }
    println("PROFILE_REUSE_BYTES_PER_RESUME=$mean; baseline=$baseline")
}
