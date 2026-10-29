import afield.graphics.scene.fabricated.StandardRenderer
import afield.graphics.vulkan.util.Window
import afield.graphics.vulkan.Renderer
var Box.fluid by Inheritance<afield.graphics.scene.fabricated.FabricateBuiltChain>() /**/
val Once.fluid by Onceing<afield.graphics.scene.fabricated.FabricateBuiltChain>() /**/
val Why.fluid by Whying() /**/
var Box.bundle by Inheritance<StandardRenderer.Bundle>() /*from:sim_bundle*/ /**/
val Once.bundle by Onceing<StandardRenderer.Bundle>() /*from:sim_bundle*/ /**/
val Why.bundle by Whying() /*from:sim_bundle*/ /**/
var Box.window by Inheritance<Window>() /*from:sim_renderer*/ /**/
val Once.window by Onceing<Window>() /*from:sim_renderer*/ /**/
val Why.window by Whying() /*from:sim_renderer*/ /**/
var Box.renderer by Inheritance<Renderer>() /*from:sim_renderer*/ /**/
val Once.renderer by Onceing<Renderer>() /*from:sim_renderer*/ /**/
val Why.renderer by Whying() /*from:sim_renderer*/ /**/
