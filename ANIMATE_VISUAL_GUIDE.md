# Animate Block Syntax - Visual Guide

## Problem Statement Solution

```
┌─────────────────────────────────────────────┐
│  User writes:                               │
│  ─────────────────────────────────────────  │
│  foo()                                      │
│  bar(10.0)                                  │
│                                             │
│  animate {                                  │
│      gux()                                  │
│  }                                          │
└─────────────────────────────────────────────┘
                    │
                    │ Launch (Shift+Cmd+Enter)
                    ▼
┌─────────────────────────────────────────────┐
│  Field4 Execution                           │
└─────────────────────────────────────────────┘
                    │
                    ├─────────────────────────┐
                    │                         │
                    ▼                         ▼
          ┌──────────────────┐    ┌─────────────────────┐
          │  Execute Once    │    │  Returns Frames     │
          │  ─────────────   │    │  ─────────────────  │
          │  foo()           │    │  animate { } returns│
          │  bar(10.0)       │    │  Frames object      │
          └──────────────────┘    └─────────────────────┘
                                             │
                                             │
                                             ▼
                                  ┌─────────────────────┐
                                  │ launchReturnValue() │
                                  │ detects Frames type │
                                  └─────────────────────┘
                                             │
                                             │
                                             ▼
                                  ┌─────────────────────┐
                                  │  Frame Loop         │
                                  │  ───────────────    │
                                  │  Frame 1: gux()     │
                                  │  Frame 2: gux()     │
                                  │  Frame 3: gux()     │
                                  │  ...                │
                                  └─────────────────────┘
```

## Code Flow Diagram

```
┌────────────────────────────────────────────────────────────┐
│ Box Code                                                   │
├────────────────────────────────────────────────────────────┤
│                                                            │
│  // Setup Phase (runs once)                               │
│  ┌──────────────────────────────────────────────┐         │
│  │ val speed = 5f                               │ [Once]  │
│  │ println("Starting...")                       │         │
│  │ var x = 100f                                 │         │
│  └──────────────────────────────────────────────┘         │
│                                                            │
│  // Animation Phase (runs every frame)                    │
│  ┌──────────────────────────────────────────────┐         │
│  │ animate {                                    │         │
│  │     background(0f)                           │ [Loop]  │
│  │     x += speed                               │         │
│  │     ellipse(x, 100f, 50f, 50f)              │         │
│  │ }                                            │         │
│  └──────────────────────────────────────────────┘         │
│     │                                                      │
│     │ returns Frames                                      │
│     ▼                                                      │
└────────────────────────────────────────────────────────────┘
                     │
                     │
          ┌──────────┴──────────┐
          │                     │
          ▼                     ▼
    ┌──────────┐         ┌──────────┐
    │ Frame 1  │         │ Frame 2  │  ...
    │ ──────── │         │ ──────── │
    │ x = 105  │         │ x = 110  │
    │ draw...  │         │ draw...  │
    └──────────┘         └──────────┘
```

## Execution Timeline

```
Time ─────────────────────────────────────────────────────────>

Launch Event
     │
     ▼
   ┌─────────────────────────────────────────────────────┐
   │ Initialization Phase (once)                         │
   ├─────────────────────────────────────────────────────┤
   │ • Execute all code before animate                   │
   │ • Setup variables                                   │
   │ • Call setup functions                              │
   │ • animate { } is called, returns Frames object      │
   └─────────────────────────────────────────────────────┘
                              │
                              ▼
   ┌─────────────────────────────────────────────────────┐
   │ Frame 1                                             │
   ├─────────────────────────────────────────────────────┤
   │ • Execute animate block lambda                      │
   │ • frameNumber = 1                                   │
   └─────────────────────────────────────────────────────┘
                              │
                              ▼
   ┌─────────────────────────────────────────────────────┐
   │ Frame 2                                             │
   ├─────────────────────────────────────────────────────┤
   │ • Execute animate block lambda                      │
   │ • frameNumber = 2                                   │
   └─────────────────────────────────────────────────────┘
                              │
                              ▼
                            ...
                              │
                              ▼
   ┌─────────────────────────────────────────────────────┐
   │ Frame N                                             │
   ├─────────────────────────────────────────────────────┤
   │ • Execute animate block lambda                      │
   │ • frameNumber = N                                   │
   │ • Until stopped or end() called                     │
   └─────────────────────────────────────────────────────┘
```

## Implementation Architecture

```
┌─────────────────────────────────────────────────────────────┐
│ User Code                                                   │
│ ─────────────────────────────────────────────────────────── │
│ animate { ... }                                             │
└─────────────────────────────────────────────────────────────┘
                              │
                              │ calls
                              ▼
┌─────────────────────────────────────────────────────────────┐
│ stdlib.kt                                                   │
│ ─────────────────────────────────────────────────────────── │
│ fun animate(block: suspend Frames.() -> Unit): Frames {    │
│     return eachFrame(block)                                 │
│ }                                                           │
└─────────────────────────────────────────────────────────────┘
                              │
                              │ calls
                              ▼
┌─────────────────────────────────────────────────────────────┐
│ Phased.kt                                                   │
│ ─────────────────────────────────────────────────────────── │
│ fun eachFrame(block: suspend Frames.() -> Unit): Frames {  │
│     return frames {                                         │
│         while (frame()) {                                   │
│             this.block()                                    │
│         }                                                   │
│     }                                                       │
│ }                                                           │
└─────────────────────────────────────────────────────────────┘
                              │
                              │ returns
                              ▼
┌─────────────────────────────────────────────────────────────┐
│ Frames object                                               │
│ ─────────────────────────────────────────────────────────── │
│ class Frames {                                              │
│     var body: suspend () -> Unit                            │
│     suspend fun frame(): Boolean                            │
│     // ... frame management                                 │
│ }                                                           │
└─────────────────────────────────────────────────────────────┘
                              │
                              │ detected by
                              ▼
┌─────────────────────────────────────────────────────────────┐
│ execution.kt                                                │
│ ─────────────────────────────────────────────────────────── │
│ fun launchReturnValue(                                      │
│     inside: Box, returnValue: Any?, ...                     │
│ ) {                                                         │
│     when (returnValue) {                                    │
│         is Frames -> {                                      │
│             // Execute frame body in main loop              │
│             inMain { returnValue.body() }                   │
│         }                                                   │
│     }                                                       │
│ }                                                           │
└─────────────────────────────────────────────────────────────┘
```

## Comparison: Before vs After

### Before (No animate)
```
┌─────────────────────────────┐
│ Launch Code                 │
├─────────────────────────────┤
│ background(0f)              │ ┐
│ ellipse(100f, 100f, 50, 50) │ ├─ All runs every frame
│ line(0, 0, 100, 100)        │ ┘
└─────────────────────────────┘
         │
         ├─> Frame 1: Execute all
         ├─> Frame 2: Execute all
         ├─> Frame 3: Execute all
         └─> ...
```

Problem: Can't separate setup from animation

### After (With animate)
```
┌─────────────────────────────┐
│ Launch Code                 │
├─────────────────────────────┤
│ strokeWeight(2f)            │ ◄── Runs once
│ val radius = 50f            │ ◄── Runs once
│                             │
│ animate {                   │ ┐
│   background(0f)            │ │
│   ellipse(100, 100, r, r)   │ ├─ Runs every frame
│   line(0, 0, 100, 100)      │ │
│ }                           │ ┘
└─────────────────────────────┘
         │
         ├─> Init: strokeWeight, radius
         ├─> Frame 1: animate block
         ├─> Frame 2: animate block
         ├─> Frame 3: animate block
         └─> ...
```

Solution: Clear separation of concerns

## Key Points

1. **Minimal Implementation**: Just 3 lines of code
2. **Leverages Existing System**: Uses Frames infrastructure
3. **Type Safe**: Returns Frames, caught at compile time
4. **Backwards Compatible**: Old style still works
5. **Clear Intent**: Obvious what runs when
6. **Formal Syntax**: Standard pattern across all code

## Related Functions

```
animate { }    ─┐
                ├─ All build on Frames system
eachFrame { }  ─┤
                │
loop { }       ─┤
                │
frames { }     ─┘ (base function)
```
