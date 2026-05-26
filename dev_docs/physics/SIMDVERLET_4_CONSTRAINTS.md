# SimdVerlet DirectedConstraints4 Extension

## Overview

This extension adds support for 4-slot distance constraints to the SimdVerlet physics engine, enabling more complex particle systems like cloth simulations with up to 4 neighbors per particle.

## Changes Made

### 1. DirectedConstraints4 Class

A new constraint class that extends the capability from 3 to 4 distance constraints per particle.

**Location**: `src/jvmMain/kotlin/afield/four/physics/SimdVerlet.kt`

```kotlin
class DirectedConstraints4(
    val n0: IntArray,  // Neighbor indices for slot 0
    val n1: IntArray,  // Neighbor indices for slot 1
    val n2: IntArray,  // Neighbor indices for slot 2
    val n3: IntArray,  // Neighbor indices for slot 3
    val r0: FloatArray,  // Rest lengths for slot 0
    val r1: FloatArray,  // Rest lengths for slot 1
    val r2: FloatArray,  // Rest lengths for slot 2
    val r3: FloatArray,  // Rest lengths for slot 3
    val s0: FloatArray,  // Stiffness values for slot 0
    val s1: FloatArray,  // Stiffness values for slot 1
    val s2: FloatArray,  // Stiffness values for slot 2
    val s3: FloatArray   // Stiffness values for slot 3
)
```

**Methods**:
- `setSlot(index, slot, neighbor, restLength, stiffness)` - Set a constraint in slots 0-3
- `swapConstraints(i, j)` - Swap constraints between particles
- `remapNeighbors(remap, newSize)` - Remap neighbor indices after particle removal

### 2. SIMD Constraint Solver

Added `solveDirectedDist4InPlace` and `solveDirectedDist4InPlaceRange` functions that use SIMD operations to efficiently solve 4-slot distance constraints.

**Key Features**:
- Vectorized constraint solving using JDK Incubator Vector API
- Supports up to 4 constraints per particle
- Thread-safe for parallel execution
- Automatic fallback to scalar code for remainder lanes

### 3. Step Function Overload

Added `step()` function overload that accepts `DirectedConstraints4`:

```kotlin
fun step(
    dt: Float,
    damping: Float,
    gravity: Float,
    iterations: Int,
    constraints: DirectedConstraints4?
)
```

### 4. State Management Extensions

Added `applyQueuedRemovalsSwap(constraints: DirectedConstraints4?)` to handle particle removal with 4-slot constraints.

### 5. ConstraintScratch Extension

Extended `ConstraintScratch` class to include `map3` for the 4th constraint slot, enabling efficient vectorized gather operations.

## Usage Example

### Basic Setup

```kotlin
// Create state
val state = SimdVerlet.State3(
    x = FloatArray(0),
    y = FloatArray(0),
    z = FloatArray(0),
    px = FloatArray(0),
    py = FloatArray(0),
    pz = FloatArray(0),
    ax = FloatArray(0),
    ay = FloatArray(0),
    az = FloatArray(0),
    invMass = FloatArray(0)
)

// Create 4-slot constraints
val constraints = SimdVerlet.DirectedConstraints4(
    n0 = IntArray(particleCount) { -1 },
    n1 = IntArray(particleCount) { -1 },
    n2 = IntArray(particleCount) { -1 },
    n3 = IntArray(particleCount) { -1 },
    r0 = FloatArray(particleCount),
    r1 = FloatArray(particleCount),
    r2 = FloatArray(particleCount),
    r3 = FloatArray(particleCount),
    s0 = FloatArray(particleCount),
    s1 = FloatArray(particleCount),
    s2 = FloatArray(particleCount),
    s3 = FloatArray(particleCount)
)

val sim = SimdVerlet(state, null)
```

### Adding Constraints

```kotlin
// Add particle
val idx = sim.addParticle(x0 = 0f, y0 = 0f, z0 = 0f)

// Set up to 4 constraints per particle
constraints.setSlot(idx, 0, neighborIdx1, restLength = 1f, stiffness = 0.8f)
constraints.setSlot(idx, 1, neighborIdx2, restLength = 1f, stiffness = 0.8f)
constraints.setSlot(idx, 2, neighborIdx3, restLength = 1f, stiffness = 0.8f)
constraints.setSlot(idx, 3, neighborIdx4, restLength = 1f, stiffness = 0.8f)
```

### Running Simulation

```kotlin
// Step simulation with 4-slot constraints
sim.step(
    dt = 1f / 60f,
    damping = 0.99f,
    gravity = -9.8f,
    iterations = 8,
    constraints = constraints
)
```

## Cloth Simulation Example

A complete interactive cloth simulation example is provided in `ClothSimulationExample.kt`:

**Key Features**:
- Grid-based cloth with 4-way constraints (up, down, left, right)
- Returns `Sequence<List<FLine>>` for visualization (via `createClothSimulation`)
- Optional curvature force for cloth flattening
- Interactive particle grabbing capability exposed through `InteractiveClothSimulation`

**Usage**:

```kotlin
val simulation = ClothSimulationExample.createInteractiveClothSimulation(
    clothWidth = 20,
    clothHeight = 20,
    spacing = 0.5f,
    enableCurvatureForce = true,
    curvatureStrength = 0.05f
)

// Optional external interaction (for mouse/VR controllers/etc.)
simulation.grabNearest(worldX, worldY, worldZ)
simulation.moveGrabbed(worldX, worldY, worldZ)
simulation.releaseGrabbed()

// Or, from external code without a local simulation reference:
ClothSimulationExample.grabNearest(worldX, worldY, worldZ)
ClothSimulationExample.moveGrabbed(worldX, worldY, worldZ)
ClothSimulationExample.releaseGrabbed()

// Iterate frames
for (lines in simulation.frames) {
    // Render lines
    // lines is List<FLine> representing the cloth wireframe
}
```

Backward-compatible usage remains available:

```kotlin
val framesOnly: Sequence<List<FLine>> = ClothSimulationExample.createClothSimulation(...)
```

For compatibility, 2D overloads still exist (`grabNearest(x, y)` / `moveGrabbed(x, y)`) and default `z = 0f`.

## Curvature Force Implementation

The cloth simulation includes an optional curvature force that tries to flatten the cloth:

```kotlin
sim.addForceGenerator(idx, "curvature", generator2 {
    while (true) {
        val ctx = yield()
        val i = ctx.index
        
        // Calculate average position of 4 neighbors
        val avgX = (x[left] + x[right] + x[top] + x[bottom]) / 4f
        val avgY = (y[left] + y[right] + y[top] + y[bottom]) / 4f
        val avgZ = (z[left] + z[right] + z[top] + z[bottom]) / 4f
        
        // Apply force toward average
        val dx = avgX - x[i]
        val dy = avgY - y[i]
        val dz = avgZ - z[i]
        
        ctx.state.ax[i] += dx * curvatureStrength
        ctx.state.ay[i] += dy * curvatureStrength
        ctx.state.az[i] += dz * curvatureStrength
    }
})
```

## Interactive Grabbing

The cloth simulation exposes interactive particle manipulation on the returned `InteractiveClothSimulation`:

```kotlin
val simulation = ClothSimulationExample.createInteractiveClothSimulation(...)

// Grab nearest particle
simulation.grabNearest(worldX, worldY, worldZ)

// Move grabbed particle
simulation.moveGrabbed(worldX, worldY, worldZ)

// Release grabbed particle
simulation.releaseGrabbed()
```

These use PID position controllers to smoothly move particles to target positions.

## Testing

Run the test example:

```bash
./gradlew :jvmRun --args="afield.examples.TestDirectedConstraints4"
```

Or the full cloth simulation:

```bash
./gradlew :jvmRun --args="afield.examples.ClothSimulationExample"
```

## Performance Considerations

- **SIMD Vectorization**: The solver uses SIMD operations for better performance on modern CPUs
- **Iteration Count**: Higher iteration counts (8-10) provide more stable constraints but are slower
- **Constraint Count**: 4 slots per particle is the maximum, use -1 or stiffness=0 for unused slots
- **Curvature Force**: Optional but adds computational cost proportional to particle count

## Implementation Details

### SIMD Constraint Solving

The 4-slot solver processes constraints in vectorized batches:

1. **Gather Phase**: Load positions of neighbor particles using vector gather operations
2. **Distance Calculation**: Compute distances using SIMD sqrt
3. **Force Calculation**: Calculate constraint forces in parallel
4. **Accumulation**: Accumulate forces with masking for invalid constraints
5. **Scalar Fallback**: Process remaining particles with scalar code

### Thread Safety

The constraint solver is designed for range-parallel execution:
- Each particle only updates its own accumulators
- No scatter operations to neighbor particles
- Safe to call on non-overlapping particle ranges

## Future Enhancements

Possible extensions:
- Bending constraints using 4-slot configuration
- Diagonal constraints for shear resistance
- Adaptive constraint stiffness based on strain
- Integration with spatial acceleration structures

## References

- Original SimdVerlet implementation: `SimdVerlet.kt`
- Test example: `TestDirectedConstraints4.kt`
- Full cloth simulation: `ClothSimulationExample.kt`
- Tiny 3-constraint example: `SimdVerletTinyExample.kt`
