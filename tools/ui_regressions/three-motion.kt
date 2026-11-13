// Declared motions in the standard viewer: a door swings about its hinge on the timeline, glows red where its
// clearance track is under the rule, and draws its swept tracks; a drawer slides. No viewer tool is involved.
import afield.geometry.kinematics.RigidMotion
import afield.geometry.mesh.boxMesh

var motionFrame = 0
fun motionScene(wall: Double) {
    val swing = RigidMotion.rotation(vec(0.0, 0.0, 0.0), vec(0.0, 0.0, 1.0), 0.0, 90.0)
    val values = (0..18).map { it * 5.0 }
    // A wall at x = wall: the door's free edge (0.9 from the hinge) comes closest at 0°.
    val gaps = values.map { wall - 0.9 * Math.cos(Math.toRadians(it)) }
    three(name = "motion", width = 720, height = 440) {
        mesh(boxMesh(0.9, 0.04, 2.0).translated(0.45, 0.0, 1.0), id = "door", wireframe = false, layer = ["Doors"])
        mesh(boxMesh(0.04, 1.2, 2.0).translated(wall, 0.4, 1.0), id = "wall", wireframe = false)
        mesh(boxMesh(0.5, 0.4, 0.2).translated(-1.0, 0.0, 0.5), id = "drawer", wireframe = false)
        motion("door", motion = swing, track = ThreeMotionTrack(values, gaps, labels = values.map { "wall" }, unit = "m",
            bands = [ThreeTintBand.under(0.3, "under 0.3 m"), ThreeTintBand.near(0.5, "near")],
            points = ThreeMotionTrack.points(vec(0.9, 0.0, 0.05))))
        motion("drawer", motion = RigidMotion.slide(vec(0.0, -1.0, 0.0), 0.0, 0.35), label = "drawer")
        timeline(periodSeconds = 4.0)
        camera(yaw = 30.0, pitch = 50.0, upAxis = ThreeUpAxis.Z)
        note("frame $motionFrame")
    }
}
motionScene(1.1)
slider("wall", 1.0, 1.3, 0.01, 1.1) { motionFrame++; motionScene(it) }
