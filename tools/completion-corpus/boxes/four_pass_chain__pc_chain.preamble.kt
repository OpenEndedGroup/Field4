import afield.four.Renderer
import afield.four.util.Window
import afield.four.fabricated.StandardRenderer
import afield.four.fabricated.FabricateBuiltChain
var Box.chain by Inheritance<FabricateBuiltChain>() /**/
val Once.chain by Onceing<FabricateBuiltChain>() /**/
val Why.chain by Whying() /**/
var Box.bundle by Inheritance<StandardRenderer.Bundle>() /*from:pc_scene*/ /**/
val Once.bundle by Onceing<StandardRenderer.Bundle>() /*from:pc_scene*/ /**/
val Why.bundle by Whying() /*from:pc_scene*/ /**/
var Box.window by Inheritance<Window>() /*from:pc_renderer*/ /**/
val Once.window by Onceing<Window>() /*from:pc_renderer*/ /**/
val Why.window by Whying() /*from:pc_renderer*/ /**/
var Box.renderer by Inheritance<Renderer>() /*from:pc_renderer*/ /**/
val Once.renderer by Onceing<Renderer>() /*from:pc_renderer*/ /**/
val Why.renderer by Whying() /*from:pc_renderer*/ /**/
