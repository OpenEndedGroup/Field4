import afield.math.energy.DiscreteEnergy
import afield.graphics.vulkan.util.Window
import afield.graphics.vulkan.Renderer
var Box.sudokuEnergy by Inheritance<DiscreteEnergy>() /**/
val Once.sudokuEnergy by Onceing<DiscreteEnergy>() /**/
val Why.sudokuEnergy by Whying() /**/
var Box.sudokuBoard by Inheritance<(EnergyState, String) -> String>() /**/
val Once.sudokuBoard by Onceing<(EnergyState, String) -> String>() /**/
val Why.sudokuBoard by Whying() /**/
var Box.window by Inheritance<Window>() /*from:energy_renderer*/ /**/
val Once.window by Onceing<Window>() /*from:energy_renderer*/ /**/
val Why.window by Whying() /*from:energy_renderer*/ /**/
var Box.renderer by Inheritance<Renderer>() /*from:energy_renderer*/ /**/
val Once.renderer by Onceing<Renderer>() /*from:energy_renderer*/ /**/
val Why.renderer by Whying() /*from:energy_renderer*/ /**/
