import afield.graphics.scene.body.BodyGeometry
import afield.graphics.vulkan.buffers.*
import afield.document.context.*
import afield.graphics.render.*
import afield.graphics.scene.*
import afield.graphics.shader.*
import arrow.core.left
import arrow.core.right
import java.nio.*
import afield.graphics.vulkan.util.Window
import afield.graphics.vulkan.Renderer
import afield.graphics.vulkan.targets.MultiviewStereoTarget
var Box.trackedBody by Inheritance<BodyGeometry>() /**/
val Once.trackedBody by Onceing<BodyGeometry>() /**/
val Why.trackedBody by Whying() /**/
var Box.trackingMarks by Inheritance<MutableList<Array<Vector4f>>>() /*from:body1_drawing*/ /**/
val Once.trackingMarks by Onceing<MutableList<Array<Vector4f>>>() /*from:body1_drawing*/ /**/
val Why.trackingMarks by Whying() /*from:body1_drawing*/ /**/
var Box.window by Inheritance<Window>() /*from:body1_context*/ /**/
val Once.window by Onceing<Window>() /*from:body1_context*/ /**/
val Why.window by Whying() /*from:body1_context*/ /**/
var Box.renderer by Inheritance<Renderer>() /*from:body1_context*/ /**/
val Once.renderer by Onceing<Renderer>() /*from:body1_context*/ /**/
val Why.renderer by Whying() /*from:body1_context*/ /**/
var Box.stereo by Inheritance<MultiviewStereoTarget>() /*from:body1_context*/ /**/
val Once.stereo by Onceing<MultiviewStereoTarget>() /*from:body1_context*/ /**/
val Why.stereo by Whying() /*from:body1_context*/ /**/
