import afield.graphics.scene.reading.ReadingDiagramPlayer
import afield.graphics.vulkan.util.Window
import afield.graphics.vulkan.Renderer
import afield.graphics.vulkan.targets.MultiviewStereoTarget
var Box.sumPlayer by Inheritance<ReadingDiagramPlayer>() /**/
val Once.sumPlayer by Onceing<ReadingDiagramPlayer>() /**/
val Why.sumPlayer by Whying() /**/
var Box.window by Inheritance<Window>() /*from:pick_context*/ /**/
val Once.window by Onceing<Window>() /*from:pick_context*/ /**/
val Why.window by Whying() /*from:pick_context*/ /**/
var Box.renderer by Inheritance<Renderer>() /*from:pick_context*/ /**/
val Once.renderer by Onceing<Renderer>() /*from:pick_context*/ /**/
val Why.renderer by Whying() /*from:pick_context*/ /**/
var Box.stereo by Inheritance<MultiviewStereoTarget>() /*from:pick_context*/ /**/
val Once.stereo by Onceing<MultiviewStereoTarget>() /*from:pick_context*/ /**/
val Why.stereo by Whying() /*from:pick_context*/ /**/
var Box.showStereoPair by Inheritance<Boolean>() /*from:pick_context*/ /**/
val Once.showStereoPair by Onceing<Boolean>() /*from:pick_context*/ /**/
val Why.showStereoPair by Whying() /*from:pick_context*/ /**/
