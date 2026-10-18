# Read inherited aperture textures and controls directly

Frozen findings: `a6aa1047e7f5-01`, `a6aa1047e7f5-02`.

The aperture producer now uses its implicit box receiver for properties and layout, retaining thisBox where nestedIn needs the box value. Each reduction/filter reads inherited paintSource/paintHalf/paintQuarter directly. The composite reads inherited source/filter/control properties without named nullable queries or copied aliases. Separate declarations and assignments remain separate.

The entire authored producer/reduction/filter/composite/analytic-sprite graph ran on the existing private native renderer. After changing the code, the source and downstream boxes were rerun in documented order. All three native display modes—aperture, sharp, radius guide—matched their original pixels exactly; different modes produced visibly different output. Source/reduction nesting and source frame remained correct. Actual images were inspected; no public media changed.

Actual editor import/ktfmt formatting covered all eight Kotlin bodies. Parsed containers, raw strings, execution boundaries and GLSL preservation checks passed; generated transcript documents remain the lander's responsibility.

Checkpoint: 350/405 addressed, 55 open. Mechanical coverage: Full corpus receipts in batch 56 and history repair in batch 57; eight batch 61 Kotlin bodies formatted and verified. Full guards are delegated to the lander.
