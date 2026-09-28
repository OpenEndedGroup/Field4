import afield.four.Renderer
import afield.four.util.Window
var Box.chain by Inheritance<afield.four.fabricated.FabricateBuiltChain>() /**/
val Once.chain by Onceing<afield.four.fabricated.FabricateBuiltChain>() /**/
var Box.bundle by Inheritance<afield.four.fabricated.StandardRenderer.Bundle>() /*from:rp_bundle*/ /**/
val Once.bundle by Onceing<afield.four.fabricated.StandardRenderer.Bundle>() /*from:rp_bundle*/ /**/
var Box.balls by Inheritance<MutableList<afield.four.fabricated.StandardRenderer.Bundle.PullingMeshPair>>() /*from:rp_bundle*/ /**/
val Once.balls by Onceing<MutableList<afield.four.fabricated.StandardRenderer.Bundle.PullingMeshPair>>() /*from:rp_bundle*/ /**/
var Box.window by Inheritance<Window>() /*from:rp_renderer*/ /**/
val Once.window by Onceing<Window>() /*from:rp_renderer*/ /**/
var Box.renderer by Inheritance<Renderer>() /*from:rp_renderer*/ /**/
val Once.renderer by Onceing<Renderer>() /*from:rp_renderer*/ /**/
