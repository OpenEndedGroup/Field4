# Jolt Physics (afield.four.physics)

This document describes the Jolt JNI integration that was promoted from personal prototypes into the main physics package.

## Location

Core runtime and API:
- src/jvmMain/kotlin/afield/four/physics/JoltPhysicsWorld.kt
- src/jvmMain/kotlin/afield/four/physics/JoltPhysicsBootstrap.kt
- src/jvmMain/kotlin/afield/four/physics/JoltDebugDrawing.kt

Examples:
- src/jvmMain/kotlin/afield/examples/JoltPhysicsExample.kt

Legacy pivot shims (deprecated forwarders):
- src/personal/kotlin/pivot/HelloJoltJni.kt
- src/personal/kotlin/pivot/JoltJniBootstrap.kt
- src/personal/kotlin/pivot/JoltJni_drawing.kt

## Core API

Primary public names:
- `JoltShape`
- `JoltBodyDef`
- `JoltBodyState`
- `JoltBodyPick`
- `JoltPhysicsWorld`

World responsibilities:
- Create and remove rigid bodies.
- Step simulation and return body states.
- Apply forces, impulses, torque, transforms, and velocities.
- Perform point-near-surface picking.
- Support a kinematic rigid-grab workflow for interactive control.
- Produce debug wireframe lines with optional local axes.

## Naming Migration

Preferred now:
- `JoltShape3D` -> `JoltShape`
- `JoltBodyDef3D` -> `JoltBodyDef`
- `JoltBodyState3D` -> `JoltBodyState`
- `JoltBodyPick3D` -> `JoltBodyPick`
- `JoltPhysicsWorld3D` -> `JoltPhysicsWorld`

Older pivot names (still available as deprecated aliases):
- `ShapeDef3D` -> `JoltShape`
- `BodyDef3D` -> `JoltBodyDef`
- `BodyState3D` -> `JoltBodyState`
- `BodyPick3D` -> `JoltBodyPick`
- `SimplePhysics3D` -> `JoltPhysicsWorld`

## Minimal Usage

```kotlin
import afield.four.physics.JoltBodyDef
import afield.four.physics.JoltPhysicsWorld
import afield.four.physics.JoltShape

fun demoStep() {
    JoltPhysicsWorld().use { world ->
        world.createBody(
            JoltBodyDef(
                name = "ground",
                shape = JoltShape.Plane(ny = 1.0, constant = 0.0),
                x = 0.0,
                y = 0.0,
                z = 0.0,
                dynamic = false
            )
        )

        world.createBody(
            JoltBodyDef(
                name = "box",
                shape = JoltShape.Cuboid(1.0, 1.0, 1.0),
                x = 0.0,
                y = 3.0,
                z = 0.0
            )
        )

        repeat(120) {
            val states = world.step(1.0 / 60.0)
            // inspect states as needed
            if (states.isEmpty()) return@repeat
        }
    }
}
```

## Notes

- Bootstrap is internal to world construction via `JoltPhysicsBootstrap.ensureInitialized()`.
- Simulation constants are currently hardcoded in `JoltPhysicsWorld` companion values.
- Current collision filtering keeps two layers: moving and non-moving.
- Examples are intentionally in `afield.examples` so runtime API files stay focused on reusable primitives.
