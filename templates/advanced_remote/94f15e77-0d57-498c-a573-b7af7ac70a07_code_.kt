// 1) Koch Snowflake (2D)
// —– A simple fractal curve; iterate ~4–5 times, angle 60°.
const koch = {
  axiom:    "F--F--F",
  rules:    { "F": "F+F--F+F" },
  angle:    60 * Math.PI/180,
  step:     1,           // relative units
  iter:     5
};

// 2) Sierpiński Triangle (2D)
// —– Generates the famous triangular gasket; angle 120°, ~6–7 its.
const sierpinski = {
  axiom:    "F-G-G",
  rules:    {
    "F": "F-G+F+G-F",
    "G": "GG"
  },
  angle:    120 * Math.PI/180,
  step:     1,
  iter:     7
};

// 3) Dragon Curve (2D)
// —– A space-filling “dragon”; angle 90°, ~10–12 its.
const dragon = {
  axiom:    "FX",
  rules:    {
    "X": "X+YF+",
    "Y": "-FX-Y"
  },
  angle:    90 * Math.PI/180,
  step:     1,
  iter:     12
};

// 4) “Bush” (3D tree‐style)
// —– A very bushy 3D form; angle ~20°, ~5–6 its, step ~0.2.
const bush3D = {
  axiom:    "X",
  rules:    {
    "X": "F[+X]F[-X]+X",
    "F": "FF"
  },
  angle:    20 * Math.PI/180,
  step:     0.2,
  iter:     6
};

// 5) “Plant” (3D fern-like)
// —– Produces more elongated, fern-like branches; angle ~25°, ~5 its.
const plant3D = {
  axiom:    "X",
  rules:    {
    "X": "F-[[X]+X]+F[+FX]-X",
    "F": "FF"
  },
  angle:    25 * Math.PI/180,
  step:     0.2,
  iter:     5
};

// 6) Cross Fractal (3D “plus” shape)
// —– Uses yaw +/-, pitch &/^, roll /\, iteration ~4–5
const cross3D = {
  axiom: "X",
  rules: {
    "X": "F[+X][/X][&X][^X][-X]X",
    "F": "FF"
  },
  angle: 30 * Math.PI/180,
  step: 0.2,
  iter: 5
};

// 7) Crystal Cluster (dense 3D crystal)
// —– Explodes outward on all three rotation axes; keep iter low (~2–3)
const crystal3D = {
  axiom: "F",
  rules: {
    "F": "F[+F]F[-F]F[&F]F[^F]F[/F]\\F"
  },
  angle: 25 * Math.PI/180,
  step: 0.15,
  iter: 3
};

// 8) Twisted Tree (3D fern‐like twist)
// —– Combines multiple branches with both pitch & roll
const twisted3D = {
  axiom: "X",
  rules: {
    "X": "F[+X][&X][^X][\\X][/X][-X]X",
    "F": "FF"
  },
  angle: 22.5 * Math.PI/180,
  step: 0.18,
  iter: 6
};


let cfg = cross3D;
let sys = new LSystem(cfg.axiom, cfg.rules);
let sentence = sys.iterate(cfg.iter);
drawLSystem(sentence, cfg.angle, cfg.step);
