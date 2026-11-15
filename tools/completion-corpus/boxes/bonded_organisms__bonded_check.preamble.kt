import afield.simulation.agents.BondedWorld
import afield.graphics.vulkan.buffers.*
import afield.document.context.*
import afield.graphics.render.*
import afield.graphics.scene.*
import afield.graphics.shader.*
import arrow.core.left
import arrow.core.right
import afield.graphics.vulkan.buffers.FrameBufferGroup
import afield.simulation.agents.BondedClock
import afield.graphics.vulkan.util.Window
import afield.graphics.vulkan.Renderer
var Box.bonded by Inheritance<BondedWorld>() /*from:bonded_world*/ /**/
val Once.bonded by Onceing<BondedWorld>() /*from:bonded_world*/ /**/
val Why.bonded by Whying() /*from:bonded_world*/ /**/
var Box.bondedFactory by Inheritance<() -> BondedWorld>() /*from:bonded_world*/ /**/
val Once.bondedFactory by Onceing<() -> BondedWorld>() /*from:bonded_world*/ /**/
val Why.bondedFactory by Whying() /*from:bonded_world*/ /**/
var Box.bondedCells by Inheritance<FrameBufferGroup>() /*from:bonded_world*/ /**/
val Once.bondedCells by Onceing<FrameBufferGroup>() /*from:bonded_world*/ /**/
val Why.bondedCells by Whying() /*from:bonded_world*/ /**/
var Box.bondedBonds by Inheritance<FrameBufferGroup>() /*from:bonded_world*/ /**/
val Once.bondedBonds by Onceing<FrameBufferGroup>() /*from:bonded_world*/ /**/
val Why.bondedBonds by Whying() /*from:bonded_world*/ /**/
var Box.bondedClock by Inheritance<BondedClock>() /*from:bonded_world*/ /**/
val Once.bondedClock by Onceing<BondedClock>() /*from:bonded_world*/ /**/
val Why.bondedClock by Whying() /*from:bonded_world*/ /**/
var Box.bondedUpload by Inheritance<() -> Unit>() /*from:bonded_world*/ /**/
val Once.bondedUpload by Onceing<() -> Unit>() /*from:bonded_world*/ /**/
val Why.bondedUpload by Whying() /*from:bonded_world*/ /**/
var Box.window by Inheritance<Window>() /*from:bonded_renderer*/ /**/
val Once.window by Onceing<Window>() /*from:bonded_renderer*/ /**/
val Why.window by Whying() /*from:bonded_renderer*/ /**/
var Box.renderer by Inheritance<Renderer>() /*from:bonded_renderer*/ /**/
val Once.renderer by Onceing<Renderer>() /*from:bonded_renderer*/ /**/
val Why.renderer by Whying() /*from:bonded_renderer*/ /**/
