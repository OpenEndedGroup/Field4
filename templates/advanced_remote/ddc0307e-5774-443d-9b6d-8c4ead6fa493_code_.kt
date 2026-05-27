// ——————————————————————————————————————————————————
// 1) Gray–Scott Reaction–Diffusion
// ——————————————————————————————————————————————————
function simulateGrayScott(width, height, Du, Dv, F, k, dt, steps) {
  // allocate and initialize u and v
  const u = [], v = [];
  for (let x = 0; x < width; x++) {
    u[x] = []; v[x] = [];
    for (let y = 0; y < height; y++) {
      u[x][y] = 1.0;
      v[x][y] = 0.0;
    }
  }
  // seed a small square in the center with V = 1.0
  const cx = Math.floor(width/2), cy = Math.floor(height/2);
  for (let i = cx-5; i < cx+5; i++) {
    for (let j = cy-5; j < cy+5; j++) {
      v[i][j] = 1.0;
      u[i][j] = 0.0;
    }
  }

  function laplacian(arr, x, y) {
    // 5-point stencil
    let sum = -1 * arr[x][y];
    sum += 0.2 * (arr[x-1][y] + arr[x+1][y] + arr[x][y-1] + arr[x][y+1]);
    sum += 0.05 * ((arr[x-1][y-1] + arr[x-1][y+1] + arr[x+1][y-1] + arr[x+1][y+1]) || 0);
    return sum;
  }

  for (let n = 0; n < steps; n++) {
    const u_new = [], v_new = [];
    for (let x = 1; x < width-1; x++) {
      u_new[x] = []; v_new[x] = [];
      for (let y = 1; y < height-1; y++) {
        const uvv = u[x][y] * v[x][y] * v[x][y];
        const du = Du * laplacian(u, x, y) - uvv + F * (1 - u[x][y]);
        const dv = Dv * laplacian(v, x, y) + uvv - (F + k) * v[x][y];
        u_new[x][y] = u[x][y] + du * dt;
        v_new[x][y] = v[x][y] + dv * dt;
      }
    }
    // copy boundaries and swap
    for (let x = 1; x < width-1; x++) {
      for (let y = 1; y < height-1; y++) {
        u[x][y] = u_new[x][y];
        v[x][y] = v_new[x][y];
      }
    }
  }
  return v;  // return B-concentration field
}

// ——————————————————————————————————————————————————
// 2) Marching Squares for a single contour at T = 0.5
// ——————————————————————————————————————————————————
function extractContour(v, width, height, threshold) {
  const segs = [];
  function interp(p1, p2, t1, t2) {
    const α = (threshold - t1) / (t2 - t1);
    return { x: p1.x + α*(p2.x - p1.x), y: p1.y + α*(p2.y - p1.y) };
  }

  for (let x = 0; x < width-1; x++) {
    for (let y = 0; y < height-1; y++) {
      // corners
      const f0 = v[x][y],     f1 = v[x+1][y];
      const f2 = v[x+1][y+1], f3 = v[x][y+1];
      // cell-index
      const idx = (f0>threshold?1:0) |
                  (f1>threshold?2:0) |
                  (f2>threshold?4:0) |
                  (f3>threshold?8:0);
      if (idx === 0 || idx === 15) continue;  // empty or full

      // grid coords to world coords
      const p0 = { x: x,   y: y   };
      const p1 = { x: x+1, y: y   };
      const p2 = { x: x+1, y: y+1 };
      const p3 = { x: x,   y: y+1 };

      // look up edges for each of the 16 cases
      const edgeTable = {
        1:  [[p0,p3,f0,f3]],
        2:  [[p0,p1,f0,f1]],
        3:  [[p1,p3,f1,f3]],
        4:  [[p1,p2,f1,f2]],
        5:  [[p0,p1,f0,f1],[p2,p3,f2,f3]],
        6:  [[p0,p2,f0,f2]],
        7:  [[p2,p3,f2,f3]],
        8:  [[p2,p3,f2,f3]],
        9:  [[p0,p2,f0,f2]],
        10: [[p0,p3,f0,f3],[p1,p2,f1,f2]],
        11: [[p1,p2,f1,f2]],
        12: [[p1,p3,f1,f3]],
        13: [[p0,p1,f0,f1]],
        14: [[p0,p3,f0,f3]]
      };

      (edgeTable[idx]||[]).forEach(e => {
        // e = [pA,pB,fA,fB]
        const A = interp(e[0], e[1], e[2], e[3]);
        const B = interp(e[1], e[0], e[3], e[2]);
        segs.push([A, B]);
      });
    }
  }
  return segs;
}

// ——————————————————————————————————————————————————
// 3) Put it all together and draw
// ——————————————————————————————————————————————————
const W   = 100, Ht = 100;
const Du  = 0.16, Dv = 0.08;
const Ff  = 0.060, K = 0.062;
const dt  = 1.0;
const steps = 10000;

// 1) simulate
const fieldB = simulateGrayScott(W, Ht, Du, Dv, Ff, K, dt, steps);

// 2) extract contour at B = 0.5
const segs = extractContour(fieldB, W, Ht, 0.5);

// 3) draw with FLine
let outline = new FLine();
outline.pointed = false;
outline.filled  = false;
outline.color   = vec(0,0,0,1);

segs.forEach(([A,B]) => {
  // scale positions into your world space if you like:
  outline.moveTo(A.x, A.y, 0);
  outline.lineTo(B.x, B.y, 0);
});

clearAll()

stage.add(outline);
window.outline = outline
