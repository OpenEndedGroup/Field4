import afield.four.Renderer
import afield.four.util.Window
var Box.chain by Inheritance<afield.four.fabricated.FabricateBuiltChain>() /**/
val Once.chain by Onceing<afield.four.fabricated.FabricateBuiltChain>() /**/
var Box.bundle by Inheritance<afield.four.fabricated.StandardRenderer.Bundle>() /*from:pc_scene*/ /**/
val Once.bundle by Onceing<afield.four.fabricated.StandardRenderer.Bundle>() /*from:pc_scene*/ /**/
var Box.window by Inheritance<Window>() /*from:pc_renderer*/ /**/
val Once.window by Onceing<Window>() /*from:pc_renderer*/ /**/
var Box.renderer by Inheritance<Renderer>() /*from:pc_renderer*/ /**/
val Once.renderer by Onceing<Renderer>() /*from:pc_renderer*/ /**/
