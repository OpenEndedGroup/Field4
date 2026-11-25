import afield.graphics.vulkan.buffers.*
import afield.document.context.*
import afield.graphics.render.*
import afield.graphics.scene.*
import afield.graphics.shader.*
import arrow.core.left
import arrow.core.right
import afield.graphics.vulkan.buffers.FrameBufferGroup
import afield.graphics.vulkan.util.Window
import afield.graphics.vulkan.Renderer
var Box.ofStateA by Inheritance<FrameBufferGroup>() /*from:ofg_sim*/ /**/
val Once.ofStateA by Onceing<FrameBufferGroup>() /*from:ofg_sim*/ /**/
val Why.ofStateA by Whying() /*from:ofg_sim*/ /**/
var Box.ofStateB by Inheritance<FrameBufferGroup>() /*from:ofg_sim*/ /**/
val Once.ofStateB by Onceing<FrameBufferGroup>() /*from:ofg_sim*/ /**/
val Why.ofStateB by Whying() /*from:ofg_sim*/ /**/
var Box.ofWall by Inheritance<FrameBufferGroup>() /*from:ofg_sim*/ /**/
val Once.ofWall by Onceing<FrameBufferGroup>() /*from:ofg_sim*/ /**/
val Why.ofWall by Whying() /*from:ofg_sim*/ /**/
var Box.ofWidth by Inheritance<Int>() /*from:ofg_sim*/ /**/
val Once.ofWidth by Onceing<Int>() /*from:ofg_sim*/ /**/
val Why.ofWidth by Whying() /*from:ofg_sim*/ /**/
var Box.ofHeight by Inheritance<Int>() /*from:ofg_sim*/ /**/
val Once.ofHeight by Onceing<Int>() /*from:ofg_sim*/ /**/
val Why.ofHeight by Whying() /*from:ofg_sim*/ /**/
var Box.addOutline by Inheritance<(FLine) -> Unit>() /*from:ofg_sim*/ /**/
val Once.addOutline by Onceing<(FLine) -> Unit>() /*from:ofg_sim*/ /**/
val Why.addOutline by Whying() /*from:ofg_sim*/ /**/
var Box.window by Inheritance<Window>() /*from:ofg_renderer*/ /**/
val Once.window by Onceing<Window>() /*from:ofg_renderer*/ /**/
val Why.window by Whying() /*from:ofg_renderer*/ /**/
var Box.renderer by Inheritance<Renderer>() /*from:ofg_renderer*/ /**/
val Once.renderer by Onceing<Renderer>() /*from:ofg_renderer*/ /**/
val Why.renderer by Whying() /*from:ofg_renderer*/ /**/
