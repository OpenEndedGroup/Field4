import afield.graphics.vulkan.Renderer
import afield.graphics.vulkan.util.Window
import afield.graphics.scene.fabricated.StandardRenderer
var Box.tree by Inheritance<afield.graphics.scene.fabricated.FabricateBuiltChain>() /**/
val Once.tree by Onceing<afield.graphics.scene.fabricated.FabricateBuiltChain>() /**/
val Why.tree by Whying() /**/
var Box.bundle by Inheritance<StandardRenderer.Bundle>() /*from:mq_bundle*/ /**/
val Once.bundle by Onceing<StandardRenderer.Bundle>() /*from:mq_bundle*/ /**/
val Why.bundle by Whying() /*from:mq_bundle*/ /**/
var Box.window by Inheritance<Window>() /*from:mq_renderer*/ /**/
val Once.window by Onceing<Window>() /*from:mq_renderer*/ /**/
val Why.window by Whying() /*from:mq_renderer*/ /**/
var Box.renderer by Inheritance<Renderer>() /*from:mq_renderer*/ /**/
val Once.renderer by Onceing<Renderer>() /*from:mq_renderer*/ /**/
val Why.renderer by Whying() /*from:mq_renderer*/ /**/
