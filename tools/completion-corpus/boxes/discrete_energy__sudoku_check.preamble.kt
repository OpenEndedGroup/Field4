import afield.graphics.vulkan.buffers.*
import afield.document.context.*
import afield.graphics.render.*
import afield.graphics.scene.*
import afield.graphics.shader.*
import arrow.core.left
import arrow.core.right
import afield.graphics.vulkan.buffers.FrameBufferGroup
import afield.simulation.agents.BondedClock
import afield.math.energy.DiscreteEnergy
import afield.graphics.vulkan.util.Window
import afield.graphics.vulkan.Renderer
var Box.energyGpuState by Inheritance<FrameBufferGroup>() /*from:sudoku_gpu*/ /**/
val Once.energyGpuState by Onceing<FrameBufferGroup>() /*from:sudoku_gpu*/ /**/
val Why.energyGpuState by Whying() /*from:sudoku_gpu*/ /**/
var Box.energyClock by Inheritance<BondedClock>() /*from:sudoku_gpu*/ /**/
val Once.energyClock by Onceing<BondedClock>() /*from:sudoku_gpu*/ /**/
val Why.energyClock by Whying() /*from:sudoku_gpu*/ /**/
var Box.energyUpload by Inheritance<() -> Unit>() /*from:sudoku_gpu*/ /**/
val Once.energyUpload by Onceing<() -> Unit>() /*from:sudoku_gpu*/ /**/
val Why.energyUpload by Whying() /*from:sudoku_gpu*/ /**/
var Box.sudokuEnergy by Inheritance<DiscreteEnergy>() /*from:sudoku_rules*/ /**/
val Once.sudokuEnergy by Onceing<DiscreteEnergy>() /*from:sudoku_rules*/ /**/
val Why.sudokuEnergy by Whying() /*from:sudoku_rules*/ /**/
var Box.sudokuBoard by Inheritance<(EnergyState, String) -> String>() /*from:sudoku_rules*/ /**/
val Once.sudokuBoard by Onceing<(EnergyState, String) -> String>() /*from:sudoku_rules*/ /**/
val Why.sudokuBoard by Whying() /*from:sudoku_rules*/ /**/
var Box.window by Inheritance<Window>() /*from:energy_renderer*/ /**/
val Once.window by Onceing<Window>() /*from:energy_renderer*/ /**/
val Why.window by Whying() /*from:energy_renderer*/ /**/
var Box.renderer by Inheritance<Renderer>() /*from:energy_renderer*/ /**/
val Once.renderer by Onceing<Renderer>() /*from:energy_renderer*/ /**/
val Why.renderer by Whying() /*from:energy_renderer*/ /**/
