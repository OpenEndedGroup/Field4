// RectSL editor extension: inline syntax highlighting, flow decorations, diagnostics,
// context badge and completions for #rectsl regions embedded in Kotlin """...""" strings.
//
// This is the single source of truth for the RectSL editor extension. It is served as a
// static AMD module (loaded via bithero/rectsl in bootstrapMonaco.js) in the browser, and
// is also read verbatim by afield.rectsl.RectSLEditorExtension for the node-probe smoke
// test. The wrapper below supports both worlds: it registers an AMD module when a Monaco
// loader is present, and otherwise self-installs immediately (as it does under bare eval).
(function () {
  "use strict";

  const installRectSLExtension = () => {
  const root = window;
  const extensionId = "rectsl";
  const extensionLabel = "RectSL";
  const triple = '"""';
  const headerRe = /^(?:[ \t]*(?:\r?\n|$))*[ \t]*#rectsl\b[^\n]*(?:\r?\n|$)/i;

  const stackWords = new Set(["dup", "drop", "swap", "over"]);
  const sceneWords = new Set(["wall", "duration", "fps", "meters", "pixels", "abstract", "fit", "contain", "cover", "stretch"]);
  const objectWords = new Set(["rect", "group", "end", "end-group", "group-selected", "select", "rename", "tag", "id", "rect?", "group?", "copy", "clone", "clone-deep", "copy-deep", "front", "back"]);
  const geometryWords = new Set(["xy", "nxy", "x", "y", "wh", "nwh", "w", "h", "square", "z", "layer", "rot", "rotation", "scale", "scale-xy", "alpha", "opacity*"]);
  const paintWords = new Set(["solid", "linear", "fill", "rgb", "rgba", "material", "shader"]);
  const trackWords = new Set(["track", "linear-track", "hold-track", "track-ref", "tcb", "linear", "hold", "tcb!", "x-track", "y-track", "w-track", "h-track", "rot-track", "rotation-track", "scale-x-track", "scale-y-track", "alpha-track", "z-track", "layer-track", "delay-time", "scale-time", "reverse-time", "loop-time", "pingpong-time", "time+", "time*", "value+", "value*", "clamp-values", "snap-time", "keys-before", "keys-after", "keys-between", "ease-in", "ease-out", "ease-in-out", "ease-smoothstep", "smooth"]);
  const transformWords = new Set(["mirror-x", "mirror-y", "mirror-x-at", "mirror-y-at", "translate", "rotate", "offset", "stagger", "polar", "delay-all-time", "scale-all-time", "reverse-all-time", "loop-all-time", "pingpong-all-time", "stagger-time"]);
  const higherWords = new Set(["all", "roots", "selected", "children", "descendants", "rects", "groups", "tagged", "tagged?", "each", "map", "filter", "where", "times", "repeat", "range", "i", "loop-count", "n", "array", "grid", "radial", "f", "f0", "fc", "ic", "col", "row", "cols", "rows", "colc", "rowc", "ease", "gaussian", "noise", "fbm", "sample", "reflect", "tile"]);
  const operatorWords = new Set(["+", "add", "-", "sub", "*", "mul", "/", "div", "mod", "pow", "min", "max", "neg", "negate", "abs", "floor", "ceil", "fract", "sqrt", "sin", "cos", "clamp", "sat", "saturate", "mix", "lerp", "smoothstep", "<", "lt", "<=", "lte", ">", "gt", ">=", "gte", "=", "eq", "!=", "<>", "neq", "and", "or", "not"]);
  const constants = new Set(["pi", "tau"]);
  const wordDoc = (signature, detail, insertText = null) => ({ signature, detail, insertText });
  const wordDocs = new Map([
    ["wall", wordDoc("width height --", "Set the physical wall size. Follow with a unit word such as meters.")],
    ["meters", wordDoc("--", "Mark the current wall unit as meters.")],
    ["pixels", wordDoc("--", "Mark the current wall unit as pixels.")],
    ["abstract", wordDoc("--", "Mark the current wall unit as abstract coordinates.")],
    ["duration", wordDoc("seconds --", "Set animation duration in seconds.")],
    ["fps", wordDoc("framesPerSecond --", "Set the sampling/render frame rate.")],
    ["fit", wordDoc("policy --", "Set render fit policy: contain, cover, or stretch.")],
    ["contain", wordDoc("-- policy", "Preserve aspect ratio and fit inside the render target.")],
    ["cover", wordDoc("-- policy", "Preserve aspect ratio and cover the render target.")],
    ["stretch", wordDoc("-- policy", "Stretch wall coordinates to the render target.")],

    ["rect", wordDoc("[id] -- rect", "Create a rectangle, optionally using a string id from the stack.")],
    ["group", wordDoc("[id] -- group", "Create a group scope, optionally using a string id from the stack.")],
    ["end", wordDoc("--", "Close the current group scope.")],
    ["end-group", wordDoc("--", "Close the current group scope.")],
    ["group-selected", wordDoc("selection name -- group", "Create a new group containing the selected objects.")],
    ["select", wordDoc("id -- object", "Select a rect or group by id.")],
    ["rename", wordDoc("name --", "Rename the current selected object.")],
    ["tag", wordDoc("name --", "Add a tag to the current rect/group or selected objects.")],
    ["id", wordDoc("-- id", "Push the active rect/group id.")],
    ["rect?", wordDoc("-- bool", "Return 1 when the active object is a rectangle.")],
    ["group?", wordDoc("-- bool", "Return 1 when the active object is a group.")],
    ["copy", wordDoc("-- object", "Copy the current rect/group shallowly.")],
    ["clone", wordDoc("-- object", "Alias for copy.")],
    ["copy-deep", wordDoc("-- object", "Copy a group and descendants, preserving internal structure.")],
    ["clone-deep", wordDoc("-- object", "Alias for copy-deep.")],

    ["xy", wordDoc("x y --", "Set center position in wall/world coordinates.")],
    ["nxy", wordDoc("nx ny --", "Set center position from normalized -1..1 wall coordinates.")],
    ["x", wordDoc("number|track --", "Set or animate x position. A quote becomes a default TCB track.")],
    ["y", wordDoc("number|track --", "Set or animate y position. A quote becomes a default TCB track.")],
    ["wh", wordDoc("width height --", "Set rectangle size in wall/world units.")],
    ["nwh", wordDoc("nw nh --", "Set rectangle size from normalized wall fractions.")],
    ["w", wordDoc("width --", "Set rectangle width.")],
    ["h", wordDoc("height --", "Set rectangle height.")],
    ["square", wordDoc("size --", "Set width and height to the same value.")],
    ["z", wordDoc("number|track --", "Set draw-depth metadata for the rect.")],
    ["layer", wordDoc("number|string|track --", "Set draw layer. Higher layers render later and appear on top.")],
    ["front", wordDoc("--", "Move the active object/selection to the front of its layer.")],
    ["back", wordDoc("--", "Move the active object/selection to the back of its layer.")],
    ["rot", wordDoc("radians|track --", "Set or animate rotation in radians.")],
    ["rotation", wordDoc("radians|track --", "Alias for rot.")],
    ["scale", wordDoc("scale|track --", "Set uniform scale.")],
    ["scale-xy", wordDoc("xScale yScale --", "Set nonuniform scale.")],
    ["alpha", wordDoc("alpha|track --", "Set or animate opacity alpha.")],
    ["opacity*", wordDoc("factor --", "Multiply current opacity/alpha by factor.")],

    ["solid", wordDoc("color -- paint", "Create a solid fill paint from a color.")],
    ["linear", wordDoc("color0 color1 x0 y0 x1 y1 -- paint", "Create a two-color linear gradient paint, CPU-baked into vertex colors.")],
    ["fill", wordDoc("paint --", "Assign fill paint to the current rect or selection.")],
    ["rgb", wordDoc("r g b -- color", "Build an RGB color from 0..1 channels.")],
    ["rgba", wordDoc("r g b a -- color", "Build an RGBA color from 0..1 channels.")],

    ["track", wordDoc("name quote -- | track name quote", "Define a named TCB scalar track.")],
    ["linear-track", wordDoc("name quote", "Prefix word: define a named linear scalar track.")],
    ["hold-track", wordDoc("name quote", "Prefix word: define a named hold/step scalar track.")],
    ["track-ref", wordDoc("name -- track", "Push a named track for reuse on properties or operators.")],
    ["tcb", wordDoc("quote -- track", "Convert quoted time/value pairs to a TCB scalar track.")],
    ["linear", wordDoc("quote -- track | color0 color1 x0 y0 x1 y1 -- paint", "Convert a quote to a linear scalar track; with paint operands, create a CPU-baked linear fill.")],
    ["hold", wordDoc("quote -- track", "Convert quoted time/value pairs to a hold/step track.")],
    ["tcb!", wordDoc("track tension continuity bias -- track", "Set default TCB parameters on a track.")],
    ["delay-time", wordDoc("track seconds -- track", "Delay a track in time.")],
    ["scale-time", wordDoc("track factor -- track", "Scale a track's key times.")],
    ["reverse-time", wordDoc("track -- track", "Reverse a track in time.")],
    ["loop-time", wordDoc("track period -- track", "Loop a track over a time period.")],
    ["pingpong-time", wordDoc("track period -- track", "Ping-pong a track over a time period.")],
    ["delay-all-time", wordDoc("seconds --", "Delay all tracks on the current object or selection.")],
    ["scale-all-time", wordDoc("factor --", "Scale all key times on the current object or selection.")],
    ["reverse-all-time", wordDoc("--", "Reverse all tracks on the current object or selection across the scene duration.")],
    ["loop-all-time", wordDoc("period --", "Loop all tracks on the current object or selection.")],
    ["pingpong-all-time", wordDoc("period --", "Ping-pong all tracks on the current object or selection.")],
    ["stagger-time", wordDoc("stepSeconds --", "Delay each selected object by an increasing step.")],

    ["mirror-x", wordDoc("--", "Mirror current object/selection across x=0, including x tracks.")],
    ["mirror-y", wordDoc("--", "Mirror current object/selection across y=0, including y tracks.")],
    ["mirror-x-at", wordDoc("xAxis --", "Mirror current object/selection across the given x axis.")],
    ["mirror-y-at", wordDoc("yAxis --", "Mirror current object/selection across the given y axis.")],
    ["translate", wordDoc("dx dy --", "Translate current object/selection.")],
    ["rotate", wordDoc("radians --", "Rotate current object/selection.")],
    ["offset", wordDoc("dx dy --", "Offset position tracks or constants.")],
    ["stagger", wordDoc("dx dy --", "Offset each selected object by an increasing vector.")],

    ["all", wordDoc("-- selection", "Select all nodes.")],
    ["roots", wordDoc("-- selection", "Select top-level nodes.")],
    ["selected", wordDoc("-- selection", "Push the current selected object(s).")],
    ["children", wordDoc("selection -- selection", "Select direct children of selected groups.")],
    ["descendants", wordDoc("selection -- selection", "Select all descendants of selected groups.")],
    ["rects", wordDoc("selection -- selection", "Filter a selection to rectangles.")],
    ["groups", wordDoc("selection -- selection", "Filter a selection to groups.")],
    ["tagged", wordDoc("selection tag -- selection", "Filter a selection to objects with a tag.")],
    ["tagged?", wordDoc("object tag -- bool", "Test whether an object has a tag.")],
    ["each", wordDoc("selection quote -- selection", "Apply a quote to each object in a selection.")],
    ["map", wordDoc("selection quote -- selection", "Map a quote across a selection.")],
    ["filter", wordDoc("selection quote -- selection", "Filter a selection by a predicate quote.")],
    ["where", wordDoc("selection quote -- selection", "Alias for filter.")],
    ["times", wordDoc("count quote --", "Run a quote count times.")],

    ["x-track", wordDoc("object -- track", "Push the x track for transform/key editing.")],
    ["y-track", wordDoc("object -- track", "Push the y track for transform/key editing.")],
    ["w-track", wordDoc("object -- track", "Push the width track.")],
    ["h-track", wordDoc("object -- track", "Push the height track.")],
    ["rot-track", wordDoc("object -- track", "Push the rotation track.")],
    ["scale-x-track", wordDoc("object -- track", "Push the x scale track.")],
    ["scale-y-track", wordDoc("object -- track", "Push the y scale track.")],
    ["alpha-track", wordDoc("object -- track", "Push the alpha track.")],
    ["z-track", wordDoc("object -- track", "Push the z track.")],
    ["layer-track", wordDoc("object -- track", "Push the layer track.")],
    ["time+", wordDoc("track seconds -- track", "Shift key times.")],
    ["time*", wordDoc("track factor -- track", "Scale key times.")],
    ["snap-time", wordDoc("track grid -- track", "Snap key times to a grid.")],
    ["value+", wordDoc("track amount -- track", "Add to all scalar key values.")],
    ["value*", wordDoc("track factor -- track", "Scale all scalar key values.")],
    ["clamp-values", wordDoc("track low high -- track", "Clamp all scalar key values.")],
    ["keys-before", wordDoc("track time -- track", "Keep keys before time.")],
    ["keys-after", wordDoc("track time -- track", "Keep keys after time.")],
    ["keys-between", wordDoc("track start end -- track", "Keep keys inside a time range.")],
    ["ease-in", wordDoc("scalar -- track", "Apply a quadratic ease-in curve to a scalar or track.")],
    ["ease-out", wordDoc("scalar -- track", "Apply a quadratic ease-out curve to a scalar or track.")],
    ["ease-in-out", wordDoc("scalar -- track", "Apply a symmetric quadratic ease-in/out curve.")],
    ["ease-smoothstep", wordDoc("scalar -- track", "Apply Hermite smoothstep easing.")],
    ["smooth", wordDoc("scalar -- track", "Alias for ease-smoothstep.")],

    ["repeat", wordDoc("count quote --", "Run a quote count times. Inside the quote, i is the zero-based index and n/loop-count is the count.")],
    ["range", wordDoc("start end quote --", "Run a quote for each integer in [start, end). Inside the quote, i is the current value.")],
    ["i", wordDoc("-- number", "Push the current loop index inside repeat/range.")],
    ["loop-count", wordDoc("-- number", "Push the current repeat/range count.")],
    ["n", wordDoc("-- number", "Alias for loop-count.")],

    ["array", wordDoc("[template] count quote -- selection", "Reproduce a template object count times, running quote on each copy with array context. Element 0 is the template; the rest are fresh clones (deep for groups). Template is the active object, or a rect/group from the stack. Pushes the selection of all copies.")],
    ["radial", wordDoc("[template] count radius quote -- selection", "Like array, but each copy is placed on a circle of radius at angle ordinal/count*tau before quote runs.")],
    ["grid", wordDoc("cols rows quote -- selection", "Run quote cols*rows times to build fresh cells with grid context (col, row, cols, rows, colc, rowc). Close each cell with ;. Pushes the selection of created objects.")],
    ["polar", wordDoc("angle radius --", "Place the active object on a circle: x = radius*cos angle, y = radius*sin angle.")],
    ["f", wordDoc("-- number", "Normalized array fraction ordinal/(n-1) in [0,1]; 0 for a single element. Pairs with mix.")],
    ["f0", wordDoc("-- number", "Cyclic array fraction ordinal/n in [0,1); handy for rings (f0 tau *).")],
    ["fc", wordDoc("-- number", "Centered array fraction 2f-1 in [-1,1].")],
    ["ic", wordDoc("-- number", "Centered array index ordinal-(n-1)/2, symmetric around 0.")],
    ["col", wordDoc("-- number", "Current column inside grid.")],
    ["row", wordDoc("-- number", "Current row inside grid.")],
    ["cols", wordDoc("-- number", "Grid column count inside grid.")],
    ["rows", wordDoc("-- number", "Grid row count inside grid.")],
    ["colc", wordDoc("-- number", "Centered column col-(cols-1)/2 inside grid.")],
    ["rowc", wordDoc("-- number", "Centered row row-(rows-1)/2 inside grid.")],

    ["ease", wordDoc("x name -- x", "Shape a scalar/track through a named Penner easing (quad-out, cubic-in-out, back-out, elastic-in-out, bounce-out, sine-in-out, expo-out, circ-in-out, smoothstep, smootherstep, ...). Borrowed from afield.stdlib.Functions.")],
    ["gaussian", wordDoc("-- Fn", "A bell-curve distribution over [0,1] centered at 0.5. Sample at the array fraction f.")],
    ["noise", wordDoc("seed -- Fn", "A seeded value-noise curve over [0,1] — deterministic, reproducible scatter.")],
    ["fbm", wordDoc("seed -- Fn", "A seeded fractal (fBm) noise curve over [0,1] — richer multi-octave scatter.")],
    ["sample", wordDoc("Fn x -- number", "Evaluate a distribution curve at x (use the array fraction f).")],
    ["reflect", wordDoc("Fn -- Fn", "Mirror a curve about 0.5: reflect(x) = fn(1-x).")],
    ["tile", wordDoc("Fn k -- Fn", "Repeat a curve k times across [0,1].")],

    ["dup", wordDoc("a -- a a", "Duplicate the top stack value.")],
    ["drop", wordDoc("a --", "Drop the top stack value.")],
    ["swap", wordDoc("a b -- b a", "Swap the top two stack values.")],
    ["over", wordDoc("a b -- a b a", "Copy the second stack value to the top.")],
    ["+", wordDoc("a b -- out", "Add constants/tracks.")],
    ["-", wordDoc("a b -- out", "Subtract constants/tracks.")],
    ["*", wordDoc("a b -- out", "Multiply constants/tracks.")],
    ["/", wordDoc("a b -- out", "Divide constants/tracks.")],
    ["mod", wordDoc("a b -- out", "Modulo constants/tracks.")],
    ["pow", wordDoc("base exp -- out", "Power for constants/tracks.")],
    ["min", wordDoc("a b -- out", "Minimum for constants/tracks.")],
    ["max", wordDoc("a b -- out", "Maximum for constants/tracks.")],
    ["neg", wordDoc("a -- out", "Negate a constant/track.")],
    ["abs", wordDoc("a -- out", "Absolute value.")],
    ["floor", wordDoc("a -- out", "Floor.")],
    ["ceil", wordDoc("a -- out", "Ceil.")],
    ["fract", wordDoc("a -- out", "Fractional part.")],
    ["sqrt", wordDoc("a -- out", "Square root.")],
    ["sin", wordDoc("a -- out", "Sine.")],
    ["cos", wordDoc("a -- out", "Cosine.")],
    ["clamp", wordDoc("value low high -- out", "Clamp to a range.")],
    ["sat", wordDoc("value -- out", "Clamp to 0..1.")],
    ["mix", wordDoc("a b t -- out", "Linear interpolation.")],
    ["lerp", wordDoc("a b t -- out", "Alias for mix.")],
    ["smoothstep", wordDoc("low high x -- out", "Smooth Hermite step.")],
    ["pi", wordDoc("-- number", "Push pi.")],
    ["tau", wordDoc("-- number", "Push tau.")],
  ]);

  const effects = new Map([
    ["dup", [1, 2]], ["drop", [1, 0]], ["swap", [2, 2]], ["over", [2, 3]],
    ["wall", [2, 0]], ["duration", [1, 0]], ["fps", [1, 0]], ["fit", [1, 0]],
    ["rect", [0, 1]], ["group", [0, 1]], ["end", [0, 0]], ["end-group", [0, 0]], ["group-selected", [2, 1]], ["select", [1, 1]], ["rename", [1, 1]], ["tag", [1, 0]], ["id", [0, 1]], ["rect?", [0, 1]], ["group?", [0, 1]], ["tagged?", [1, 1]],
    ["copy", [0, 1]], ["clone", [0, 1]], ["copy-deep", [0, 1]], ["clone-deep", [0, 1]],
    ["xy", [2, 0]], ["nxy", [2, 0]], ["x", [1, 0]], ["y", [1, 0]], ["wh", [2, 0]], ["nwh", [2, 0]], ["w", [1, 0]], ["h", [1, 0]],
    ["square", [1, 0]], ["z", [1, 0]], ["layer", [1, 0]], ["front", [0, 0]], ["back", [0, 0]], ["rot", [1, 0]], ["rotation", [1, 0]], ["scale", [1, 0]], ["scale-xy", [2, 0]], ["alpha", [1, 0]], ["opacity*", [1, 0]],
    ["solid", [1, 1]], ["linear", [1, 1]], ["fill", [1, 0]], ["rgb", [3, 1]], ["rgba", [4, 1]], ["material", [1, 0]], ["shader", [1, 0]],
    ["track", [2, 0]], ["linear-track", [2, 0]], ["hold-track", [2, 0]], ["track-ref", [1, 1]], ["tcb", [1, 1]], ["hold", [1, 1]], ["tcb!", [4, 1]],
    ["x-track", [0, 1]], ["y-track", [0, 1]], ["w-track", [0, 1]], ["h-track", [0, 1]], ["rot-track", [0, 1]], ["rotation-track", [0, 1]], ["scale-x-track", [0, 1]], ["scale-y-track", [0, 1]], ["alpha-track", [0, 1]], ["z-track", [0, 1]], ["layer-track", [0, 1]],
    ["delay-time", [2, 1]], ["scale-time", [2, 1]], ["reverse-time", [1, 1]], ["loop-time", [2, 1]], ["pingpong-time", [2, 1]], ["time+", [2, 1]], ["time*", [2, 1]], ["value+", [2, 1]], ["value*", [2, 1]], ["clamp-values", [3, 1]], ["snap-time", [2, 1]], ["keys-before", [2, 1]], ["keys-after", [2, 1]], ["keys-between", [3, 1]],
    ["ease-in", [1, 1]], ["ease-out", [1, 1]], ["ease-in-out", [1, 1]], ["ease-smoothstep", [1, 1]], ["smooth", [1, 1]],
    ["mirror-x-at", [1, 0]], ["mirror-y-at", [1, 0]], ["translate", [2, 0]], ["rotate", [1, 0]], ["offset", [2, 0]], ["stagger", [2, 1]], ["polar", [2, 0]],
    ["all", [0, 1]], ["roots", [0, 1]], ["selected", [0, 1]], ["children", [1, 1]], ["descendants", [1, 1]], ["rects", [1, 1]], ["groups", [1, 1]], ["tagged", [2, 1]],
    ["each", [2, 1]], ["map", [2, 1]], ["filter", [2, 1]], ["where", [2, 1]], ["times", [2, 0]], ["repeat", [2, 0]], ["range", [3, 0]], ["i", [0, 1]], ["loop-count", [0, 1]], ["n", [0, 1]],
    ["array", [2, 1]], ["radial", [3, 1]], ["grid", [3, 1]], ["f", [0, 1]], ["f0", [0, 1]], ["fc", [0, 1]], ["ic", [0, 1]], ["col", [0, 1]], ["row", [0, 1]], ["cols", [0, 1]], ["rows", [0, 1]], ["colc", [0, 1]], ["rowc", [0, 1]],
    ["ease", [2, 1]], ["gaussian", [0, 1]], ["noise", [1, 1]], ["fbm", [1, 1]], ["sample", [2, 1]], ["reflect", [1, 1]], ["tile", [2, 1]],
    ["+", [2, 1]], ["-", [2, 1]], ["*", [2, 1]], ["/", [2, 1]], ["mod", [2, 1]], ["pow", [2, 1]], ["min", [2, 1]], ["max", [2, 1]],
    ["neg", [1, 1]], ["abs", [1, 1]], ["floor", [1, 1]], ["ceil", [1, 1]], ["fract", [1, 1]], ["sqrt", [1, 1]], ["sin", [1, 1]], ["cos", [1, 1]],
    ["clamp", [3, 1]], ["sat", [1, 1]], ["mix", [3, 1]], ["lerp", [3, 1]], ["smoothstep", [3, 1]],
    ["add", [2, 1]], ["sub", [2, 1]], ["mul", [2, 1]], ["div", [2, 1]], ["negate", [1, 1]], ["saturate", [1, 1]],
    ["<", [2, 1]], ["lt", [2, 1]], ["<=", [2, 1]], ["lte", [2, 1]], [">", [2, 1]], ["gt", [2, 1]], [">=", [2, 1]], ["gte", [2, 1]], ["=", [2, 1]], ["eq", [2, 1]], ["!=", [2, 1]], ["<>", [2, 1]], ["neq", [2, 1]], ["and", [2, 1]], ["or", [2, 1]], ["not", [1, 1]],
  ]);

  const monacoApi = () => root.globalMonaco || root.monaco;

  const escapeHtml = (value) => String(value == null ? "" : value)
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;");

  const rangeFromOffsets = (monaco, model, start, end) => {
    const s = model.getPositionAt(start);
    const e = model.getPositionAt(Math.max(start, end));
    return new monaco.Range(s.lineNumber, s.column, e.lineNumber, e.column);
  };

  const findEmbeddedRegions = (model) => {
    if (!model || (model.getLanguageId && model.getLanguageId()) !== "kotlin") return [];
    const source = model.getValue();
    const regions = [];
    let cursor = 0;
    while (cursor < source.length) {
      const open = source.indexOf(triple, cursor);
      if (open < 0) break;
      const contentStart = open + triple.length;
      const close = source.indexOf(triple, contentStart);
      if (close < 0) break;
      const content = source.slice(contentStart, close);
      const header = headerRe.exec(content);
      if (header) {
        const headerStart = contentStart + header[0].search(/#rectsl\b/i);
        const bodyStart = contentStart + header[0].length;
        regions.push({
          source: content.slice(header[0].length),
          bodyStart,
          bodyEnd: close,
          headerStart,
          headerEnd: headerStart + "#rectsl".length,
          embedded: true,
        });
      }
      cursor = close + triple.length;
    }
    return regions;
  };

  const sourceScope = (model, offset) => {
    if (!model) return null;
    if (model.getLanguageId && model.getLanguageId() === extensionId) {
      return { source: model.getValue(), bodyStart: 0, bodyEnd: model.getValue().length, offset, embedded: false };
    }
    return findEmbeddedRegions(model).find((region) => region.bodyStart <= offset && offset <= region.bodyEnd) || null;
  };

  const pushToken = (tokens, kind, start, end, source, baseOffset) => {
    if (end <= start) return;
    tokens.push({ kind, start: baseOffset + start, end: baseOffset + end, text: source.slice(start, end) });
  };

  const tokenize = (source, baseOffset = 0) => {
    const tokens = [];
    let i = 0;
    while (i < source.length) {
      const ch = source[i];
      if (/\s/.test(ch)) {
        i++;
        continue;
      }
      if (ch === "#") {
        const start = i;
        while (i < source.length && source[i] !== "\n") i++;
        pushToken(tokens, "comment", start, i, source, baseOffset);
        continue;
      }
      if (source.startsWith("'(", i)) {
        const start = i;
        i += 2;
        let depth = 1;
        while (i < source.length && depth > 0) {
          if (source.startsWith("'(", i)) {
            depth++;
            i += 2;
          } else if (source[i] === ")") {
            depth--;
            i++;
          } else {
            i++;
          }
        }
        pushToken(tokens, "quote", start, i, source, baseOffset);
        continue;
      }
      if (ch === "\"") {
        const start = i++;
        while (i < source.length) {
          if (source[i] === "\\" && i + 1 < source.length) {
            i += 2;
          } else if (source[i] === "\"") {
            i++;
            break;
          } else {
            i++;
          }
        }
        pushToken(tokens, "string", start, i, source, baseOffset);
        continue;
      }
      if (ch === ":" || ch === ";") {
        pushToken(tokens, "control", i, i + 1, source, baseOffset);
        i++;
        continue;
      }
      const start = i;
      while (i < source.length && !/\s/.test(source[i]) && source[i] !== "#" && source[i] !== "\"" && source[i] !== ":" && source[i] !== ";") i++;
      const raw = source.slice(start, i);
      const kind = /^0x[0-9a-fA-F]+$/.test(raw) ? "color" : (/^[+-]?(?:\d+\.\d*|\d*\.\d+|\d+)(?:[eE][+-]?\d+)?$/.test(raw) ? "number" : "word");
      pushToken(tokens, kind, start, i, source, baseOffset);
    }
    return tokens;
  };

  const classForToken = (token, expectingDefinitionName) => {
    if (token.kind === "comment") return "afield-stacksl-token-comment";
    if (token.kind === "string") return "afield-stacksl-token-variable";
    if (token.kind === "quote") return "afield-stacksl-token-creative";
    if (token.kind === "number" || token.kind === "color") return "afield-stacksl-token-number";
    if (token.kind === "control") return "afield-stacksl-token-control";
    if (expectingDefinitionName) return "afield-stacksl-token-definition";
    const word = String(token.text || "").toLowerCase();
    if (stackWords.has(word) || objectWords.has(word) || geometryWords.has(word) || higherWords.has(word)) return "afield-stacksl-token-keyword";
    if (paintWords.has(word) || transformWords.has(word)) return "afield-stacksl-token-creative";
    if (trackWords.has(word) || operatorWords.has(word)) return "afield-stacksl-token-operator";
    if (sceneWords.has(word) || constants.has(word)) return "afield-stacksl-token-constant";
    return "afield-stacksl-token-identifier";
  };

  const isPushToken = (token) => token.kind === "number" || token.kind === "color" || token.kind === "string" || token.kind === "quote";
  const effectForToken = (token) => {
    if (!token || token.kind === "comment") return [0, 0];
    if (isPushToken(token)) return [0, 1];
    if (token.kind === "control") return [0, 0];
    const word = String(token.text || "").toLowerCase();
    return effects.get(word) || [0, 0];
  };

  const analyzeFlow = (tokens) => {
    const stack = [];
    const records = [];
    for (const token of tokens) {
      const effect = effectForToken(token);
      const consume = effect[0];
      const produce = effect[1];
      const consumed = [];
      for (let i = 0; i < consume; i++) consumed.unshift(stack.pop() || null);
      const produced = [];
      for (let i = 0; i < produce; i++) {
        const value = { producer: token, consumers: [] };
        produced.push(value);
        stack.push(value);
      }
      consumed.forEach((value) => {
        if (value) value.consumers.push(token);
      });
      records.push({ token, effect, consumed, produced });
    }
    return records;
  };

  const tokenAt = (tokens, offset) => tokens.find((token) => token.start <= offset && offset <= token.end);

  const completionPrefixAt = (model, position) => {
    const offset = model.getOffsetAt(position);
    const value = model.getValue();
    let start = offset;
    while (start > 0 && !/\s/.test(value[start - 1]) && value[start - 1] !== "\"" && value[start - 1] !== ":" && value[start - 1] !== ";") start--;
    return { offset, start, text: value.slice(start, offset) };
  };

  const provideCompletionItems = (editor, model, position, context = {}) => {
    const monaco = context.monaco || monacoApi();
    if (!model || !position || !monaco) return { suggestions: [] };
    const offset = model.getOffsetAt(position);
    const scope = sourceScope(model, offset);
    if (!scope) return { suggestions: [] };
    const prefix = completionPrefixAt(model, position);
    const start = model.getPositionAt(prefix.start);
    const range = new monaco.Range(start.lineNumber, start.column, position.lineNumber, position.column);
    const kind = monaco.languages && monaco.languages.CompletionItemKind
      ? monaco.languages.CompletionItemKind.Function
      : 1;
    const wordStart = prefix.text.toLowerCase();
    const suggestions = Array.from(wordDocs.entries())
      .filter(([word]) => wordStart.length === 0 || word.toLowerCase().startsWith(wordStart))
      .map(([word, doc], index) => ({
        label: word,
        insertText: doc.insertText || word,
        filterText: word,
        sortText: "0200" + String(index).padStart(4, "0") + word,
        kind,
        range,
        detail: doc.signature,
        documentation: {
          value: "**" + word + "** `" + doc.signature + "`\n\n" + doc.detail,
          isTrusted: true,
          supportThemeIcons: true,
          supportHtml: true,
        },
        data: {
          source: extensionId,
          aiDocResolved: true,
        },
      }));
    return { suggestions };
  };

  const ensureCollection = (editor, key) => {
    if (!editor) return null;
    if (!editor[key]) {
      editor[key] = editor.createDecorationsCollection ? editor.createDecorationsCollection([]) : { set: () => {} };
    }
    return editor[key];
  };

  const setCollection = (editor, key, decorations) => {
    const collection = ensureCollection(editor, key);
    if (collection && typeof collection.set === "function") collection.set(decorations);
  };

  // The regions RectSL is responsible for in a given model: the whole buffer when the
  // model IS a standalone "rectsl" document (e.g. a loose .rectsl file), or each embedded
  // #rectsl region inside a kotlin """...""" string.
  const regionScopes = (model) => {
    if (!model) return [];
    if (model.getLanguageId && model.getLanguageId() === extensionId) {
      const value = model.getValue();
      return [{ source: value, bodyStart: 0, bodyEnd: value.length, embedded: false }];
    }
    return findEmbeddedRegions(model);
  };

  // High-confidence lexical checks that mirror the RectSL tokenizer's own error cases and
  // cannot false-positive: unbalanced quotes, unterminated strings, and unbalanced {{ }}
  // Kotlin escapes. Deliberately NOT a stack-depth analysis: RectSL's stack effects are
  // context-dependent (rect/group optionally consume a leading id, many words peek) and
  // leaving values on the stack is normal, so underflow/leftover linting would be noise.
  // Returns issues with region-local offsets.
  const analyzeLexical = (source) => {
    const issues = [];
    const quoteOpens = [];
    let escapeDepth = 0;
    let escapeOpen = -1;
    let i = 0;
    while (i < source.length) {
      const ch = source[i];
      if (ch === "{" && source[i + 1] === "{") {
        if (escapeDepth === 0) escapeOpen = i;
        escapeDepth++;
        i += 2;
        continue;
      }
      if (ch === "}" && source[i + 1] === "}") {
        if (escapeDepth > 0) escapeDepth--;
        i += 2;
        continue;
      }
      if (escapeDepth > 0) { i++; continue; }
      if (ch === "#") {
        while (i < source.length && source[i] !== "\n") i++;
        continue;
      }
      if (ch === "\"") {
        const start = i++;
        let closed = false;
        while (i < source.length) {
          if (source[i] === "\\" && i + 1 < source.length) { i += 2; continue; }
          if (source[i] === "\"") { i++; closed = true; break; }
          i++;
        }
        if (!closed) issues.push({ start, end: source.length, message: "RectSL string literal is not terminated.", severity: "error" });
        continue;
      }
      if (ch === "'" && source[i + 1] === "(") {
        quoteOpens.push(i);
        i += 2;
        continue;
      }
      if (ch === ")") {
        if (quoteOpens.length > 0) quoteOpens.pop();
        else issues.push({ start: i, end: i + 1, message: "RectSL ')' has no matching '( quote.", severity: "warning" });
        i++;
        continue;
      }
      i++;
    }
    if (escapeDepth > 0 && escapeOpen >= 0) {
      issues.push({ start: escapeOpen, end: escapeOpen + 2, message: "RectSL '{{' Kotlin escape is not closed with '}}'.", severity: "error" });
    }
    for (const openOffset of quoteOpens) {
      issues.push({ start: openOffset, end: openOffset + 2, message: "RectSL '( quote is not closed with ')'.", severity: "warning" });
    }
    return issues;
  };

  const updateEmbeddedHighlight = (editor) => {
    const monaco = monacoApi();
    const model = editor && editor.getModel && editor.getModel();
    if (!monaco || !model) return;
    const decorations = [];
    for (const region of regionScopes(model)) {
      if (region.embedded) {
        decorations.push({
          range: rangeFromOffsets(monaco, model, region.headerStart, region.headerEnd),
          options: { inlineClassName: "afield-stacksl-token-directive", hoverMessage: { value: "Embedded RectSL" } },
        });
      }
      let expectingDefinitionName = false;
      for (const token of tokenize(region.source, region.bodyStart)) {
        const inlineClassName = classForToken(token, expectingDefinitionName);
        decorations.push({ range: rangeFromOffsets(monaco, model, token.start, token.end), options: { inlineClassName } });
        if (token.kind === "control" && token.text === ":") expectingDefinitionName = true;
        else if (token.kind !== "comment" && expectingDefinitionName) expectingDefinitionName = false;
      }
    }
    setCollection(editor, "_rectslHighlightDecorations", decorations);
  };

  const updateFlowLens = (editor) => {
    const monaco = monacoApi();
    const model = editor && editor.getModel && editor.getModel();
    if (!monaco || !model || editor._rectslFlowEnabled === false) {
      setCollection(editor, "_rectslFlowDecorations", []);
      return;
    }
    const position = editor.getPosition && editor.getPosition();
    if (!position) {
      setCollection(editor, "_rectslFlowDecorations", []);
      return;
    }
    const offset = model.getOffsetAt(position);
    const scope = sourceScope(model, offset);
    if (!scope) {
      setCollection(editor, "_rectslFlowDecorations", []);
      return;
    }
    const tokens = tokenize(scope.source, scope.bodyStart).filter((token) => token.kind !== "comment");
    const active = tokenAt(tokens, offset);
    if (!active) {
      setCollection(editor, "_rectslFlowDecorations", []);
      return;
    }
    const records = analyzeFlow(tokens);
    const activeRecord = records.find((record) => record.token === active);
    if (!activeRecord) {
      setCollection(editor, "_rectslFlowDecorations", []);
      return;
    }
    const sources = new Set(activeRecord.consumed.map((value) => value && value.producer).filter(Boolean));
    const consumers = new Set(activeRecord.produced.flatMap((value) => value.consumers || []));
    const decorations = [];
    sources.forEach((token) => decorations.push({
      range: rangeFromOffsets(monaco, model, token.start, token.end),
      options: { inlineClassName: "afield-stacksl-flow-source", hoverMessage: { value: "RectSL value source" } },
    }));
    consumers.forEach((token) => decorations.push({
      range: rangeFromOffsets(monaco, model, token.start, token.end),
      options: { inlineClassName: "afield-stacksl-flow-consumer", hoverMessage: { value: "RectSL future consumer" } },
    }));
    setCollection(editor, "_rectslFlowDecorations", decorations);
  };

  const updateDiagnostics = (editor) => {
    const monaco = monacoApi();
    const model = editor && editor.getModel && editor.getModel();
    if (!monaco || !monaco.editor || !model) return;
    const severityFor = (level) => {
      const S = monaco.MarkerSeverity;
      if (!S) return level === "error" ? 8 : 4;
      return level === "error" ? S.Error : S.Warning;
    };
    const markers = [];
    for (const region of regionScopes(model)) {
      for (const issue of analyzeLexical(region.source)) {
        const start = model.getPositionAt(region.bodyStart + issue.start);
        const end = model.getPositionAt(region.bodyStart + issue.end);
        markers.push({
          severity: severityFor(issue.severity),
          message: issue.message,
          source: extensionLabel,
          startLineNumber: start.lineNumber,
          startColumn: start.column,
          endLineNumber: end.lineNumber,
          endColumn: end.column,
        });
      }
    }
    monaco.editor.setModelMarkers(model, "rectsl-diagnostics", markers);
  };

  const buildContextPayload = (editor, resolveContext, options = {}) => {
    if (!editor || (!options.allowUnfocused && editor.hasTextFocus && !editor.hasTextFocus())) return null;
    const model = editor.getModel && editor.getModel();
    const position = editor.getPosition && editor.getPosition();
    if (!model || !position) return null;
    const offset = model.getOffsetAt(position);
    const scope = sourceScope(model, offset);
    if (!scope) return null;
    const tokens = tokenize(scope.source, scope.bodyStart).filter((token) => token.kind !== "comment");
    const active = tokenAt(tokens, offset);
    if (!active) return null;
    const effect = effectForToken(active);
    const resolved = resolveContext ? resolveContext(editor) : null;
    const contextKey = resolved && resolved.uid && resolved.property ? resolved.uid + "::" + resolved.property : extensionId;
    const escaped = escapeHtml(active.text || "");
    const doc = wordDocs.get(String(active.text || "")) || wordDocs.get(String(active.text || "").toLowerCase());
    const arity = '<span class="afield-stacksl-muted">(' + effect[0] + '-&gt;' + effect[1] + ')</span>';
    const signature = doc && doc.signature ? escapeHtml(doc.signature) + ' ' : '';
    const detail = doc && doc.detail ? '<div class="afield-stacksl-muted">' + escapeHtml(doc.detail) + '</div>' : '';
    const html = '<div class="afield-stacksl-context"><strong>RectSL</strong> ' +
      '<span class="afield-stacksl-word"><code>' + escaped + '</code> ' +
      '<span class="afield-stacksl-signature">' + signature + arity + '</span></span>' +
      detail + '</div>';
    return { key: contextKey + "::" + (model.getVersionId ? model.getVersionId() : 0) + "::" + offset, editor, html, isLiveValue: false };
  };

  const install = (editor, monaco, registry) => {
    if (!editor || editor._rectslExtensionInstalled) return;
    editor._rectslExtensionInstalled = true;
    editor._rectslFlowEnabled = true;
    if (editor.addAction) {
      editor.addAction({
        id: "toggle-rectsl-flow-lens",
        label: "Toggle RectSL Flow Lens",
        contextMenuGroupId: "navigation",
        contextMenuOrder: 3.6,
        run: (activeEditor) => {
          const ed = activeEditor || editor;
          ed._rectslFlowEnabled = ed._rectslFlowEnabled === false;
          updateFlowLens(ed);
        },
      });
    }
    const schedule = () => setTimeout(() => {
      updateEmbeddedHighlight(editor);
      updateFlowLens(editor);
      updateDiagnostics(editor);
    }, 0);
    if (editor.onDidChangeCursorPosition) editor.onDidChangeCursorPosition(schedule);
    if (editor.onDidChangeModelContent) editor.onDidChangeModelContent(schedule);
    if (editor.onDidChangeModel) editor.onDidChangeModel(schedule);
    // Re-highlight when a model is (re)assigned the rectsl language in place, e.g. a loose
    // .rectsl file whose language is set after the model already exists.
    const monacoForEvents = monaco || monacoApi();
    if (monacoForEvents && monacoForEvents.editor && typeof monacoForEvents.editor.onDidChangeModelLanguage === "function") {
      monacoForEvents.editor.onDidChangeModelLanguage(schedule);
    }
    schedule();
  };

  const extension = {
    id: extensionId,
    label: extensionLabel,
    languageIds: ["kotlin", extensionId],
    findEmbeddedRegions,
    install,
    updateEmbeddedHighlight,
    updateFlowLens,
    updateDiagnostics,
    buildContextPayload,
    provideCompletionItems,
  };

  if (root.FieldLanguageExtensions && typeof root.FieldLanguageExtensions.register === "function") {
    root.FieldLanguageExtensions.register(extension);
  } else {
    root.__fieldLanguageExtensionQueue = root.__fieldLanguageExtensionQueue || [];
    root.__fieldLanguageExtensionQueue.push(extension);
  }
  root.__rectslExtensionProof = { registered: true, extension, findEmbeddedRegions, tokenize, analyzeFlow, analyzeLexical };
  };

  // Register a standalone "rectsl" Monaco language so loose .rectsl files highlight when
  // opened in the editor, and get #-comment toggling and (  ) auto-close. Coloring itself
  // is done by the decoration highlighter above (which already treats a whole "rectsl"
  // model as one scope) — no separate Monarch grammar, so there is a single tokenizer.
  const registerRectSLLanguage = (monaco) => {
    if (!monaco || !monaco.languages || typeof monaco.languages.register !== "function") return;
    const languageId = "rectsl";
    const alreadyRegistered = typeof monaco.languages.getLanguages === "function" &&
      monaco.languages.getLanguages().some((language) => language && language.id === languageId);
    if (!alreadyRegistered) {
      monaco.languages.register({ id: languageId, extensions: [".rectsl"], aliases: ["RectSL", "rectsl", "rectSL"] });
    }
    monaco.languages.setLanguageConfiguration(languageId, {
      comments: { lineComment: "#" },
      brackets: [["(", ")"]],
      autoClosingPairs: [{ open: "(", close: ")" }, { open: "\"", close: "\"" }],
      surroundingPairs: [{ open: "(", close: ")" }, { open: "\"", close: "\"" }],
    });
  };

  if (typeof define === "function" && define.amd) {
    define("bithero/rectsl", ["require", "vs/editor/editor.api"], function (require, monacoModule) {
      const monaco = monacoModule && monacoModule.default ? monacoModule.default : monacoModule;
      registerRectSLLanguage(monaco);
      installRectSLExtension();
      return {};
    });
  } else {
    installRectSLExtension();
  }
})();
