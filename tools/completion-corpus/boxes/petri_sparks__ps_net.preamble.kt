import afield.simulation.petri.PetriNet
import afield.simulation.petri.PetriRun
import afield.graphics.vulkan.util.Window
import afield.graphics.vulkan.Renderer
var Box.sparkNet by Inheritance<PetriNet>() /**/
val Once.sparkNet by Onceing<PetriNet>() /**/
val Why.sparkNet by Whying() /**/
var Box.sparkRun by Inheritance<PetriRun>() /**/
val Once.sparkRun by Onceing<PetriRun>() /**/
val Why.sparkRun by Whying() /**/
var Box.window by Inheritance<Window>() /*from:ps_renderer*/ /**/
val Once.window by Onceing<Window>() /*from:ps_renderer*/ /**/
val Why.window by Whying() /*from:ps_renderer*/ /**/
var Box.renderer by Inheritance<Renderer>() /*from:ps_renderer*/ /**/
val Once.renderer by Onceing<Renderer>() /*from:ps_renderer*/ /**/
val Why.renderer by Whying() /*from:ps_renderer*/ /**/
