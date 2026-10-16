import afield.graphics.vulkan.Renderer
import afield.graphics.vulkan.util.Window
import afield.graphics.vulkan.targets.RenderToTexture
import afield.graphics.vulkan.targets.ComputeTarget
import afield.ui.controls.EffectiveParameter
import afield.runtime.lifecycle.SimulationReset
import afield.graphics.vulkan.buffers.FrameBufferGroup
var Box.flowA by ReactiveInheritance<FrameBufferGroup>(Reactive.DOWN) /**/
val Once.flowA by Onceing<FrameBufferGroup>() /**/
val Why.flowA by Whying() /**/
var Box.flowB by ReactiveInheritance<FrameBufferGroup>(Reactive.DOWN) /**/
val Once.flowB by Onceing<FrameBufferGroup>() /**/
val Why.flowB by Whying() /**/
var Box.advected by ReactiveInheritance<FrameBufferGroup>(Reactive.DOWN) /**/
val Once.advected by Onceing<FrameBufferGroup>() /**/
val Why.advected by Whying() /**/
var Box.divergence by ReactiveInheritance<FrameBufferGroup>(Reactive.DOWN) /**/
val Once.divergence by Onceing<FrameBufferGroup>() /**/
val Why.divergence by Whying() /**/
var Box.pressureA by ReactiveInheritance<FrameBufferGroup>(Reactive.DOWN) /**/
val Once.pressureA by Onceing<FrameBufferGroup>() /**/
val Why.pressureA by Whying() /**/
var Box.pressureB by ReactiveInheritance<FrameBufferGroup>(Reactive.DOWN) /**/
val Once.pressureB by Onceing<FrameBufferGroup>() /**/
val Why.pressureB by Whying() /**/
var Box.particleA by ReactiveInheritance<FrameBufferGroup>(Reactive.DOWN) /**/
val Once.particleA by Onceing<FrameBufferGroup>() /**/
val Why.particleA by Whying() /**/
var Box.particleB by ReactiveInheritance<FrameBufferGroup>(Reactive.DOWN) /**/
val Once.particleB by Onceing<FrameBufferGroup>() /**/
val Why.particleB by Whying() /**/
var Box.tileHeaders by ReactiveInheritance<FrameBufferGroup>(Reactive.DOWN) /**/
val Once.tileHeaders by Onceing<FrameBufferGroup>() /**/
val Why.tileHeaders by Whying() /**/
var Box.cameraHistory by ReactiveInheritance<FrameBufferGroup>(Reactive.DOWN) /**/
val Once.cameraHistory by Onceing<FrameBufferGroup>() /**/
val Why.cameraHistory by Whying() /**/
var Box.itemHistory by ReactiveInheritance<FrameBufferGroup>(Reactive.DOWN) /**/
val Once.itemHistory by Onceing<FrameBufferGroup>() /**/
val Why.itemHistory by Whying() /**/
var Box.resetFlow by ReactiveInheritance<SimulationReset>(Reactive.DOWN) /**/
val Once.resetFlow by Onceing<SimulationReset>() /**/
val Why.resetFlow by Whying() /**/
var Box.force by ReactiveInheritance<EffectiveParameter>(Reactive.DOWN) /**/
val Once.force by Onceing<EffectiveParameter>() /**/
val Why.force by Whying() /**/
var Box.grainCell by ReactiveInheritance<EffectiveParameter>(Reactive.DOWN) /**/
val Once.grainCell by Onceing<EffectiveParameter>() /**/
val Why.grainCell by Whying() /**/
var Box.strokeTime by ReactiveInheritance<EffectiveParameter>(Reactive.DOWN) /**/
val Once.strokeTime by Onceing<EffectiveParameter>() /**/
val Why.strokeTime by Whying() /**/
var Box.flowLook by ReactiveInheritance<afield.math.linalg.Vec4>(Reactive.DOWN) /**/
val Once.flowLook by Onceing<afield.math.linalg.Vec4>() /**/
val Why.flowLook by Whying() /**/
var Box.flowClock by ReactiveInheritance<afield.math.linalg.Vec4>(Reactive.DOWN) /**/
val Once.flowClock by Onceing<afield.math.linalg.Vec4>() /**/
val Why.flowClock by Whying() /**/
var Box.flowEmitter by ReactiveInheritance<afield.math.linalg.Vec4>(Reactive.DOWN) /**/
val Once.flowEmitter by Onceing<afield.math.linalg.Vec4>() /**/
val Why.flowEmitter by Whying() /**/
var Box.flowSimulation by ReactiveInheritance<ComputeTarget>(Reactive.DOWN) /**/
val Once.flowSimulation by Onceing<ComputeTarget>() /**/
val Why.flowSimulation by Whying() /**/
var Box.flowStep by ReactiveInheritance<java.util.concurrent.atomic.AtomicLong>(Reactive.DOWN) /**/
val Once.flowStep by Onceing<java.util.concurrent.atomic.AtomicLong>() /**/
val Why.flowStep by Whying() /**/
var Box.travel by ReactiveInheritance<java.util.concurrent.atomic.AtomicBoolean>(Reactive.DOWN) /**/
val Once.travel by Onceing<java.util.concurrent.atomic.AtomicBoolean>() /**/
val Why.travel by Whying() /**/
var Box.flowWidth by ReactiveInheritance<EffectiveParameter>(Reactive.DOWN) /**/
val Once.flowWidth by Onceing<EffectiveParameter>() /**/
val Why.flowWidth by Whying() /**/
var Box.flowSurface by ReactiveInheritance<RenderToTexture>(Reactive.DOWN) /**/
val Once.flowSurface by Onceing<RenderToTexture>() /**/
val Why.flowSurface by Whying() /**/
var Box.window by Inheritance<Window>() /*from:flow_renderer*/ /**/
val Once.window by Onceing<Window>() /*from:flow_renderer*/ /**/
val Why.window by Whying() /*from:flow_renderer*/ /**/
var Box.renderer by Inheritance<Renderer>() /*from:flow_renderer*/ /**/
val Once.renderer by Onceing<Renderer>() /*from:flow_renderer*/ /**/
val Why.renderer by Whying() /*from:flow_renderer*/ /**/
