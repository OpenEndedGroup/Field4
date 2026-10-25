import afield.graphics.vulkan.Renderer
import afield.graphics.vulkan.util.Window
import afield.graphics.scene.fabricated.StandardRenderer
var Box.bundle by Inheritance<StandardRenderer.Bundle>() /**/
val Once.bundle by Onceing<StandardRenderer.Bundle>() /**/
val Why.bundle by Whying() /**/
var Box.keys by Inheritance<afield.animation.keyframes.KeyframeScope>() /**/
val Once.keys by Onceing<afield.animation.keyframes.KeyframeScope>() /**/
val Why.keys by Whying() /**/
var Box.window by Inheritance<Window>() /*from:ks_renderer*/ /**/
val Once.window by Onceing<Window>() /*from:ks_renderer*/ /**/
val Why.window by Whying() /*from:ks_renderer*/ /**/
var Box.renderer by Inheritance<Renderer>() /*from:ks_renderer*/ /**/
val Once.renderer by Onceing<Renderer>() /*from:ks_renderer*/ /**/
val Why.renderer by Whying() /*from:ks_renderer*/ /**/
