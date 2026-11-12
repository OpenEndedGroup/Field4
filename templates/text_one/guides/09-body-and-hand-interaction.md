# Body contact, SIMD nodes and wafting

**Body interaction / diagnostics** samples the current predicted body/hand tracking once per
rendered frame. Each unpinned SIMD particle is tested at its actual world position during
its fixed physics step. The resulting acceleration is transformed back into diagram space.
The measured text, frames and connections follow those moving anchors.

In **Box 12 → Skeleton**, independently toggle:

- **Show skeleton**: current tracked bone centrelines.
- **Show SIMD nodes**: the exact particle positions; dim dots are pinned, bright dots receive force.
- **Show body force lines**: the selected bone centreline to the exact node position tested by
  the physics callback. A line appears only when that callback applies a nonzero force.

These displays do not enable or disable body forces. The diagnostic output in the body box
reports tracked bones, node count and active pushes. Finger lines appear only when valid
hand-joint data is supplied; otherwise the tracked body and controller endpoints still work.
The debug dots expose sparse particle contact: glyph outlines and elastic-thread interior
nodes are not separately distance-tested.

## Waft away and settle

**Waft away (outward band)** changes the outside influence from gathering to repulsion.
The acceleration smoothly fades over **Outside reach**, measured outward from the capsule
surface plus **Standoff gap**. Inside the capsule, penetration adds to the outward push.
The acceleration cap bounds the combined result. Outside the band there is no body force.

**Waft away and settle** sets a 40 cm influence band, 4 m/s² surface acceleration, a
4.5 m/s² cap and gentle layout attraction/damping on the current example. It preserves
particle positions and the reading-space transform. Without forcing, the damped SIMD solver
settles toward the authored layout. Gravity and wind are zero for this proof.

Turn wafting off to use the earlier standoff/gathering behavior. **Classic gathering gain**
and **Classic inside reach** apply to that mode. The properties and controls are live and
persistent; re-running the body box retains the model values.

## Runtime setup

`Options.validateOpenXR = false` must be set before creating the renderer. The installed
core OpenXR validator does not register spaces created through WiVRn's `XR_MNDX_xdev_space`
extension and otherwise rejects their handles. Vulkan validation is independent and remains
available. Changing instance layers requires a fresh Field JVM.
