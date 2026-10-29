:::fold imports and recorded events
import afield.animation.mocap.*

val take = MotionRecording("%docs/assets/motion_recordings/closd-movement-score.f4motion".path)
val clip = take.clip()
val events = take.events().toList()
val attach = events.first { it.optString("type")=="applied" && it.optString("operation")=="ATTACH" }
val release = events.first { it.optString("type")=="applied" && it.optString("operation")=="DETACH" }.getDouble("appliedTime")
val parameters = attach.getJSONObject("payload")
val physics = events.filter { it.optString("type")=="tetherPhysics" }
val started = AnimationClock.nowSeconds()
:::endfold

loop {
    val elapsed = AnimationClock.nowSeconds() - started
    val duration = clip.endTime - clip.startTime
    val time = clip.startTime + elapsed % duration
    val frame = clip.sample(time).frame
    val active = time >= attach.getDouble("appliedTime") && time < release
    val event = physics.lastOrNull { it.getDouble("time") <= time }
    fun values(key: String) = event!!.getJSONArray(key).let { a -> (0..2).map { a.getDouble(it) } }
    val tether = if(active && event != null) TetherObservation(
        parameters.getString("id"), parameters.getString("body"), true, "attached", event.getDouble("time"),
        forceN=event.getDouble("forceN"), rawForceN=event.getDouble("rawForceN"),
        stretchMetres=event.getDouble("errorM"), maxForce=parameters.getDouble("maximumForce"),
        point=values("point"), anchor=values("anchor"), force=values("force")) else null
    val body = frame.executed.bones(clip.skeleton).apply {
        color=vec(.94,.96,1.0,1.0); thickness=3.0; pointed=true; pointSize=.8
    }
    val reference = frame.intended?.bones(clip.skeleton)?.apply { color=vec(.15,.7,.85,.65); thickness=1.5 }
    three(channel="motion", height=480, width=700) {
        lines(body); reference?.let { lines(it) }; lines(tether?.overlays() ?: emptyList())
        camera(yaw=35.0, pitch=12.0, padding=1.5)
        followCamera(vec(frame.executed.root[0],frame.executed.root[1],frame.executed.root[2]),
            halfLifeSeconds=.4, epoch="${(elapsed/duration).toInt()}:${frame.episode}")
        title("CLoSD · movement score · ${"%.2f".format(time)} s")
        helpers(true)
    }
    val phase = when {
        active -> "TETHERED · ${"%.0f".format(tether?.forceN ?: 0.0)} N"
        time >= release -> "RELEASED"
        else -> "WALKING"
    }
    markdown("status", "**$phase**\n\nRecorded execution of the Kotlin score: walk 2 s, tether 2 s, release and continue 3 s. " +
        "White: executed · cyan: reference · red: anchor · amber: submitted force (scaled). No reset in this take.")
    displayOpen("motion"); displayOpen("status")
    delay(16)
}
