import afield.graphics.vulkan.buffers.*
import afield.document.context.*
import afield.graphics.render.*
import afield.graphics.scene.*
import afield.graphics.shader.*
import arrow.core.left
import arrow.core.right
import java.nio.*
import afield.graphics.scene.video.StereoVideoSurface
import afield.ui.output.ControlValueHandle
import afield.graphics.vulkan.util.Window
import afield.graphics.vulkan.Renderer
import afield.graphics.vulkan.targets.MultiviewStereoTarget
var Box.movie by Inheritance<StereoVideoSurface>() /*from:video1_screen*/ /**/
val Once.movie by Onceing<StereoVideoSurface>() /*from:video1_screen*/ /**/
val Why.movie by Whying() /*from:video1_screen*/ /**/
var Box.aspectControl by Inheritance<ControlValueHandle<Double>>() /*from:video1_screen*/ /**/
val Once.aspectControl by Onceing<ControlValueHandle<Double>>() /*from:video1_screen*/ /**/
val Why.aspectControl by Whying() /*from:video1_screen*/ /**/
var Box.window by Inheritance<Window>() /*from:video1_context*/ /**/
val Once.window by Onceing<Window>() /*from:video1_context*/ /**/
val Why.window by Whying() /*from:video1_context*/ /**/
var Box.renderer by Inheritance<Renderer>() /*from:video1_context*/ /**/
val Once.renderer by Onceing<Renderer>() /*from:video1_context*/ /**/
val Why.renderer by Whying() /*from:video1_context*/ /**/
var Box.stereo by Inheritance<MultiviewStereoTarget>() /*from:video1_context*/ /**/
val Once.stereo by Onceing<MultiviewStereoTarget>() /*from:video1_context*/ /**/
val Why.stereo by Whying() /*from:video1_context*/ /**/
