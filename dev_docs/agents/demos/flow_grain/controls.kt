import afield.four.modules.fromContextWithNameOrNull

// Dependencies are explicit graph exports; resources stay in their parent box.
val flowLook = fromContextWithNameOrNull<afield.stdlib.Vec4>("flowLook")!!
val force = fromContextWithNameOrNull<afield.stdlib.EffectiveParameter>("force")!!
val grainCell = fromContextWithNameOrNull<afield.stdlib.EffectiveParameter>("grainCell")!!
val strokeTime = fromContextWithNameOrNull<afield.stdlib.EffectiveParameter>("strokeTime")!!
val flowWidth = fromContextWithNameOrNull<afield.stdlib.EffectiveParameter>("flowWidth")!!
val travel = fromContextWithNameOrNull<java.util.concurrent.atomic.AtomicBoolean>("travel")!!

// Edit and execute this box. The shader receives the effective values; all values
// and bounds remain inspectable through Field's ordinary object viewer.
force.requested = 2.5 // Vortex acceleration, clamped to 0–12 world units / second².
grainCell.requested = .018 // World-space cell size, clamped to .006–.12.
strokeTime.requested = .15 // Integrated path length in seconds, clamped to .01–.6.
travel.set(false) // Scroll across the fixed tile pool and vary zoom.
flowWidth.requested = 8.0 // Visible vertical half-width in world units.
flowLook.w = 0f // 0 grain; 1 fluid velocity; 2 camera UV; 3 object+camera UV; 4 displacement.
listOf(force.snapshot(), grainCell.snapshot(), strokeTime.snapshot(), flowWidth.snapshot())
