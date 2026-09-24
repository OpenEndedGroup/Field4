(function() {
  const HIGHLIGHT_URL = "https://cdnjs.cloudflare.com/ajax/libs/highlight.js/11.8.0/highlight.min.js";
  let highlightPromise = null;
  let forthRegistered = false;
  let morphoRegistered = false;
  let clojureRegistered = false;
  let rectslRegistered = false;
  let glslRegistered = false;

  // A declared `data-lang` that highlight.js does not know is rendered PLAIN, never guessed.
  //
  // `highlightLine` colours one line at a time, so the old `hljs.highlightAuto` fallback was
  // re-detecting the language for every line independently, from a single line of context. On a GLSL
  // push-constant block that produced a different guess per line, and the text inside `// ...` was
  // tokenised as code and lit up as keywords -- because none of the guessed languages comment with
  // `//`. Two lines of the same block rendered correctly and three did not, which reads as a theme
  // bug rather than a lexer one.
  //
  // Measured, not guessed at, on the exact block from `gpu_state.md` against this same 11.8.0 common
  // build. `hljs.getLanguage("glsl")` is false, so every line took the fallback, and the fallback
  // returned a DIFFERENT language per line:
  //
  //     layout(push_constant) uniform dat {                    -> scss
  //     State   state;        // this frame's half ...         -> cpp
  //     State   prevState;    // last frame's half - read      -> cpp
  //     Anchors anchors;      // written once, read every ...  -> perl
  //     Heat    heat;         // zero-initialised, device- ... -> perl
  //
  // Perl is the one that does the visible damage: `read` and `local` are Perl keywords and Perl
  // comments with `#`, so those two words lit up *inside* a `//` comment while the two lines that
  // happened to guess cpp rendered perfectly. Same block, five lines, three languages.
  //
  // The CDN bundle above is highlight.js's *common* build (~38 languages). It has kotlin, bash,
  // javascript, python, swift, csharp, json, yaml, ruby and plaintext -- so those fences are fine --
  // but it does NOT have glsl, and it cannot have this repo's own languages. Guessing on those is
  // worse than not colouring them: unhighlighted code is obviously unhighlighted, whereas confidently
  // mis-highlighted code puts keywords inside comments and gives the reader no way to tell.
  //
  // To add a language, either register a grammar (see forth, morpho and clojure below, all three
  // wired up in `registerFieldLanguages`) or alias it here to a built-in whose lexical shape
  // genuinely matches -- same comment syntax above all.
  const LANGUAGE_ALIASES = {
    // GLSL used to be aliased to `cpp` here, on the grounds that it is C's lexical family: `//` and
    // `/* */` comments, braces, semicolons, C numeric literals. That is true and it is also the
    // floor rather than the ceiling -- cpp knows none of the vocabulary that carries meaning in a
    // shader, so `vec3`, `uniform`, `layout`, `gl_LaunchIDEXT` and `#version` all rendered as plain
    // identifiers and a 25-line fence came out with four blue words in it. `registerGlslLanguage`
    // below replaces the alias; the entry is gone rather than overridden, because
    // `resolveLanguage` consults this map *first* and an alias would win over the real grammar.
    // StackSL *is* a Forth: `: name ... ;` definitions, postfix words, a data stack. The grammar
    // registered below was written for it -- its FIELD_WORDS list is Field's own vocabulary
    // (`fline`, `vec2`, `plot-stack`, `println`), which is why aliasing here rather than writing a
    // second grammar is the honest move. It is not exact: StackSL's local marks (`=name`, `>name`,
    // `<name`) and property reads (`?pc.knobs`) are not Forth and fall through as plain text, which
    // is the right failure -- uncoloured, not miscoloured.
    stacksl: "forth"
  };

  /**
   * Resolve a fence's declared language to a grammar highlight.js actually has, or null.
   * Returning null means "render plain" -- it is a deliberate outcome, not a failure.
   */
  function resolveLanguage(language) {
    if (!language) return null;
    const key = String(language).toLowerCase();
    const mapped = LANGUAGE_ALIASES[key] || key;
    return hljs.getLanguage(mapped) ? mapped : null;
  }

  function registerForthLanguage() {
    if (!window.hljs || forthRegistered || hljs.getLanguage("forth")) {
      forthRegistered = true;
      return;
    }

    hljs.registerLanguage("forth", function(hljs) {
      const BUILTINS = [
        "!", "+!", ",", ".", ".r", "*", "*/", "*/mod", "+", "1+", "1-", "-", "/", "/mod", "2!",
        "2*", "2/", "2@", "2drop", "2dup", "2over", "2swap", "<", "<>", "=", ">", ">body",
        ">in", ">number", ">r", "?", "?do", "?dup", "@", "abs", "accept", "again", "align",
        "aligned", "allot", "and", "base", "begin", "bl", "blank", "c!", "c,", "c@", "case",
        "catch", "cell+", "cells", "char", "chars", "constant", "count", "cr", "create", "depth",
        "do", "does>", "drop", "dup", "else", "emit", "endcase", "endof", "evaluate", "execute",
        "exit", "false", "fill", "find", "fm/mod", "forget", "here", "hold", "i", "if", "immediate",
        "invert", "j", "leave", "literal", "loop", "lshift", "max", "min", "mod", "move", "negate",
        "nip", "of", "or", "over", "pick", "postpone", "r>", "r@", "recurse", "repeat", "roll",
        "rot", "rshift", "s>d", "see", "sm/rem", "source", "space", "spaces", "state", "swap",
        "then", "throw", "true", "tuck", "type", "u.", "u<", "u.r", "um*", "um/mod", "until",
        "variable", "while", "within", "word", "words", "xor"
      ];
      const FIELD_WORDS = [
        "@?", "@?!", "bar", "class-for", "cubicto", "cubicto3", "display", "display-channel",
        "fline", "fline-close", "fline-color", "fline-filled", "fline-lineto-v2",
        "fline-lineto-v3", "fline-moveto-v2", "fline-moveto-v3", "fline-node-color",
        "fline-stroked", "fline-thickness", "frame", "html", "lineto", "lineto3", "moveto",
        "moveto3", "newInstance", "plot-stack", "println", "stack-display", "str", "str+",
        "vec2", "vec2*", "vec2+", "vec2-", "vec2@y", "vec2@x", "vec2dot", "vec2len",
        "vec2norm", "vec3", "vec4", "watch"
      ];
      const WORD_BOUNDARY = "(?=\\s|$)";

      return {
        name: "Forth",
        aliases: ["forth", "fth", "fs"],
        case_insensitive: true,
        keywords: {
          keyword: "if else then begin while repeat until again do ?do loop +loop leave case of endof endcase recurse exit",
          built_in: BUILTINS.join(" ") + " " + FIELD_WORDS.join(" "),
          literal: "true false"
        },
        contains: [
          hljs.COMMENT("\\\\", "$"),
          hljs.COMMENT("\\(", "\\)"),
          {
            scope: "string",
            begin: "\\bs\\\"",
            end: "\\\"",
            relevance: 0
          },
          {
            scope: "string",
            begin: "\\.\\\"",
            end: "\\\"",
            relevance: 0
          },
          {
            scope: "title",
            begin: "(:|variable|constant|create|value)\\s+",
            end: WORD_BOUNDARY,
            excludeBegin: true
          },
          {
            scope: "number",
            begin: "[-+]?\\b(?:0x[0-9a-f]+|\\d+(?:\\.\\d+)?(?:e[-+]?\\d+)?)\\b",
            relevance: 0
          }
        ]
      };
    });
    forthRegistered = true;
  }

  // MorphoHDL, for `data-lang=morpho` fences. Python-shaped but not Python: `@morpho` is the only
  // decorator, SPLIT/CAT/LSLICE are language primitives rather than library calls, and truth tables
  // are written as underscore-grouped binary (0b1001_0110) that a Python lexer breaks in half.
  // `# main ...` is Field's directive -- invisible to morpho's own tokenizer, so worth marking here.
  function registerMorphoLanguage() {
    if (!window.hljs || morphoRegistered || hljs.getLanguage("morpho")) {
      morphoRegistered = true;
      return;
    }

    hljs.registerLanguage("morpho", function(hljs) {
      return {
        name: "MorphoHDL",
        aliases: ["morpho", "morphohdl"],
        keywords: {
          keyword: "def return",
          built_in: "LUT SPLIT CAT REPEAT LSLICE HSLICE",
          literal: "ZERO ONE VOID"
        },
        contains: [
          { scope: "meta", begin: "#\\s*main\\b", end: "$", relevance: 10 },
          hljs.COMMENT("#", "$"),
          { scope: "meta", begin: "@morpho\\b", relevance: 10 },
          { scope: "params", begin: "\\bfallback\\b(?=\\s*=)", relevance: 0 },
          {
            scope: "title.function",
            begin: "\\bdef\\s+",
            end: "(?=\\()",
            excludeBegin: true,
            relevance: 0
          },
          {
            scope: "number",
            begin: "\\b(?:0b[01_]+|0x[0-9a-fA-F_]+|\\d[\\d_]*)\\b",
            relevance: 0
          }
        ]
      };
    });
    morphoRegistered = true;
  }

  // Clojure, for `data-lang=clojure` fences. highlight.js's common build does not carry it -- the
  // list in the comment at the top of this file is the measured one -- so without this a Clojure
  // fence renders as plain text, and `resolveLanguage` would be right to refuse to guess.
  //
  // Written rather than aliased because nothing in the common build is lexically a Lisp: `;` to end
  // of line is a comment in none of them, `:keyword` is a label or a ternary in most, and the two
  // shapes a Clojure box in Field is *full* of -- `(.moveTo f 0 0 0)` and `ClojureInterface/thisBox`
  // -- are punctuation everywhere else. Those two get their own rules below, which is the part a
  // stock Clojure grammar would not have.
  //
  // Deliberately not attempted: rainbow parens. `highlightLine` colours one line at a time, so
  // nesting depth at the start of a line is not knowable here. The editor does do it (Monaco's
  // `bracketPairColorization`, on in BootstrapMonacoInit.kt), which is why the Clojure page shows
  // an editor capture rather than claiming it for the fences.
  function registerClojureLanguage() {
    if (!window.hljs || clojureRegistered || hljs.getLanguage("clojure")) {
      clojureRegistered = true;
      return;
    }

    hljs.registerLanguage("clojure", function(hljs) {
      // Clojure symbol characters, minus `/` so that `str/join` reads as one token that is then not
      // a keyword -- which is the correct outcome, since `join` is not a core name.
      const SYMBOL = "[a-zA-Z_!<>*?+=$%&|.-][a-zA-Z0-9_!<>*?+=$%&|.'#-]*";
      return {
        name: "Clojure",
        aliases: ["clj", "edn"],
        keywords: {
          $pattern: SYMBOL,
          keyword:
            "def defn defn- defmacro defmulti defmethod defrecord defprotocol deftype defonce " +
            "fn let letfn loop recur if if-not if-let when when-not when-let cond condp case do " +
            "doseq dotimes for while ns require import refer in-ns var quote try catch finally " +
            "throw set! new reify extend-type extend-protocol binding with-open doto " +
            "-> ->> as-> some-> some->> cond-> cond->> and or not",
          literal: "true false nil",
          built_in:
            "map mapv mapcat filter remove reduce reductions into conj cons assoc assoc-in dissoc " +
            "update update-in get get-in first second last rest next take take-while drop " +
            "drop-while range repeat repeatedly iterate cycle count sort sort-by group-by " +
            "frequencies partition partition-all interleave interpose concat reverse distinct " +
            "apply comp partial juxt identity constantly str format pr-str print println prn " +
            "keys vals seq vec set list hash-map zipmap empty? some every? contains? " +
            "inc dec min max mod rem quot abs even? odd? nil? instance? type class"
        },
        contains: [
          // `#!` only as a first line, which is why it is anchored; `;` anywhere.
          { scope: "comment", begin: "(^#!|;)", end: "$", relevance: 0 },
          // `#_` elides the form after it. Only the reader macro is marked: colouring the elided
          // form needs paren matching, which a line-at-a-time highlighter does not have.
          { scope: "meta", begin: "#_", relevance: 10 },
          { scope: "regexp", begin: '#"', end: '"', contains: [hljs.BACKSLASH_ESCAPE], relevance: 10 },
          hljs.QUOTE_STRING_MODE,
          // `:keyword`, `::auto-resolved`, `:ns/qualified`. hljs's own Lisp grammars scope these
          // `symbol`, and the docs theme colours that distinctly from `keyword`.
          { scope: "symbol", begin: "::?" + SYMBOL + "(?:/" + SYMBOL + ")?", relevance: 0 },
          // Character literals, including the named ones. Before the number rule: `\1` is a char.
          {
            scope: "string",
            begin: "\\\\(?:newline|space|tab|formfeed|backspace|return|u[0-9a-fA-F]{4}|.)",
            relevance: 0
          },
          // Ratios (1/2), bigints (42N), bigdecimals (1.5M) and hex, none of which a C-family
          // number rule gets right.
          {
            scope: "number",
            begin: "\\b-?(?:0[xX][0-9a-fA-F]+|\\d+/\\d+|\\d+(?:\\.\\d*)?(?:[eE][-+]?\\d+)?[MN]?)\\b",
            relevance: 0
          },
          // `^:private`, `^String`, `#^{:doc "…"}`.
          { scope: "meta", begin: "#?\\^:?" + SYMBOL, relevance: 0 },
          // The name a `def*` form defines, rather than the definer: `(defn polyline [pts] …)`
          // should read as a definition of `polyline`. hljs 11's multi-class `begin` array.
          {
            begin: [/\(/, /def[a-z-]*|fn/, /\s+/, new RegExp(SYMBOL)],
            beginScope: { 2: "keyword", 4: "title.function" },
            relevance: 0
          },
          // Interop, which is most of what a Field box does: `(.moveTo f …)`, `(.getChildren b)`.
          { scope: "built_in", begin: "\\.[a-zA-Z][a-zA-Z0-9_]*\\b", relevance: 0 },
          // A capitalised name used as a class: `FLine.`, `ClojureInterface/thisBox`, `Math/PI`.
          { scope: "title.class", begin: "\\b[A-Z][a-zA-Z0-9_]*(?=[./])", relevance: 0 }
        ]
      };
    });
    clojureRegistered = true;
  }

  // RectSL, for `data-lang=rectsl` fences. Written rather than aliased to `forth` (which is how
  // StackSL is handled above) because the two differ on the one thing that matters most: RectSL
  // comments with `#` and Forth does not, so a Forth alias would tokenise every `# eight petals
  // fanned around a centre` as code and light up `centre`-shaped words inside it -- the exact
  // failure the note at the top of this file measured on GLSL-as-perl.
  //
  // The valuable part is lexical rather than a word list. RectSL says what a word does in its
  // spelling: `->name` is a mutator, and the cheatsheet's own Model section leads with "the arrow
  // is part of the word, not optional punctuation". So the mutator rule is a regex, not a list,
  // and a word added to the language is coloured correctly on the day it is added. `'( … )`
  // quotes, `0x` colour literals, `"ids"` and `#` comments are likewise shape, not vocabulary.
  //
  // The word list below is the ordinary (non-arrow) vocabulary, generated from the `word("…")`
  // registrations in `src/jvmMain/kotlin/afield/rectsl/` -- 229 of them, which is every word the
  // VM has. An unlisted word falls through as plain text, which is the right failure: uncoloured,
  // not miscoloured.
  //
  // Deliberately not attempted: colouring a whole `'( … )` quote as a unit, and the editor's
  // amber/blue split between operator words and structural ones. Both need more than a line of
  // context -- a quote spans lines and `highlightLine` colours one at a time -- and the editor
  // does do them (`src/commonMain/resources/rectsl.js`, decoration-based, whole-buffer).
  function registerRectSLLanguage() {
    if (!window.hljs || rectslRegistered || hljs.getLanguage("rectsl")) {
      rectslRegistered = true;
      return;
    }

    hljs.registerLanguage("rectsl", function(hljs) {
      // A RectSL word may end in `?`, `!`, `*` or `+` (`atom?`, `tcb!`, `time*`, `value+`) and may
      // carry digits and hyphens inside it (`f0`, `ease-in-out`, `kolakoski-gaps`).
      const WORD = /[a-z][a-z0-9-]*[?!*+]?/;
      return {
        name: "RectSL",
        aliases: ["rect-sl"],
        case_insensitive: false,
        keywords: {
          $pattern: WORD,
          keyword:
            // scene tree, selection, and the words that walk or filter it
            "rect group end end-group id rect? group? copy clone copy-deep clone-deep " +
            "all roots selected children descendants rects groups tagged tagged? each map filter where " +
            // loops, arrays, and the per-copy context they publish
            "times repeat range apply defer i n loop-count array grid radial arc spiral phyllotaxis sunflower " +
            "f f0 fc ic col row cols rows colc rowc " +
            // the Functions bridge
            "ease ease-in ease-out ease-in-out ease-smoothstep smooth gaussian noise fbm sample reflect tile " +
            // tracks, and reading a channel's curve back out
            "tcb linear hold tcb! track linear-track hold-track track-ref free-param " +
            "x-track y-track w-track h-track rot-track rotation-track scale-x-track scale-y-track " +
            "alpha-track z-track layer-track " +
            "delay-time scale-time reverse-time loop-time pingpong-time time+ time* snap-time " +
            "value+ value* clamp-values keys-before keys-after keys-between " +
            // data and the chart / plotting layers
            "data linspace normalize extent dmin dmax data-len data-at data-map colormap cmap " +
            "bars heat heatmap scatter axes line plot area points colorbar legend " +
            // the musical layer: clock, events, patterns, and their transforms
            "beats cycles cycle-secs scene-cycles pat strip t dur cyc step atom atom? param param? " +
            "mini sine cosine saw isaw tri square rand perlin nums texts segment pat-range " +
            "quantise quantize bipolar euclid kolakoski kolakoski-gaps hold-pat " +
            "fast slow rev palindrome iter iter-back ply stut linger pat-rot shuffle degrade-by " +
            "swing swing-by press press-by pat-delay every off superimpose inside outside " +
            "sometimes-by stack-pat cat-pat " +
            // stack shuffles and scalar math
            "dup drop swap over add sub mul div mod pow min max neg negate abs floor ceil fract " +
            "sqrt sin cos clamp sat saturate mix lerp smoothstep eq neq lt lte gt gte and or not",
          // Red, with the numbers: `pi` and `tau` are constants, and the unit and fit words are
          // the closest thing the language has to an enum. None is a registered word -- `->wall`
          // and `->fit` read them forward out of the source -- so they exist only here.
          literal: "pi tau meters meter m pixels pixel px abstract units unit contain cover stretch"
        },
        contains: [
          // `#` to end of line, which also covers the `#rectsl` header -- it *is* a comment.
          { scope: "comment", begin: /#/, end: /$/, relevance: 0 },
          hljs.QUOTE_STRING_MODE,
          // The mutators, by shape rather than by name. Ends with an optional `*` for `->opacity*`.
          { scope: "symbol", begin: /->[a-z][a-z0-9-]*\*?/, relevance: 10 },
          // A quote's delimiters. Only the delimiters: what is inside them is ordinary RectSL and
          // should keep reading as itself.
          { scope: "symbol", begin: /'\(|\)/, relevance: 10 },
          // Paint, which the editor also colours as a writer rather than as vocabulary.
          { scope: "symbol", begin: /\b(?:solid|rgba?|material)\b/, relevance: 0 },
          // Colour literals, before the number rule so `0xffaa33` is not read as `0` then a word.
          { scope: "number", begin: /\b0[xX][0-9a-fA-F]+\b/, relevance: 10 },
          { scope: "number", begin: /-?\b(?:\d+\.\d*|\.\d+|\d+)(?:[eE][-+]?\d+)?\b/, relevance: 0 },
          // The symbolic operators, longest first so `<=` is not read as `<` then `=`. `=` is also
          // the optional separator inside a track key (`0=0.2`), which is the same colour either way.
          { scope: "operator", begin: /<=|>=|!=|<>|[-+*/<>=]/, relevance: 0 },
          // `: name … ;` — the name being defined, and the delimiters that frame a definition or
          // close a rect body.
          {
            begin: [/:/, /\s+/, WORD],
            beginScope: { 1: "operator", 3: "title.function" },
            relevance: 10
          },
          { scope: "operator", begin: /;/, relevance: 0 }
        ]
      };
    });
    rectslRegistered = true;
  }

  // GLSL, for `data-lang=glsl` fences -- 56 of them across field_probes, gpu_state, path_tracing,
  // ray_tracing_examples, neural_particles, flow_grain, afield_four_graphics and the chain pages.
  // The common highlight.js build has no glsl at all; this file used to alias it to `cpp`, which
  // got the comments, braces and numbers right and then stopped. Everything that carries meaning in
  // a shader -- the types, the storage and layout qualifiers, the `gl_` builtins, the preprocessor,
  // the builtin function library -- is vocabulary cpp does not have, so it all fell through as
  // plain identifiers.
  //
  // The vocabulary is mirrored from the editor's own GLSL grammar,
  // `src/commonMain/resources/glsl.js` (Monarch, `bithero/glsl`), so a fence and the editor agree
  // about what a word is. Where that grammar lists a family exhaustively -- 180-odd sampler,
  // texture and image types -- this one matches the family by shape instead, because the list is
  // generated in spelling (`isampler2DArrayShadow` is prefix + dimension + array + shadow) and a
  // regex cannot go stale the way a transcribed list can.
  //
  // Three things here that a stock GLSL grammar would not have:
  //
  //   - Field's template spans. `{% include "fieldProbe" %}` and `{{ pc.dec }}` are how a Field
  //     shader pulls in a preamble or a generated block, they appear in the docs corpus more often
  //     than any GLSL builtin except `vec3`, and they are not GLSL -- so they are scoped `meta`,
  //     the same colour as the `#` preprocessor, which is what they are the Field-side equivalent
  //     of. The editor colours them too (`delimiter.template`).
  //   - `field…` helpers as builtins, by shape (`fieldProbe`, `fieldStatsRecord`,
  //     `fieldStatsQuantile`). Inside a GLSL fence, an identifier spelled that way came from an
  //     injected Field preamble, so it is a builtin from the shader author's point of view. Shape
  //     rather than a list, for the same reason as the sampler types.
  //   - Layout qualifier names. `set`, `binding`, `push_constant`, `std430`, `local_size_x`,
  //     `rgba8`, `buffer_reference` are meaningful only inside `layout(…)` and are not keywords
  //     anywhere else, so they are scoped `attr` and only within the parens.
  //
  // A builtin function is only coloured as one when it is *called* (`BUILTIN_CALL` requires the
  // open paren). `texture` is the builtin, `texture2D` is a type, and `length`, `data` and `mix`
  // are all plausible variable names in this corpus -- gating on the paren keeps a variable named
  // `normal` from reading as `normalize`'s cousin.
  //
  // Deliberately not attempted: `/* … */` spanning lines. `highlightLine` colours one line at a
  // time, so the continuation lines of a block comment are not knowable here -- the same limit the
  // cpp alias had, and the reason the corpus uses `//`.
  function registerGlslLanguage() {
    if (!window.hljs || glslRegistered || hljs.getLanguage("glsl")) {
      glslRegistered = true;
      return;
    }

    hljs.registerLanguage("glsl", function(hljs) {
      // Control flow and declaration structure, plus the storage, precision and interpolation
      // qualifiers -- `storage` and `keywords` in the editor's grammar, one colour here because a
      // fence has fewer colours than an editor theme does.
      const KEYWORDS = [
        "if", "else", "for", "while", "do", "switch", "case", "default", "break", "continue",
        "return", "discard", "struct", "subroutine",
        "in", "out", "inout", "uniform", "buffer", "shared", "layout", "attribute", "varying",
        "precision", "highp", "mediump", "lowp", "const", "centroid", "sample", "patch",
        "smooth", "flat", "noperspective", "coherent", "volatile", "restrict", "readonly",
        "writeonly",
        // Vulkan ray tracing storage classes: the qualifier that makes a variable a hit attribute
        // or a ray payload. GLSL 4.6 + GL_EXT_ray_tracing.
        "hitAttributeEXT", "rayPayloadEXT", "rayPayloadInEXT", "callableDataEXT",
        "callableDataInEXT", "shaderRecordEXT"
      ];

      // The scalar types and the handful of opaque ones that are not spelled as a family. The
      // vector, matrix, sampler, texture and image families are matched by shape below.
      const TYPES = [
        "void", "bool", "int", "uint", "float", "double", "atomic_uint",
        "accelerationStructureEXT", "rayQueryEXT"
      ];

      // GLSL 4.6's builtin function library, in the order the spec groups it.
      const BUILTIN_FUNCTIONS = [
        // angle and trigonometry, exponential
        "radians", "degrees", "sin", "cos", "tan", "asin", "acos", "atan", "sinh", "cosh", "tanh",
        "asinh", "acosh", "atanh", "pow", "exp", "log", "exp2", "log2", "sqrt", "inversesqrt",
        // common
        "abs", "sign", "floor", "trunc", "round", "roundEven", "ceil", "fract", "mod", "modf",
        "min", "max", "clamp", "mix", "step", "smoothstep", "isnan", "isinf", "fma", "frexp",
        "ldexp", "floatBitsToInt", "floatBitsToUint", "intBitsToFloat", "uintBitsToFloat",
        // packing
        "packUnorm2x16", "packSnorm2x16", "packUnorm4x8", "packSnorm4x8", "unpackUnorm2x16",
        "unpackSnorm2x16", "unpackUnorm4x8", "unpackSnorm4x8", "packHalf2x16", "unpackHalf2x16",
        "packDouble2x32", "unpackDouble2x32",
        // geometry and matrices
        "length", "distance", "dot", "cross", "normalize", "faceforward", "reflect", "refract",
        "matrixCompMult", "outerProduct", "transpose", "determinant", "inverse",
        // vector relational
        "lessThan", "lessThanEqual", "greaterThan", "greaterThanEqual", "equal", "notEqual",
        "any", "all", "not",
        // integer
        "uaddCarry", "usubBorrow", "umulExtended", "imulExtended", "bitfieldExtract",
        "bitfieldInsert", "bitfieldReverse", "bitCount", "findLSB", "findMSB",
        // texture and image
        "textureSize", "textureQueryLod", "textureQueryLevels", "textureSamples", "texture",
        "textureProj", "textureLod", "textureOffset", "texelFetch", "texelFetchOffset",
        "textureProjOffset", "textureLodOffset", "textureProjLod", "textureProjLodOffset",
        "textureGrad", "textureGradOffset", "textureProjGrad", "textureProjGradOffset",
        "textureGather", "textureGatherOffset", "textureGatherOffsets",
        "imageSize", "imageSamples", "imageLoad", "imageStore", "imageAtomicAdd", "imageAtomicMin",
        "imageAtomicMax", "imageAtomicAnd", "imageAtomicOr", "imageAtomicXor",
        "imageAtomicExchange", "imageAtomicCompSwap", "subpassLoad",
        // derivatives and interpolation
        "dFdx", "dFdy", "dFdxFine", "dFdyFine", "dFdxCoarse", "dFdyCoarse", "fwidth", "fwidthFine",
        "fwidthCoarse", "interpolateAtCentroid", "interpolateAtSample", "interpolateAtOffset",
        // atomics, barriers, invocation control
        "atomicAdd", "atomicMin", "atomicMax", "atomicAnd", "atomicOr", "atomicXor",
        "atomicExchange", "atomicCompSwap", "atomicCounter", "atomicCounterIncrement",
        "atomicCounterDecrement", "barrier", "memoryBarrier", "memoryBarrierAtomicCounter",
        "memoryBarrierBuffer", "memoryBarrierShared", "memoryBarrierImage", "groupMemoryBarrier",
        "emitVertex", "endPrimitive",
        // Vulkan ray tracing and debug printf
        "traceRayEXT", "reportIntersectionEXT", "ignoreIntersectionEXT", "terminateRayEXT",
        "executeCallableEXT", "rayQueryInitializeEXT", "rayQueryProceedEXT",
        "rayQueryTerminateEXT", "rayQueryGetIntersectionTEXT",
        "rayQueryGetIntersectionTypeEXT", "debugPrintfEXT"
      ];

      // The words Field's own template tags are built from -- `templateWords` in the editor grammar.
      const TEMPLATE_WORDS = [
        "if", "else", "elif", "elseif", "endif", "for", "endfor", "while", "endwhile",
        "include", "copyPC"
      ];

      // The scalar types are a mode rather than a `keywords` entry because a `contains` rule wins
      // over keyword matching, and the rule that colours a call (`USER_CALL`) would otherwise take
      // `int(x)`, `uint(bits)` and `float(n)` -- GLSL's constructor-syntax casts, which the corpus
      // is full of -- and colour them as functions the shader defines.
      const SCALAR_TYPE = new RegExp("\\b(?:" + TYPES.join("|") + ")\\b");
      // `vec3`, `ivec4`, `mat4`, `dmat3x2`. One regex rather than 40 entries.
      const VECTOR_OR_MATRIX_TYPE = /\b(?:[ibud]?vec[234]|d?mat[234](?:x[234])?)\b/;
      // `sampler2D`, `isampler2DArray`, `image2D`, `texture2DMSArray`, `usampler2DRectShadow`,
      // `subpassInputMS`, and bare `sampler`/`samplerShadow` for a separate-sampler binding. The
      // longer spellings come first so `samplerShadow` is not read as `sampler` plus a word.
      const OPAQUE_TYPE =
        /\b(?:[iu]?(?:sampler|texture|image)(?:1D|2D|3D|Cube|Buffer|2DRect)(?:Array)?(?:Shadow|MS(?:Array)?)?|[iu]?subpassInput(?:MS)?|samplerShadow|sampler)\b/;
      const BUILTIN_CALL = new RegExp("\\b(?:" + BUILTIN_FUNCTIONS.join("|") + ")(?=\\s*\\()");
      const TEMPLATE_WORD = new RegExp("\\b(?:" + TEMPLATE_WORDS.join("|") + ")\\b");
      // A call that is not a builtin and not a type constructor. The control-flow words are
      // followed by `(` too, and a `contains` rule wins over keyword matching, so they have to be
      // excluded here or `if (` would render as a function named `if`.
      const USER_CALL =
        /\b(?!(?:if|for|while|switch|return|do|else|layout|struct)\b)[A-Za-z_]\w*(?=\s*\()/;

      return {
        name: "GLSL",
        aliases: ["vert", "frag", "comp", "geom", "rgen", "rchit", "rahit", "rmiss", "rint"],
        case_insensitive: false,
        keywords: {
          keyword: KEYWORDS.join(" "),
          literal: "true false"
        },
        contains: [
          hljs.C_LINE_COMMENT_MODE,
          hljs.C_BLOCK_COMMENT_MODE,
          // Field's template expression and tag spans, delimiters included: not GLSL, and the
          // reader should be able to see that at a glance.
          {
            scope: "meta",
            begin: /\{\{/,
            end: /\}\}/,
            relevance: 10,
            contains: [
              hljs.QUOTE_STRING_MODE,
              { scope: "attr", match: /[A-Za-z_][\w.]*/ }
            ]
          },
          {
            scope: "meta",
            begin: /\{%/,
            end: /%\}/,
            relevance: 10,
            contains: [
              hljs.QUOTE_STRING_MODE,
              { scope: "keyword", match: TEMPLATE_WORD },
              { scope: "attr", match: /[A-Za-z_][\w.]*/ }
            ]
          },
          // The preprocessor: `#version 460`, `#extension … : require`, `#include "…"`, and the
          // conditional family. Coloured as one span, with the version number and the include path
          // keeping their own colours inside it.
          {
            scope: "meta",
            begin: /^[ \t]*#[ \t]*[a-z_]+/,
            end: /$/,
            relevance: 10,
            contains: [
              hljs.QUOTE_STRING_MODE,
              { scope: "string", begin: /</, end: />/ },
              { scope: "number", match: /\b\d+\b/ }
            ]
          },
          // `gl_FragCoord`, `gl_LaunchIDEXT`, `gl_RayFlagsOpaqueEXT`. By prefix, because the `gl_`
          // namespace is reserved for exactly this and every extension adds to it.
          { scope: "built_in", match: /\bgl_[A-Za-z_]\w*/, relevance: 10 },
          // Field's injected helpers, from `{% include "fieldProbe" %}` and friends.
          { scope: "built_in", match: /\bfield(?:Probe|Stats)[A-Za-z0-9_]*/, relevance: 10 },
          // `layout(set = 0, binding = 1, std430)`. The qualifier names are `attr` and only here.
          {
            begin: [/\blayout\b/, /[ \t]*/, /\(/],
            beginScope: { 1: "keyword" },
            end: /\)/,
            relevance: 10,
            contains: [
              { scope: "attr", match: /\b[a-z_]\w*\b/ },
              { scope: "number", match: /\b\d+\b/ }
            ]
          },
          { scope: "type", match: SCALAR_TYPE, relevance: 0 },
          { scope: "type", match: VECTOR_OR_MATRIX_TYPE, relevance: 0 },
          { scope: "type", match: OPAQUE_TYPE, relevance: 0 },
          { scope: "built_in", match: BUILTIN_CALL, relevance: 0 },
          // `struct Payload {` — the name being declared.
          {
            begin: [/\bstruct\b/, /\s+/, /[A-Za-z_]\w*/],
            beginScope: { 1: "keyword", 3: "type" },
            relevance: 10
          },
          { scope: "title.function", match: USER_CALL, relevance: 0 },
          // An UpperCamel identifier that is not a call: in this corpus that is a struct or an
          // interface block -- `State state;`, `Material material;`, `Payload payload;`. A
          // heuristic, and the one place this grammar guesses: an UpperCamel *function* declared
          // without a call site on the same line would read as a type. Accepted because the
          // struct-heavy pages (gpu_state, path_tracing) are the ones that need it, and because
          // being read as a type is a near miss rather than a keyword inside a comment.
          { scope: "type", match: /\b[A-Z][A-Za-z0-9_]*\b/, relevance: 0 },
          // C numeric literals, with GLSL's `u`/`f`/`lf` suffixes. `1.0f`, `3u`, `0xffu`, `1e-3`.
          {
            scope: "number",
            match: /\b0[xX][0-9a-fA-F]+[uU]?|\b\d+[uU]\b|(?:\b\d+\.\d*|\.\d+|\b\d+)(?:[eE][-+]?\d+)?(?:[fF]|lf|LF)?/,
            relevance: 0
          }
        ]
      };
    });
    glslRegistered = true;
  }

  /**
   * Register every grammar this repo adds to highlight.js.
   *
   * One function because there are three call sites and they used to disagree: `highlightLine`
   * registered Forth only, so a `data-lang=morpho` fence rendered plain unless `ensureHighlight`
   * had run first. Adding a language should be one edit, not three.
   */
  function registerFieldLanguages() {
    registerForthLanguage();
    registerMorphoLanguage();
    registerClojureLanguage();
    registerRectSLLanguage();
    registerGlslLanguage();
    registerFieldMLLanguage();
    registerReferenceLanguages();
  }

  function registerReferenceLanguages() {
    if (!window.hljs) return;
    // Keep Kotlin's string/comment handling; add lexical call and type tokens for
    // short signatures, where the standard grammar otherwise colours almost nothing.
    if (!hljs.getLanguage("kotlin-reference")) {
      hljs.registerLanguage("kotlin-reference", function(hljs) {
        const kotlin = hljs.getLanguage("kotlin").rawDefinition();
        return {
          ...kotlin,
          name: "Kotlin reference",
          contains: [...kotlin.contains,
            { className: "title.function", match: /\b(?!if\b|when\b|for\b|while\b|catch\b)[A-Za-z_]\w*(?=\s*\()/ },
            { className: "type", match: /\b[A-Z][A-Za-z0-9_]*/ },
            { className: "punctuation", match: /[().,{}\[\]:;]/ }
          ]
        };
      });
    }
    if (!hljs.getLanguage("petri")) {
      hljs.registerLanguage("petri", function(hljs) {
        return {
          name: "Petri notation",
          keywords: { keyword: "step event timed order priority round-robin random carry black swap stack cat fastcat rev palindrome degrade fast2 slow2 sum inc count" },
          contains: [hljs.C_LINE_COMMENT_MODE, hljs.QUOTE_STRING_MODE, hljs.APOS_STRING_MODE,
            { className: "meta", match: /#petri\b|!(?:timing|grid|conflict|seed)\b/ },
            { className: "attr", match: /\b(?:delay|pri|w)(?==)/ },
            { className: "number", match: /#?-?\b\d+(?:\.\d+)?(?:\/\d+)?/ },
            { className: "operator", match: /-\[|\]->|=>|[@:^+*~&=,]/ }
          ]
        };
      });
    }
    if (!hljs.getLanguage("fnrewrite")) {
      hljs.registerLanguage("fnrewrite", function(hljs) {
        return {
          name: "Function rewrite",
          contains: [hljs.C_LINE_COMMENT_MODE, hljs.HASH_COMMENT_MODE,
            { begin: /@\{|\$\{/, end: /\}/, subLanguage: "kotlin",
              contains: [{ begin: /\{/, end: /\}/, contains: ["self"] }] },
            hljs.QUOTE_STRING_MODE, hljs.C_NUMBER_MODE,
            { className: "variable", match: /\?[A-Za-z_]\w*/ },
            { className: "operator", match: /=>|->|==|!=|>=|<=|[>|&!*=+\/-]/ },
            { className: "title.function", match: /\b[A-Za-z_]\w*(?=\s*\()/ }
          ]
        };
      });
    }
  }

  // Match FieldML's tokenizer, including nested (* ... *) comments. OCaml's
  // grammar is not a substitute: FieldML also has // comments and its own types.
  function registerFieldMLLanguage() {
    if (!window.hljs || hljs.getLanguage("fieldml")) return;
    hljs.registerLanguage("fieldml", function(hljs) {
      return {
        name: "FieldML",
        keywords: {
          keyword: "let in fun if then else type match with",
          literal: "true false",
          type: "Int Float Bool String Unit Vec2 Vec3 Vec4 FLine Host"
        },
        contains: [
          hljs.C_LINE_COMMENT_MODE,
          hljs.COMMENT(/\(\*/, /\*\)/, { contains: ["self"] }),
          hljs.QUOTE_STRING_MODE,
          { className: "number", match: /\b\d+(?:\.\d+)?/ },
          { className: "type", match: /'[a-z][\w']*|\b[A-Z][\w']*/ },
          { className: "operator", match: /\|>|->|==|!=|<=|>=|&&|\|\||\+\+|::|[=+*\/<>-]/ }
        ]
      };
    });
  }

  function ensureHighlight() {
    if (window.hljs) {
      registerFieldLanguages();
      return Promise.resolve();
    }
    if (highlightPromise) return highlightPromise;
    highlightPromise = new Promise((resolve, reject) => {
      const script = document.createElement("script");
      script.src = HIGHLIGHT_URL;
      script.crossOrigin = "anonymous";
      script.referrerPolicy = "no-referrer";
      script.onload = () => {
        registerFieldLanguages();
        resolve();
      };
      script.onerror = (err) => reject(err);
      document.head.appendChild(script);
    }).catch((err) => {
      console.warn("highlight.js failed to load", err);
    });
    return highlightPromise;
  }

  function normalizeBlock(text) {
    if (!text) return "";
    const lines = text.replace(/\r\n?/g, "\n").split("\n");
    while (lines.length && lines[0].trim() === "") lines.shift();
    while (lines.length && lines[lines.length - 1].trim() === "") lines.pop();
    let indent = null;
    for (const line of lines) {
      if (!line.trim()) continue;
      const match = line.match(/^\s*/);
      const spaces = match ? match[0].length : 0;
      indent = indent === null ? spaces : Math.min(indent, spaces);
    }
    if (indent && indent > 0) {
      return lines.map((l) => l.slice(indent)).join("\n");
    }
    return lines.join("\n");
  }

  function toLines(text) {
    if (!text) return [];
    const arr = text.split("\n");
    if (arr.length && arr[0] === "") arr.shift();
    if (arr.length && arr[arr.length - 1] === "") arr.pop();
    return arr;
  }

  // Parse `:::fold [label]` / `:::endfold` region markers out of a static block. The marker
  // lines are removed (they never render or execute); the enclosed lines stay in the code and
  // are recorded as a collapsible region [start, end) in the CLEANED line indexing.
  function parseFoldMarkers(code) {
    const beginRe = /^\s*:::fold(?:\s+(.*\S))?\s*$/;
    const endRe = /^\s*:::endfold\s*$/;
    const lines = toLines(code);
    const out = [];
    const regions = [];
    let open = null;
    let id = 0;
    for (const line of lines) {
      const begin = line.match(beginRe);
      if (begin) {
        open = { start: out.length, label: (begin[1] || "").trim() };
        continue;
      }
      if (endRe.test(line)) {
        if (open) {
          regions.push({ id: id++, start: open.start, end: out.length, label: open.label, expanded: false });
          open = null;
        }
        continue;
      }
      out.push(line);
    }
    if (open) regions.push({ id: id++, start: open.start, end: out.length, label: open.label, expanded: false });
    return { code: out.join("\n"), regions };
  }

  function commonPrefixLen(aLines, bLines) {
    const len = Math.min(aLines.length, bLines.length);
    let i = 0;
    while (i < len && aLines[i] === bLines[i]) i += 1;
    return i;
  }

  function commonSuffixLen(aLines, bLines, skipPrefix = 0) {
    const lenA = aLines.length;
    const lenB = bLines.length;
    const maxLen = Math.min(lenA, lenB) - skipPrefix;
    let i = 0;
    while (i < maxLen && aLines[lenA - 1 - i] === bLines[lenB - 1 - i]) i += 1;
    return i;
  }

  function diffMyers(a, b) {
    const N = a.length;
    const M = b.length;
    const max = N + M;
    const offset = max;
    const V = new Array(2 * max + 1).fill(-1);
    const trace = [];

    V[offset + 1] = 0;

    for (let d = 0; d <= max; d++) {
      const Vd = V.slice();
      trace.push(Vd);
      for (let k = -d; k <= d; k += 2) {
        const idx = offset + k;
        let x;
        if (k === -d || (k !== d && V[idx - 1] < V[idx + 1])) {
          x = V[idx + 1];
        } else {
          x = V[idx - 1] + 1;
        }
        let y = x - k;
        while (x < N && y < M && a[x] === b[y]) {
          x += 1;
          y += 1;
        }
        V[idx] = x;
        if (x >= N && y >= M) {
          trace[trace.length - 1] = V.slice();
          return backtrack(trace, a, b, offset);
        }
      }
    }
    return [];
  }

  function backtrack(trace, a, b, offset) {
    let x = a.length;
    let y = b.length;
    const ops = [];

    for (let d = trace.length - 1; d >= 0; d--) {
      const V = trace[d];
      const k = x - y;
      const idx = offset + k;
      let prevK;
      if (k === -d || (k !== d && V[idx - 1] < V[idx + 1])) {
        prevK = k + 1;
      } else {
        prevK = k - 1;
      }
      const prevX = V[offset + prevK];
      const prevY = prevX - prevK;

      while (x > prevX && y > prevY) {
        ops.push({ type: "equal", line: a[x - 1] });
        x -= 1;
        y -= 1;
      }

      if (d === 0) break;

      if (x === prevX) {
        ops.push({ type: "insert", line: b[prevY] });
        y -= 1;
      } else {
        ops.push({ type: "delete", line: a[prevX] });
        x -= 1;
      }
    }

    return ops.reverse();
  }

  function diffChars(a, b) {
    const m = a.length;
    const n = b.length;
    const dp = Array.from({ length: m + 1 }, () => Array(n + 1).fill(0));
    for (let i = m - 1; i >= 0; i--) {
      for (let j = n - 1; j >= 0; j--) {
        dp[i][j] = a[i] === b[j] ? dp[i + 1][j + 1] + 1 : Math.max(dp[i + 1][j], dp[i][j + 1]);
      }
    }

    const segs = [];
    let i = 0;
    let j = 0;
    while (i < m || j < n) {
      if (i < m && j < n && a[i] === b[j]) {
        segs.push({ type: "eq", text: a[i] });
        i += 1; j += 1;
      } else if (j < n && (i === m || dp[i][j + 1] >= dp[i + 1][j])) {
        segs.push({ type: "ins", text: b[j] });
        j += 1;
      } else {
        segs.push({ type: "del", text: a[i] });
        i += 1;
      }
    }

    const merged = [];
    for (const s of segs) {
      if (merged.length && merged[merged.length - 1].type === s.type) {
        merged[merged.length - 1].text += s.text;
      } else {
        merged.push({ ...s });
      }
    }
    return merged;
  }

  function buildSteps(from, to) {
    const a = toLines(from);
    const b = toLines(to);
    const ses = diffMyers(a, b);

    const stepsOut = [];
    let cursor = 0;
    for (let i = 0; i < ses.length;) {
      const op = ses[i];
      if (op.type === "equal") {
        cursor += 1;
        i += 1;
        continue;
      }

      const idx = cursor;
      const dels = [];
      const ins = [];

      while (i < ses.length && ses[i].type === "delete") {
        dels.push(ses[i].line);
        i += 1;
      }
      while (i < ses.length && ses[i].type === "insert") {
        ins.push(ses[i].line);
        i += 1;
      }

      if (dels.length && ins.length) {
        stepsOut.push({ kind: "replace", index: idx, deletes: dels, inserts: ins });
        cursor += ins.length;
      } else if (dels.length) {
        stepsOut.push({ kind: "delete", index: idx, deletes: dels });
      } else if (ins.length) {
        stepsOut.push({ kind: "insert", index: idx, inserts: ins });
        cursor += ins.length;
      }
    }

    return stepsOut;
  }

  function escapeHtml(str) {
    return str
      .replace(/&/g, "&amp;")
      .replace(/</g, "&lt;")
      .replace(/>/g, "&gt;")
      .replace(/"/g, "&quot;")
      .replace(/'/g, "&#39;");
  }

  function highlightLine(line, language) {
    if (!window.hljs) return escapeHtml(line === "" ? "\u00a0" : line);
    registerFieldLanguages();
    const source = line === "" ? " " : line;
    const resolved = resolveLanguage(language);
    // No guessing: an unknown declared language renders as escaped plain text. See LANGUAGE_ALIASES.
    // An empty line takes the same non-breaking space the no-hljs path above uses, so a blank
    // line in an unhighlighted fence keeps its height instead of collapsing.
    if (!resolved) return escapeHtml(line === "" ? "\u00a0" : line);
    const html = hljs.highlight(source, { language: resolved, ignoreIllegals: true }).value;
    return line === "" ? html.replace(" ", "&nbsp;") : html;
  }

  function applyInlineToHighlighted(html, segments) {
    const container = document.createElement("div");
    container.innerHTML = html === "" ? "&nbsp;" : html;
    let segIdx = 0;
    let segOffset = 0;

    function walk(node) {
      if (segIdx >= segments.length) return;
      if (node.nodeType === Node.TEXT_NODE) {
        const text = node.nodeValue || "";
        const parts = [];
        let pos = 0;
        while (pos < text.length && segIdx < segments.length) {
          const seg = segments[segIdx];
          const remainingSeg = seg.text.length - segOffset;
          const remainingText = text.length - pos;
          const take = Math.min(remainingSeg, remainingText);
          const chunk = text.slice(pos, pos + take);

          if (seg.type === "eq") {
            parts.push(document.createTextNode(chunk));
          } else {
            const span = document.createElement("span");
            span.className = seg.type === "ins" ? "ch-ins" : "ch-del";
            span.textContent = chunk;
            parts.push(span);
          }

          pos += take;
          segOffset += take;
          if (segOffset >= seg.text.length) {
            segIdx += 1;
            segOffset = 0;
          }
        }
        if (pos < text.length) {
          parts.push(document.createTextNode(text.slice(pos)));
        }

        const parent = node.parentNode;
        if (parent) {
          for (const part of parts) parent.insertBefore(part, node);
          parent.removeChild(node);
        }
      } else if (node.nodeType === Node.ELEMENT_NODE) {
        const children = Array.from(node.childNodes);
        for (const child of children) walk(child);
      }
    }

    walk(container);
    return container.innerHTML;
  }

  class DiffPlayer {
    constructor(container, options) {
      this.container = container;
      this.language = options.language || "kotlin";
      // The line number the block's first line carries. 1 for an ordinary example; for a fence cut
      // out of a real file it is where that fence starts, so the gutter reads as the file does.
      this.startLine = Number.isFinite(options.startLine) && options.startLine > 0
        ? options.startLine
        : 1;
      this.codeStart = options.before || "";
      this.codeEnd = options.after || options.before || "";
      // Pull out :::fold/:::endfold regions before anything else counts lines or diffs.
      const foldStart = parseFoldMarkers(this.codeStart);
      const foldEnd = parseFoldMarkers(this.codeEnd);
      this.codeStart = foldStart.code;
      this.codeEnd = foldEnd.code;
      this.foldRegions = foldStart.regions;
      this.staticMode = Boolean(options.static) || this.codeStart === this.codeEnd;
      this.showFinalFirst = options.showFinalFirst !== false; // default: render final state first
      this.steps = buildSteps(this.codeStart, this.codeEnd);
      this.markers = new Map();
      this.inlineHighlights = new Map();
      const startLines = toLines(this.codeStart);
      const endLines = toLines(this.codeEnd);
      const hasDiff = this.codeStart !== this.codeEnd;
      this.prefixLen = hasDiff ? commonPrefixLen(startLines, endLines) : 0;
      this.suffixLen = hasDiff ? commonSuffixLen(startLines, endLines, this.prefixLen) : 0;
      this.foldTop = this.prefixLen > 2;
      this.foldBottom = this.suffixLen > 2;
      this.stateLines = this.staticMode || !this.showFinalFirst ? [...startLines] : [...endLines];
      this.currentStep = 0;
      this.timer = null;
      this.isPlaying = false;
      this.debug = options.debug || false;
      if (this.showFinalFirst && !this.staticMode && this.steps.length) {
        this.fastForwardToEnd();
      }
      this.buildDom();
      this.render(this.stateLines);
      ensureHighlight().then(() => this.render(this.stateLines));
    }

    buildDom() {
      const wrap = document.createElement("div");
      wrap.className = "code-diff-shell";
      const codeWrap = document.createElement("div");
      codeWrap.className = "code-diff-wrap";

      const actionsBottom = document.createElement("div");
      actionsBottom.className = "code-diff-actions-bottom";
      this.copyBtn = document.createElement("button");
      this.copyBtn.className = "code-diff-copy";
      this.copyBtn.title = "Copy to clipboard";
      this.copyBtn.setAttribute("aria-label", "Copy code");
      actionsBottom.appendChild(this.copyBtn);
      this.playBtn = document.createElement("button");
      this.playBtn.className = "code-diff-btn play-icon";
      this.playBtn.textContent = this.showFinalFirst ? "▶" : "▶";
      this.playBtn.setAttribute("aria-label", "Play diff");
      actionsBottom.appendChild(this.playBtn);

      const pre = document.createElement("pre");
      pre.className = "code-diff-pre";
      const code = document.createElement("code");
      code.className = "hljs";
      pre.appendChild(code);

      codeWrap.appendChild(actionsBottom);
      codeWrap.appendChild(pre);
      wrap.appendChild(codeWrap);
      this.container.appendChild(wrap);

      this.codeEl = code;
      this.preEl = pre;

      this.playBtn.addEventListener("click", () => this.play());
      this.copyBtn.addEventListener("click", () => this.copy());

      if (this.staticMode || this.steps.length === 0) {
        this.playBtn.style.display = "none";
        this.container.classList.add("code-diff-static");
      }
    }

    render(lines, opts = {}) {
      const inserted = new Set(opts.inserted || []);
      const deleting = new Set(opts.deleting || []);
      const closing = new Set(opts.closing || []);
      const inline = new Map(this.inlineHighlights);
      if (opts.inline) {
        for (const [k, v] of opts.inline.entries()) inline.set(k, v);
      }
      const markers = new Map(this.markers);
      if (opts.markers) {
        for (const [k, v] of opts.markers.entries()) markers.set(k, v);
      }
      const hasAnyMarker = markers.size > 0;
      const useHl = Boolean(window.hljs);
      const highlightedLines = useHl ? lines.map((line) => highlightLine(line, this.language)) : null;

      const startIdx = this.foldTop ? this.prefixLen : 0;
      const endIdx = this.foldBottom ? lines.length - this.suffixLen : lines.length;

      const renderLine = (idx) => {
        const line = lines[idx];
        const segments = inline.get(idx);
        let content;

        if (segments && segments.length) {
          const base = highlightedLines
            ? highlightedLines[idx]
            : escapeHtml(line === "" ? "\u00a0" : line);
          content = applyInlineToHighlighted(base, segments);
        } else if (highlightedLines) {
          const safe = highlightedLines[idx];
          content = safe === "" ? "&nbsp;" : safe;
        } else {
          content = escapeHtml(line === "" ? "\u00a0" : line);
        }

        let cls = "code-diff-line";
        if (closing.has(idx)) cls += " closing";
        else if (deleting.has(idx)) cls += " deleting";
        else if (inserted.has(idx)) cls += " inserted";
        const marker = markers.get(idx) || "";
        const markerHtml = marker ? `<span class="marker ${marker}"></span>` : "";
        cls += marker ? " with-marker" : " no-marker";
        return `<div class="${cls}"><span class="line-no">${idx + this.startLine}</span>${markerHtml}<span class="code-text">${content}</span></div>`;
      };

      // Explicit :::fold regions still collapsed, clipped to the visible [startIdx,endIdx) window.
      const regions = (this.foldRegions || [])
        .filter((r) => !r.expanded)
        .map((r) => ({ id: r.id, start: Math.max(r.start, startIdx), end: Math.min(r.end, endIdx), label: r.label }))
        .filter((r) => r.end > r.start)
        .sort((a, b) => a.start - b.start);

      const visible = [];
      let idx = startIdx;
      let ri = 0;
      while (idx < endIdx) {
        while (ri < regions.length && regions[ri].start < idx) ri += 1;
        if (ri < regions.length && idx === regions[ri].start) {
          const r = regions[ri];
          const count = r.end - r.start;
          const plural = count === 1 ? "" : "s";
          const labelHtml = r.label
            ? `${escapeHtml(r.label)} <span class="fold-count">(${count} line${plural})</span>`
            : `\u2026 ${count} line${plural}`;
          visible.push(`<div class="code-diff-fold fold-region" data-fold-region="${r.id}"><span class="fold-icon">+</span> ${labelHtml}</div>`);
          idx = r.end;
          ri += 1;
          continue;
        }
        visible.push(renderLine(idx));
        idx += 1;
      }

      const wrapped = visible.join("");

      let finalHtml = wrapped;

      if (this.foldTop && startIdx > 0) {
        const count = startIdx;
        const fold = `<div class="code-diff-fold fold-top" data-fold="top"><span class="fold-icon">+</span> … ${count} line${count === 1 ? "" : "s"}</div>`;
        finalHtml = fold + finalHtml;
      }

      if (this.foldBottom && endIdx < lines.length) {
        const count = lines.length - endIdx;
        const fold = `<div class="code-diff-fold fold-bottom" data-fold="bottom"><span class="fold-icon">+</span> … ${count} line${count === 1 ? "" : "s"}</div>`;
        finalHtml = finalHtml + fold;
      }

      this.codeEl.className = `hljs language-${this.language}`;
      this.codeEl.innerHTML = finalHtml;
      this.preEl.classList.toggle("has-markers", hasAnyMarker);

      // Attach fold handlers
      const folds = this.codeEl.parentElement.querySelectorAll(".code-diff-fold");
      folds.forEach((el) => {
        el.addEventListener("click", () => {
          const dir = el.dataset.fold;
          if (dir === "top") this.foldTop = false;
          if (dir === "bottom") this.foldBottom = false;
          const regionId = el.dataset.foldRegion;
          if (regionId !== undefined && this.foldRegions) {
            const region = this.foldRegions.find((r) => String(r.id) === regionId);
            if (region) region.expanded = true;
          }
          this.render(this.stateLines);
        });
      });
    }

    copy() {
      const text = this.stateLines.join("\n");
      const done = () => this.showCopyFeedback();
      const fail = () => this.copyFallback(text, done);
      if (navigator.clipboard?.writeText) {
        navigator.clipboard.writeText(text).then(done).catch(fail);
      } else {
        fail();
      }
    }

    copyFallback(text, cb) {
      const ta = document.createElement("textarea");
      ta.value = text;
      document.body.appendChild(ta);
      ta.select();
      document.execCommand("copy");
      document.body.removeChild(ta);
      if (cb) cb();
    }

    showCopyFeedback() {
      if (!this.copyBtn) return;
      this.copyBtn.classList.add("copied");
      clearTimeout(this.copyTimer);
      this.copyTimer = setTimeout(() => {
        this.copyBtn.classList.remove("copied");
      }, 900);
    }

    flash(kind) {
      this.preEl.classList.remove("flash-insert", "flash-delete");
      if (kind) this.preEl.classList.add(kind === "insert" ? "flash-insert" : "flash-delete");
      setTimeout(() => this.preEl.classList.remove("flash-insert", "flash-delete"), 280);
    }

    reset() {
      clearTimeout(this.timer);
      this.stateLines = this.staticMode || !this.showFinalFirst ? toLines(this.codeStart) : toLines(this.codeEnd);
      this.markers.clear();
      this.inlineHighlights.clear();
      this.foldTop = this.prefixLen > 2;
      this.foldBottom = this.suffixLen > 2;
      if (this.foldRegions) this.foldRegions.forEach((r) => { r.expanded = false; });
      if (this.showFinalFirst && !this.staticMode && this.steps.length) {
        this.fastForwardToEnd();
      }
      this.render(this.stateLines);
      this.isPlaying = false;
      this.playBtn.disabled = false;
      this.playBtn.textContent = "▶";
      this.currentStep = 0;
    }

    play() {
      if (this.isPlaying) return;
      if (this.currentStep >= this.steps.length) this.reset();
      if (this.showFinalFirst && !this.staticMode) {
        // jump back to start before animating, and pause one frame so the user sees the untouched text
        clearTimeout(this.timer);
        this.stateLines = toLines(this.codeStart);
        this.markers.clear();
        this.render(this.stateLines);
        this.currentStep = 0;
        this.isPlaying = true;
        this.playBtn.disabled = true;
        this.timer = setTimeout(() => this.runStep(this.currentStep), 420);
        return;
      }
      this.isPlaying = true;
      this.playBtn.disabled = true;
      this.runStep(this.currentStep);
    }

    runStep(idx) {
      if (this.staticMode || this.steps.length === 0) {
        this.render(this.stateLines);
        this.playBtn.disabled = false;
        return;
      }
      if (idx >= this.steps.length) {
        this.stateLines = toLines(this.codeEnd);
        this.render(this.stateLines);
        this.flash("insert");
        this.isPlaying = false;
        this.playBtn.disabled = false;
        this.playBtn.textContent = "▶";
        return;
      }

      const step = this.steps[idx];

      const finish = (delay) => {
        this.currentStep = idx + 1;
        this.timer = setTimeout(() => this.runStep(this.currentStep), delay);
      };

      if (step.kind === "delete") {
        const delIdx = Array.from({ length: step.deletes.length }, (_, k) => step.index + k);
        const markers = new Map(delIdx.map((i) => [i, "marker-delete"]));
        delIdx.forEach((i) => this.markers.set(i, "marker-delete"));
        this.render(this.stateLines, { deleting: delIdx, markers });
        this.flash("delete");
        setTimeout(() => {
          this.render(this.stateLines, { closing: delIdx, markers });
          setTimeout(() => {
            this.stateLines.splice(step.index, step.deletes.length);
            delIdx.forEach((i) => this.markers.delete(i));
            this.shiftMarkers(step.index, step.deletes.length, 0);
            delIdx.forEach((i) => this.inlineHighlights.delete(i));
            this.shiftInlineHighlights(step.index, step.deletes.length, 0);
            this.render(this.stateLines);
            finish(360);
          }, 240);
        }, 420);
        return;
      }

      if (step.kind === "replace") {
        const inline = new Map();
        const overlap = Math.min(step.deletes.length, step.inserts.length);
        for (let k = 0; k < overlap; k++) {
          const oldLine = step.deletes[k];
          const newLine = step.inserts[k];
          inline.set(step.index + k, diffChars(oldLine, newLine));
        }

        for (let k = 0; k < step.deletes.length; k++) this.markers.delete(step.index + k);

        this.stateLines.splice(step.index, step.deletes.length, ...step.inserts);

        const insertedLines = [];
        for (let k = overlap; k < step.inserts.length; k++) {
          insertedLines.push(step.index + k);
        }

        this.shiftMarkers(step.index, step.deletes.length, step.inserts.length);

        for (let k = 0; k < step.deletes.length; k++) this.inlineHighlights.delete(step.index + k);
        this.shiftInlineHighlights(step.index, step.deletes.length, step.inserts.length);
        for (let k = 0; k < overlap; k++) this.inlineHighlights.set(step.index + k, inline.get(step.index + k));

        for (let k = 0; k < overlap; k++) this.markers.set(step.index + k, "marker-replace");
        for (const idxIns of insertedLines) this.markers.set(idxIns, "marker-insert");

        this.render(this.stateLines, { inserted: insertedLines, inline });
        this.flash("insert");
        finish(520);
        return;
      }

      if (step.kind === "insert") {
        this.stateLines.splice(step.index, 0, ...step.inserts);
        const anim = step.inserts.map((_, k) => step.index + k);
        this.shiftMarkers(step.index, 0, step.inserts.length);
        this.shiftInlineHighlights(step.index, 0, step.inserts.length);
        anim.forEach((i) => this.markers.set(i, "marker-insert"));
        this.render(this.stateLines, { inserted: anim });
        this.flash("insert");
        finish(480);
      }
    }

    shiftMarkers(start, removed, added) {
      if (added === removed) return;
      const delta = added - removed;
      const entries = Array.from(this.markers.entries()).sort((a, b) => a[0] - b[0]);
      this.markers.clear();
      for (const [idx, val] of entries) {
        if (idx < start) {
          this.markers.set(idx, val);
        } else if (idx >= start + removed) {
          this.markers.set(idx + delta, val);
        }
      }
    }

    shiftInlineHighlights(start, removed, added) {
      if (added === removed) return;
      const delta = added - removed;
      const entries = Array.from(this.inlineHighlights.entries()).sort((a, b) => a[0] - b[0]);
      this.inlineHighlights.clear();
      for (const [idx, val] of entries) {
        if (idx < start) {
          this.inlineHighlights.set(idx, val);
        } else if (idx >= start + removed) {
          this.inlineHighlights.set(idx + delta, val);
        }
      }
    }

    fastForwardToEnd() {
      this.stateLines = toLines(this.codeStart);
      this.markers.clear();
      this.inlineHighlights.clear();
      for (const step of this.steps) {
        if (step.kind === "delete") {
          for (let k = 0; k < step.deletes.length; k++) this.markers.delete(step.index + k);
          this.stateLines.splice(step.index, step.deletes.length);
          this.shiftMarkers(step.index, step.deletes.length, 0);
          for (let k = 0; k < step.deletes.length; k++) this.inlineHighlights.delete(step.index + k);
          this.shiftInlineHighlights(step.index, step.deletes.length, 0);
          continue;
        }

        if (step.kind === "replace") {
          for (let k = 0; k < step.deletes.length; k++) this.markers.delete(step.index + k);
          this.stateLines.splice(step.index, step.deletes.length, ...step.inserts);
          const overlap = Math.min(step.deletes.length, step.inserts.length);
          const insertedLines = [];
          for (let k = overlap; k < step.inserts.length; k++) insertedLines.push(step.index + k);
          this.shiftMarkers(step.index, step.deletes.length, step.inserts.length);
          for (let k = 0; k < step.deletes.length; k++) this.inlineHighlights.delete(step.index + k);
          this.shiftInlineHighlights(step.index, step.deletes.length, step.inserts.length);
          for (let k = 0; k < overlap; k++) {
            this.inlineHighlights.set(step.index + k, diffChars(step.deletes[k], step.inserts[k]));
          }
          for (let k = 0; k < overlap; k++) this.markers.set(step.index + k, "marker-replace");
          for (const idxIns of insertedLines) this.markers.set(idxIns, "marker-insert");
          continue;
        }

        if (step.kind === "insert") {
          this.stateLines.splice(step.index, 0, ...step.inserts);
          this.shiftMarkers(step.index, 0, step.inserts.length);
          this.shiftInlineHighlights(step.index, 0, step.inserts.length);
          step.inserts.forEach((_, k) => this.markers.set(step.index + k, "marker-insert"));
        }
      }
      // keep currentStep at 0 so play starts from the true beginning
    }
  }

  function extractCode(container) {
    const beforeNode = container.querySelector('pre[data-state="before"], pre[data-role="before"]');
    const afterNode = container.querySelector('pre[data-state="after"], pre[data-role="after"]');
    const singleNode = container.querySelector('pre[data-state="single"], pre[data-state="only"], pre[data-role="single"], pre[data-role="only"]');

    if (!beforeNode && !afterNode && !singleNode) return null;

    const lang = container.dataset.language || container.dataset.lang || "kotlin";
    const declaredStart = parseInt(container.dataset.startLine, 10);
    const startLine = Number.isFinite(declaredStart) && declaredStart > 0 ? declaredStart : 1;

    if (singleNode) {
      const text = normalizeBlock(singleNode.textContent || "");
      singleNode.hidden = true;
      return { before: text, after: text, language: lang, static: true, startLine };
    }

    if (beforeNode && !afterNode) {
      const text = normalizeBlock(beforeNode.textContent || "");
      beforeNode.hidden = true;
      return { before: text, after: text, language: lang, static: true, startLine };
    }

    if (!beforeNode || !afterNode) return null;

    const before = normalizeBlock(beforeNode.textContent || "");
    const after = normalizeBlock(afterNode.textContent || "");
    beforeNode.hidden = true;
    afterNode.hidden = true;
    return { before, after, language: lang, startLine };
  }

  function mountAll() {
    const nodes = document.querySelectorAll(".code-diff-player:not([data-code-diff-bound])");
    nodes.forEach((node) => mountOne(node));
    highlightReferences();
  }

  async function highlightReferences() {
    const references = document.querySelectorAll("[data-code-lang] td code:not(.highlight):not([data-reference-highlighted])");
    if (!references.length) return;
    await ensureHighlight();
    if (!window.hljs) return;
    references.forEach((code) => {
      // Language is an author choice, never guessed from a short identifier.
      const declared = code.closest("[data-code-lang]").dataset.codeLang;
      const language = resolveLanguage(declared === "kotlin" ? "kotlin-reference" : declared);
      if (!language) return;
      code.innerHTML = hljs.highlight(code.textContent, { language, ignoreIllegals: true }).value;
      code.classList.add("hljs");
      code.dataset.referenceHighlighted = "true";
    });
  }

  function mountOne(node, opts) {
    if (!node || node.dataset.codeDiffBound === "true") return null;
    const data = extractCode(node);
    if (!data) return null;
    node.dataset.codeDiffBound = "true";
    const player = new DiffPlayer(node, { ...data, ...(opts || {}) });
    return player;
  }

  window.CodeDiffPlayer = {
    mountAll,
    mountOne,
    DiffPlayer,
  };

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", () => mountAll());
  } else {
    mountAll();
  }
})();
