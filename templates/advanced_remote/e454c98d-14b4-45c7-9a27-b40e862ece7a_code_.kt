clearAFrame()

class LSystem {
  constructor(axiom, rules) {
    this.axiom = axiom;
    this.rules = rules;
  }
  iterate(n) {
    let s = this.axiom;
    for (let i = 0; i < n; i++) {
      let next = "";
      for (let c of s) {
        next += (this.rules[c] !== undefined ? this.rules[c] : c);
      }
      s = next;
    }
    return s;
  }
}

function rotate(v, axis, angle) {
  let cos = Math.cos(angle),
      sin = Math.sin(angle),
      dot = v.x*axis.x + v.y*axis.y + v.z*axis.z,
      cross = {
        x: axis.y*v.z - axis.z*v.y,
        y: axis.z*v.x - axis.x*v.z,
        z: axis.x*v.y - axis.y*v.x
      };
  return {
    x: v.x*cos + cross.x*sin + axis.x*dot*(1-cos),
    y: v.y*cos + cross.y*sin + axis.y*dot*(1-cos),
    z: v.z*cos + cross.z*sin + axis.z*dot*(1-cos)
  };
}

// 3) Interpreter: walk the string, drawing into a single FLine
function drawLSystem(str, angle, step) {
  let f = new FLine();
  f.pointed = false;
  f.filled  = false;
  f.color   = vec(0,0,0,0.3);  // brown branches

  // turtle state
  let pos     = { x: 0,   y: 0,   z: 0 };
  let heading = { x: 0,   y: 1,   z: 0 };
  let left    = { x: -1,  y: 0,   z: 0 };
  let up      = { x: 0,   y: 0,   z: 1 };
  let stack   = [];

  for (let c of str) {
    switch (c) {
      case "F":
        let nextPos = {
          x: pos.x + heading.x * step,
          y: pos.y + heading.y * step,
          z: pos.z + heading.z * step
        };
        f.moveTo(pos.x, pos.y, pos.z);
        f.lineTo(nextPos.x, nextPos.y, nextPos.z);
        pos = nextPos;
        break;

      case "+":
        heading = rotate(heading, up,  angle*Math.random()*3);
        left    = rotate(left,    up,  angle);
        break;
      case "-":
        heading = rotate(heading, up, -angle);
        left    = rotate(left,    up, -angle);
        break;

      case "&":
        heading = rotate(heading, left,  angle);
        up      = rotate(up,      left,  angle);
        break;
      case "^":
        heading = rotate(heading, left, -angle);
        up      = rotate(up,      left, -angle);
        break;

      case "\\":
        left = rotate(left,  heading,  angle);
        up   = rotate(up,    heading,  angle);
        break;
      case "/":
        left = rotate(left,  heading, -angle);
        up   = rotate(up,    heading, -angle);
        break;

      case "[":
        // push turtle state explicitly
        stack.push({
          pos:     { x: pos.x,     y: pos.y,     z: pos.z     },
          heading: { x: heading.x, y: heading.y, z: heading.z },
          left:    { x: left.x,    y: left.y,    z: left.z    },
          up:      { x: up.x,      y: up.y,      z: up.z      }
        });
        break;
      case "]":
        // pop turtle state
        let st = stack.pop();
        pos     = { x: st.pos.x,     y: st.pos.y,     z: st.pos.z     };
        heading = { x: st.heading.x, y: st.heading.y, z: st.heading.z };
        left    = { x: st.left.x,    y: st.left.y,    z: st.left.z    };
        up      = { x: st.up.x,      y: st.up.y,      z: st.up.z      };
        break;

      // ignore any other symbols
    }
  }
 // f.pointed=true
  stage.add(f);
}

// 4) Example: a classic “fractal tree”
let tree = new LSystem(
  "F",
  { "F": "F[+F]F[-F]" }
);

// generate the 5th iteration
let sentence = tree.iterate(5);



clearAll()
drawLSystem(sentence, 20 * Math.PI/180, 2.2);



const twisted3D = {
  axiom: "X",
  rules: {
    "X": "F[+X][&FF][^X][\\X][/F][-X]X",
    "F": "FX"
  },
  angle: 22.5 * Math.PI/180,
  step: 0.05,
  iter: 7
};


const crystal3D = {
  axiom: "F",
  rules: {
    "F": "F[+F]F[-F]F[&F]F[^F]F[/F]\\F"
  },
  angle: 25 * Math.PI/180,
  step: 0.05,
  iter: 3
};

clearAll()
cfg = crystal3D;
sys = new LSystem(cfg.axiom, cfg.rules);
sentence = sys.iterate(cfg.iter);
print(sentence)
drawLSystem(sentence, cfg.angle, cfg.step);
