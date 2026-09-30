import afield.four.Renderer
import afield.four.util.Window
import afield.four.synth.PatSynthLaunch
import afield.four.buffers.FrameBufferGroup
import arrow.core.right
import arrow.core.left
import afield.four.modules.*
import afield.four.buffers.*
/*start*/
/*compute-structure-type:Seeds:Seeds*/
class Seeds(
    members: List<Introspected.Member> = SeedsLayoutMembers(),
    baseOffset: Int = 0,
    pathPrefix: String = "",
    root: Structure? = null
) : Structure(members, baseOffset, pathPrefix, root) {
    init { setMembers(members) }
    var a by Structure.FromStructure<afield.stdlib.linalg.Vector4f>()
}
fun SeedsByteSize(): Long = 16L
fun SeedsLayoutMembers(): List<Introspected.Member> =
    listOf(
        Introspected.Member("a", 0, PrimitiveType.vec4.left(), 16, false),
    )
fun HasBuffer.asSeeds(): Seeds =
    asStructure(Seeds(), SeedsLayoutMembers())
fun BufferParameters.asSeeds(): Seeds =
    buffer.asSeeds()
/*end*/
/*start*/
/*compute-structure-type:Out:Out*/
class Out(
    members: List<Introspected.Member> = OutLayoutMembers(),
    baseOffset: Int = 0,
    pathPrefix: String = "",
    root: Structure? = null
) : Structure(members, baseOffset, pathPrefix, root) {
    init { setMembers(members) }
    var a by Structure.FromStructure<afield.stdlib.linalg.Vector4f>()
}
fun OutByteSize(): Long = 16L
fun OutLayoutMembers(): List<Introspected.Member> =
    listOf(
        Introspected.Member("a", 0, PrimitiveType.vec4.left(), 16, false),
    )
fun HasBuffer.asOut(): Out =
    asStructure(Out(), OutLayoutMembers())
fun BufferParameters.asOut(): Out =
    buffer.asOut()
/*end*/
/*compute-pc-structure:seeds*/ var Structure.seeds by Structure.FromStructure<Any>() // written once: one random phase and radius per particle
/*compute-pc-binding:seeds*/ var ComputeMesh.seeds by ComputeMesh.BoundFromStructure<afield.four.buffers.HasBuffer>() // written once: one random phase and radius per particle
/*compute-pc-binding-group:seeds*/ var ComputeMeshGroup.seeds by ComputeMeshGroup.BoundFromStructure<afield.four.buffers.HasBuffer>() // written once: one random phase and radius per particle
/*compute-pc-structure:outData*/ var Structure.outData by Structure.FromStructure<Any>() // this frame's positions, read by the draw box
/*compute-pc-binding:outData*/ var ComputeMesh.outData by ComputeMesh.BoundFromStructure<afield.four.buffers.HasBuffer>() // this frame's positions, read by the draw box
/*compute-pc-binding-group:outData*/ var ComputeMeshGroup.outData by ComputeMeshGroup.BoundFromStructure<afield.four.buffers.HasBuffer>() // this frame's positions, read by the draw box
/*compute-pc-structure:time*/ var Structure.time by Structure.FromStructure<Any>() // seconds
/*compute-pc-binding:time*/ var ComputeMesh.time by ComputeMesh.BoundFromStructure<Float>() // seconds
/*compute-pc-binding-group:time*/ var ComputeMeshGroup.time by ComputeMeshGroup.BoundFromStructure<Float>() // seconds
/*compute-pc-structure:band0*/ var Structure.band0 by Structure.FromStructure<Any>() // 70 Hz — the kick
/*compute-pc-binding:band0*/ var ComputeMesh.band0 by ComputeMesh.BoundFromStructure<Float>() // 70 Hz — the kick
/*compute-pc-binding-group:band0*/ var ComputeMeshGroup.band0 by ComputeMeshGroup.BoundFromStructure<Float>() // 70 Hz — the kick
/*compute-pc-structure:band1*/ var Structure.band1 by Structure.FromStructure<Any>() // 340 Hz
/*compute-pc-binding:band1*/ var ComputeMesh.band1 by ComputeMesh.BoundFromStructure<Float>() // 340 Hz
/*compute-pc-binding-group:band1*/ var ComputeMeshGroup.band1 by ComputeMeshGroup.BoundFromStructure<Float>() // 340 Hz
/*compute-pc-structure:band2*/ var Structure.band2 by Structure.FromStructure<Any>() // 1.6 kHz — the square stab's harmonics
/*compute-pc-binding:band2*/ var ComputeMesh.band2 by ComputeMesh.BoundFromStructure<Float>() // 1.6 kHz — the square stab's harmonics
/*compute-pc-binding-group:band2*/ var ComputeMeshGroup.band2 by ComputeMeshGroup.BoundFromStructure<Float>() // 1.6 kHz — the square stab's harmonics
/*compute-pc-structure:band3*/ var Structure.band3 by Structure.FromStructure<Any>() // 8 kHz — the hats
/*compute-pc-binding:band3*/ var ComputeMesh.band3 by ComputeMesh.BoundFromStructure<Float>() // 8 kHz — the hats
/*compute-pc-binding-group:band3*/ var ComputeMeshGroup.band3 by ComputeMeshGroup.BoundFromStructure<Float>() // 8 kHz — the hats
/*compute-pc-structure:count*/ var Structure.count by Structure.FromStructure<Any>()
/*compute-pc-binding:count*/ var ComputeMesh.count by ComputeMesh.BoundFromStructure<UInt>()
/*compute-pc-binding-group:count*/ var ComputeMeshGroup.count by ComputeMeshGroup.BoundFromStructure<UInt>()
var Box.outputPositions by Inheritance<FrameBufferGroup>() /**/
val Once.outputPositions by Onceing<FrameBufferGroup>() /**/
val Why.outputPositions by Whying() /**/
var Box.bandEnergy by Inheritance<DoubleArray>() /*from:pp_audio*/ /**/
val Once.bandEnergy by Onceing<DoubleArray>() /*from:pp_audio*/ /**/
val Why.bandEnergy by Whying() /*from:pp_audio*/ /**/
var Box.patternSynth by Inheritance<PatSynthLaunch>() /*from:pp_audio*/ /**/
val Once.patternSynth by Onceing<PatSynthLaunch>() /*from:pp_audio*/ /**/
val Why.patternSynth by Whying() /*from:pp_audio*/ /**/
var Box.window by Inheritance<Window>() /*from:pp_renderer*/ /**/
val Once.window by Onceing<Window>() /*from:pp_renderer*/ /**/
val Why.window by Whying() /*from:pp_renderer*/ /**/
var Box.renderer by Inheritance<Renderer>() /*from:pp_renderer*/ /**/
val Once.renderer by Onceing<Renderer>() /*from:pp_renderer*/ /**/
val Why.renderer by Whying() /*from:pp_renderer*/ /**/
