# Shared intro migration

The cutover intro in src/jvmMain/kotlin/afield/Boxes.kt contains 115 distinct destination imports.
The original wildcard scopes expand to 35 JVM/common owners for afield, 55 for stdlib, eight for
graphics, three for four and four for four.modules (overlapping owners are deduplicated).
There are no temporary old split-package wildcards. Explicit imports keep their disambiguation role.
The running Field server has not been changed to this intro.

The recipe's boxImports exactly matches the intro. boxSplitStarImports projects the complete map
onto JVM/common/generated-linalg declarations and requires all projected destinations in the intro.
Registered EDN box properties and metadata-registered sidecars therefore remove redundant old
wildcards without expanding them to dozens of local imports or adding JS/test-only destinations.
Standalone Kotlin, Markdown fences and compiled source do not inherit this runtime profile.

The two AI split roots had no original intro wildcard, so they do not gain one; their explicit
imports migrate individually. Parse errors and preexisting sidecar drift still hold their groups.

The generator configuration is updated for destination linalg, half-codec and completion annotation
packages. Regeneration, compilation and runtime verification belong to the coordinated cutover;
this intro alone is not runnable before declarations are relocated.
