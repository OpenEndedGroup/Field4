# Adopt fullscreen teacher meshes and reflected push suppliers

Frozen findings: `691b5d822671-01`.

Four authored demo/transcript sources use `fullscreenTriangle(teacher)` and the reflected `mesh.state` supplier. The callback remains live-editable and retains its time parameter. Actual import/ktfmt reports no remaining edits; parsed token, literal and fragment-boundary verification passes.

Real Vulkan teacher captures were compared before/after for both complete authored shader pairs at 96×64, with three training and two validation parameter slices. Both binary datasets match exactly, remain finite/nonconstant, and each manifest matches its actual source digest. The rig caught and corrected the supplier signature (`mesh.state = { vec(...) }`); earlier failed fixture diagnostics are retained. No neural training/inference or cooperative-vector view is claimed. Lifecycle ownership is handled separately in group 3.

Checkpoint: 367/405 addressed, 38 open. Mechanical coverage: Actual import/ktfmt for all four authored containers; parsed code, literals and fragment boundaries verified. Full guards are delegated to the lander.
