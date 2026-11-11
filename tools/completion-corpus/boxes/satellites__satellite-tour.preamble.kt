import afield.simulation.orbits.SatelliteCatalogue
import afield.simulation.orbits.SkyClock
import afield.graphics.vulkan.util.Camera
import afield.graphics.vulkan.buffers.*
import afield.document.context.*
import afield.graphics.render.*
import afield.graphics.scene.*
import afield.graphics.shader.*
import arrow.core.left
import arrow.core.right
import java.nio.*
import afield.graphics.vulkan.util.Window
import afield.graphics.vulkan.Renderer
var Box.sky by Inheritance<SatelliteCatalogue>() /*from:satellite-earth*/ /**/
val Once.sky by Onceing<SatelliteCatalogue>() /*from:satellite-earth*/ /**/
val Why.sky by Whying() /*from:satellite-earth*/ /**/
var Box.clock by Inheritance<SkyClock>() /*from:satellite-earth*/ /**/
val Once.clock by Onceing<SkyClock>() /*from:satellite-earth*/ /**/
val Why.clock by Whying() /*from:satellite-earth*/ /**/
var Box.camera by Inheritance<Camera>() /*from:satellite-earth*/ /**/
val Once.camera by Onceing<Camera>() /*from:satellite-earth*/ /**/
val Why.camera by Whying() /*from:satellite-earth*/ /**/
var Box.window by Inheritance<Window>() /*from:agent_graphics_context*/ /**/
val Once.window by Onceing<Window>() /*from:agent_graphics_context*/ /**/
val Why.window by Whying() /*from:agent_graphics_context*/ /**/
var Box.renderer by Inheritance<Renderer>() /*from:agent_graphics_context*/ /**/
val Once.renderer by Onceing<Renderer>() /*from:agent_graphics_context*/ /**/
val Why.renderer by Whying() /*from:agent_graphics_context*/ /**/
