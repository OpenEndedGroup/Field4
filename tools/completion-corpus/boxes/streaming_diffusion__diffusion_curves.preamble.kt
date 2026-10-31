import afield.media.video.diffusion.SpinningDiffusionDemo
import afield.graphics.vulkan.util.Window
import afield.graphics.vulkan.Renderer
var Box.diffusionDemo by Inheritance<SpinningDiffusionDemo>() /**/
val Once.diffusionDemo by Onceing<SpinningDiffusionDemo>() /**/
val Why.diffusionDemo by Whying() /**/
var Box.window by Inheritance<Window>() /*from:diffusion_renderer*/ /**/
val Once.window by Onceing<Window>() /*from:diffusion_renderer*/ /**/
val Why.window by Whying() /*from:diffusion_renderer*/ /**/
var Box.renderer by Inheritance<Renderer>() /*from:diffusion_renderer*/ /**/
val Once.renderer by Onceing<Renderer>() /*from:diffusion_renderer*/ /**/
val Why.renderer by Whying() /*from:diffusion_renderer*/ /**/
