import afield.graphics.scene.body.*
import afield.graphics.vulkan.Target
import afield.graphics.vulkan.targets.OpenXRHmdTarget.*

// Shared state and artistic controls. All positions and lengths are in world-space metres.
val xr = renderer.openXRHmdTarget!!
var showHands = true
var showBody = true
var showControllers = true
var showAxes = true
var showBones = true
var inspectionCopy = true
var markerRadius = .006f
var lineWidth = .0015f
var axisLength = .035f
val inspectionOffset = vec(0f, 0f, -1.6f)
val headPosition = vec(0f, 0f, 0f)
val headOrientation = Quaternionf()

// Inspection is a translation of the drawing; raw observations remain unchanged.
fun recenterInspection() {
    if (xr.headPose(headPosition, headOrientation)) {
        inspectionOffset.set(0f, 0f, -1.6f)
        headOrientation.transform(inspectionOffset)
        inspectionOffset.y = 0f
    }
}
recenterInspection()

// View controls. These do not change the observations supplied by the runtime.
controlGroup("tracking view") {
    checkbox("Inspection copy ahead of me", initialValue = true) { inspectionCopy = it }
    button("Recenter inspection copy") { down -> if (down) recenterInspection() }
    checkbox("Hands", initialValue = true) { showHands = it }
    checkbox("Body / raw trackers", initialValue = true) { showBody = it }
    checkbox("Controllers", initialValue = true) { showControllers = it }
    checkbox("Joint orientation axes", initialValue = true) { showAxes = it }
    checkbox("Connections between available joints", initialValue = true) { showBones = it }
    slider("Joint radius (metres)", min = .002, max = .03, step = .001, initialValue = .006) {
        markerRadius = it.toFloat()
    }
    slider("Line half-width (metres)", min = .0005, max = .006, step = .0005, initialValue = .0015) {
        lineWidth = it.toFloat()
    }
    slider("Axis length (metres)", min = .01, max = .15, step = .005, initialValue = .035) { axisLength = it.toFloat() }
}

// Geometric queries. Radii zero reduce the volume model to joint centres and limb segments.
prop<BodyGeometry>(trackedBody) = xr.bodyGeometry()
var showBounds = true
var showClosestPoint = true
var roundedQueries = true
var includeHead = true
var headRadius = .09f
var limbRadius = .04f
var queryHit: BodyHit? = null
var queryJoint: BodyHit? = null
val queryWorld = vec(0f, 0f, -1f)
controlGroup("body queries") {
    checkbox("Show body bounds", initialValue = true) { showBounds = it }
    checkbox("Show closest point", initialValue = true) { showClosestPoint = it }
    checkbox("Head sphere / limb cylinders", initialValue = true) { roundedQueries = it }
    checkbox("Include head in bounds and queries", initialValue = true) { includeHead = it }
    slider("Head sphere radius (metres)", min = .02, max = .3, step = .005, initialValue = .09) {
        headRadius = it.toFloat()
    }
    slider("Limb cylinder radius (metres)", min = .005, max = .2, step = .005, initialValue = .04) {
        limbRadius = it.toFloat()
    }
}

// Marker construction and Markdown observation reporting.
val rows = mutableListOf<String>()
var used = 0

fun xyz(p: Vector3f) = "${p.x.f2}, ${p.y.f2}, ${p.z.f2}"

fun mark(a: Vector3f, b: Vector3f, color: Vector4f, radius: Float, circular: Boolean) {
    check(used < trackingMarks.size) { "Increase the tracking mark pool" }
    val m = trackingMarks[used++]
    val offset = if (inspectionCopy) inspectionOffset else vec(0f, 0f, 0f)
    m[0].set(a.x + offset.x, a.y + offset.y, a.z + offset.z, radius)
    m[1].set(b.x + offset.x, b.y + offset.y, b.z + offset.z, if (circular) 1f else 0f)
    m[2].set(color)
}

fun segment(a: JointPose?, b: JointPose?, color: Vector4f) {
    if (showBones && a?.positionValid == true && b?.positionValid == true)
        mark(a.position, b.position, color, lineWidth, false)
}

fun reportSection(title: String) {
    rows +=
        "\n### $title\n\n| Observation | Position | Orientation | World XYZ (m) | Radius (m) |\n|---|---|---|---|---|"
}

fun joint(label: String, pose: JointPose?, color: Vector4f, visible: Boolean = true) {
    val valid = pose?.positionValid == true
    rows +=
        "| $label | ${if (valid) "valid" else "unavailable"} | ${if (pose?.orientationValid == true) "valid" else "—"} | ${if (valid) xyz(pose!!.position) else "—"} | ${if (valid) pose!!.radius.f3 else "—"} |"
    if (!valid || !visible) return
    val p = pose!!
    mark(p.position, p.position, color, markerRadius, true)
    if (showAxes && p.orientationValid) {
        val axes: List<Pair<Vector3f, Vector4f>> = [
            vec(axisLength, 0f, 0f) to vec(1f, .15f, .1f, .8f),
            vec(0f, axisLength, 0f) to vec(.15f, 1f, .2f, .8f),
            vec(0f, 0f, axisLength) to vec(.15f, .4f, 1f, .8f),
        ]
        for ((direction, tint) in axes) {
            p.orientation.transform(direction).add(p.position)
            mark(p.position, direction, tint, lineWidth * .6f, false)
        }
    }
}

// OpenXR hand order: wrist → metacarpal → finger joints → tip; palm joins the wrist.
val handChains: List<List<Int>> = [
    [1, 2, 3, 4, 5],
    [1, 6, 7, 8, 9, 10],
    [1, 11, 12, 13, 14, 15],
    [1, 16, 17, 18, 19, 20],
    [1, 21, 22, 23, 24, 25],
    [0, 1],
]

// The right palm/controller is the query; without either, use a point ahead of the head.
// Query and result markers use the same optional inspection offset as the skeleton.
fun updateBodyQueries() {
    trackedBody = xr.bodyGeometry()
    val hand = xr.handState(Hand.RIGHT)
    val controller = xr.controllerState(Hand.RIGHT)
    val palm = hand?.get(HandJoint.PALM)
    when {
        hand?.isActive == true && palm?.positionValid == true -> queryWorld.set(palm.position)
        controller.poseValid -> queryWorld.set(controller.position)
        else -> {
            queryWorld.set(0f, 0f, -.6f)
            headOrientation.transform(queryWorld).add(headPosition)
        }
    }
    val lr = if (roundedQueries) limbRadius else 0f
    val hr = if (roundedQueries) headRadius else 0f
    queryHit = trackedBody.closestPoint(queryWorld, lr, hr, includeHead)
    queryJoint = trackedBody.closestJoint(queryWorld)
    if (showClosestPoint) {
        mark(queryWorld, queryWorld, vec(1f, .2f, .8f, 1f), markerRadius * 1.5f, true)
        queryHit?.let { hit ->
            mark(hit.point, hit.point, vec(.3f, 1f, .2f, 1f), markerRadius * 1.5f, true)
            mark(queryWorld, hit.point, vec(.3f, 1f, .2f, .8f), lineWidth, false)
        }
    }
    if (showBounds)
        trackedBody.bounds(lr, hr, includeHead)?.let { bounds ->
            val corners =
                (0..7).map { i ->
                    vec(
                        if (i and 1 == 0) bounds.min.x else bounds.max.x,
                        if (i and 2 == 0) bounds.min.y else bounds.max.y,
                        if (i and 4 == 0) bounds.min.z else bounds.max.z,
                    )
                }
            for (i in 0..7) for (axis in 0..2) {
                val bit = 1 shl axis
                if (i and bit == 0)
                    mark(corners[i], corners[i or bit], vec(.55f, .65f, .8f, .55f), lineWidth * .6f, false)
            }
        }
}

var lastReport = 0L

// Sample after XR locates the eyes, before recording the stereo target.
val updateTracking = Target { _, _ ->
    rows.clear()
    used = 0
    reportSection("Head")
    val headValid = xr.headPose(headPosition, headOrientation)
    joint("HEAD", JointPose(headPosition, headOrientation, 0f, headValid, headValid), vec(1f, 1f, 1f, .8f))
    for (hand in Hand.entries) {
        val tint = if (hand == Hand.LEFT) vec(.2f, .65f, 1f, .9f) else vec(1f, .6f, .15f, .9f)
        val state = xr.handState(hand)
        reportSection(
            "$hand hand · active=${state?.isActive ?: false} · pinch=${state?.pinchStrength?.f2 ?: "unavailable"}",
        )
        for (j in HandJoint.entries) joint("$hand $j", state?.get(j), tint, showHands && state?.isActive == true)
        if (showHands && state?.isActive == true)
            for (chain in handChains) for ((a, b) in chain.zipWithNext()) segment(
                state.joints[a],
                state.joints[b],
                tint,
            )
        val controller = xr.controllerState(hand)
        rows +=
            "\n$hand controller: active=${controller.active}, trigger=${controller.triggerValue.f2}, squeeze=${controller.squeezeValue.f2}, stick=(${controller.thumbstickX.f2}, ${controller.thumbstickY.f2}), A=${controller.aPressed}, B=${controller.bPressed}, menu=${controller.menuPressed}\n"
        reportSection("$hand controller pose")
        joint(
            "$hand controller grip",
            JointPose(controller.position, controller.rotation, 0f, controller.poseValid, controller.poseValid),
            vec(.9f, .35f, 1f, .9f),
            showControllers,
        )
    }
    val body = xr.bodyState()
    reportSection("Body · active=${body?.isActive ?: false} · raw trackers=${xr.bodyTrackerCount()}")
    val bodyTint = vec(.8f, 1f, .85f, .9f)
    for (j in BodyJoint.entries) joint(j.name, body?.get(j), bodyTint, showBody)
    for ((a, b) in BodyGeometry.CONNECTIONS) if (showBody) segment(body?.get(a), body?.get(b), bodyTint)

    // WiVRn reports lower-arm joints, not a complete arm skeleton. Join each reported
    // lower arm to the corresponding live wrist; a valid controller grip is a fallback.
    if (showBody)
        for (hand in Hand.entries) {
            val arm = if (hand == Hand.LEFT) BodyJoint.LEFT_ARM_LOWER else BodyJoint.RIGHT_ARM_LOWER
            val handState = xr.handState(hand)
            val wrist = if (showHands && handState?.isActive == true) handState[HandJoint.WRIST] else null
            val controller = xr.controllerState(hand)
            val endpoint =
                if (wrist?.positionValid == true) wrist
                else if (showControllers && controller.poseValid)
                    JointPose(controller.position, controller.rotation, 0f, true, true)
                else null
            segment(body?.get(arm), endpoint, bodyTint)
        }
    for (i in 0 until xr.bodyTrackerCount()) joint(
        "raw tracker $i",
        xr.bodyTrackerPose(i),
        vec(1f, .2f, .6f, .8f),
        showBody && body?.trackedJoints.isNullOrEmpty(),
    )
    updateBodyQueries()
    for (i in used until trackingMarks.size) trackingMarks[i][2].w = 0f
    val now = System.nanoTime()
    if (now - lastReport > 250_000_000L) {
        lastReport = now
        markdown(
            "query result",
            """
            ### Body queries

            Pink: query point. Green: nearest skeleton/surface point.
            The query follows the right palm or controller, with a head-forward fallback.

            | Query | Result |
            |---|---|
            | World point | ${xyz(queryWorld)} |
            | Nearest reported joint | ${queryJoint?.joint ?: "unavailable"} |
            | Skeleton/surface distance | ${queryHit?.distance?.f3 ?: "unavailable"} m |
            | Signed primitive distance | ${queryHit?.signedDistance?.f3 ?: "unavailable"} m |
            | Body segment | ${queryHit?.segment ?: "none"} |
            | Head selected | ${queryHit?.head ?: false} |

            Negative signed distance means inside the selected primitive.
            Cylinders and the sphere are independent surfaces; overlapping end caps remain queryable.
        """,
            width = 650,
        )
        markdown(
            "tracking status",
            """
            ## Live WiVRn observations

            $used draw marks · XR ticks ${xr.xrFrameCounter} · scene submissions ${renderer.submittedSceneFrames}.

            Last render failure: ${mdEscape(renderer.lastRenderFailure ?: "none")}.

            Blue = left hand; orange = right; pale green = body; purple = controllers; pink = unmapped trackers.
            Local axes: red X, green Y, blue Z. Connections are guides between reported joints, not additional tracked bones.

            Raw positions below are in world-space metres. Inspection only offsets the drawing.
            Invalid positions are hidden; Quest 3 supplies no eye tracking.

            ${rows.joinToString("\n")}
        """,
            width = 960,
            open = false,
        )
    }
}

// Keep the render loop and sampling callback in one owner.
stereo.beforeScene["body-one-tracking"] = updateTracking
loop {
    renderer.windows.forEach { it.pollEvents() }
    renderer.render()
    frame()
}
    .end { stereo.beforeScene.remove("body-one-tracking", updateTracking) }
