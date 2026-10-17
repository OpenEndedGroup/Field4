// Dependencies are explicit graph exports; resources stay in their parent box.

// Edit and execute this box. The shader receives the effective values; all values
// and bounds remain inspectable through Field's ordinary object viewer.
force.requested = 2.5 // Vortex acceleration, clamped to 0–12 world units / second².
grainCell.requested = .018 // World-space cell size, clamped to .006–.12.
strokeTime.requested = .15 // Integrated path length in seconds, clamped to .01–.6.
travel.set(false) // Scroll across the fixed tile pool and vary zoom.
flowWidth.requested = 8.0 // Visible vertical half-height in world units.
flowLook.w = 0f // 0 grain; 1 fluid velocity; 2 camera UV; 3 object+camera UV; 4 displacement.
[force.snapshot(), grainCell.snapshot(), strokeTime.snapshot(), flowWidth.snapshot()]
