// ——————————————————————————————
// 1) Diamond–Square terrain generator
// ——————————————————————————————
function generateTerrain(size, maxHeight, roughness) {
  // size must be a power of two (e.g. 32, 64, 128)
  const N = size;
  const dim = N + 1;
  // initialize 2D height array
  const H = [];
  for (let i = 0; i < dim; i++) {
    H[i] = [];
    for (let j = 0; j < dim; j++) {
      H[i][j] = 0;
    }
  }
  // seed the four corners
  H[0][0]   = Math.random() * maxHeight;
  H[0][N]   = Math.random() * maxHeight;
  H[N][0]   = Math.random() * maxHeight;
  H[N][N]   = Math.random() * maxHeight;

  H[0][0]   = 0;
  H[0][N]   = 0;
  H[N][0]   = 0;
  H[N][N]   = 0;

  let gap    = N;
  let offset = maxHeight;

  while (gap > 1) {
    const half = gap / 2;

    // Diamond step
    for (let x = half; x < N; x += gap) {
      for (let z = half; z < N; z += gap) {
        const avg = (
          H[x - half][z - half] +
          H[x - half][z + half] +
          H[x + half][z - half] +
          H[x + half][z + half]
        ) / 4;
        H[x][z] = avg + (Math.random() * 2 * offset - offset);
      }
    }

    // Square step
    for (let x = 0; x <= N; x += half) {
      for (let z = (x + half) % gap; z <= N; z += gap) {
        let sum = 0, count = 0;
        if (x - half >= 0)    { sum += H[x - half][z]; count++; }
        if (x + half <= N)    { sum += H[x + half][z]; count++; }
        if (z - half >= 0)    { sum += H[x][z - half]; count++; }
        if (z + half <= N)    { sum += H[x][z + half]; count++; }
        const avg = sum / count;
        H[x][z] = avg + (Math.random() * 2 * offset - offset);
      }
    }

    gap    = half;
    offset *= roughness;
  }

  return H;
}

// ——————————————————————————————
// 2) Build your terrain heights
// ——————————————————————————————
const SIZE       = 32;       // must be power of 2
const WIDTH      = 3;      // total span in x-direction
const DEPTH      = 2;      // total span in z-direction
const MAX_HEIGHT = 0.8;       // initial displacement height
const ROUGHNESS  = 0.4;      // how quickly displacement decays

const heights = generateTerrain(SIZE, MAX_HEIGHT, ROUGHNESS);
const dx = WIDTH / SIZE;
const dz = DEPTH / SIZE;

// ——————————————————————————————
// 3) Draw as a wireframe grid
// ——————————————————————————————
const terrain = new FLine();
terrain.pointed = false;
terrain.filled  = false;
terrain.color   = vec(0,0,0, 1);  // light gray grid

// connect east–west lines
for (let i = 0; i <= SIZE; i++) {
  for (let j = 0; j < SIZE; j++) {
    const x1 = i * dx, z1 = j * dz;
    const y1 = heights[i][j];
    const x2 = x1,    z2 = (j + 1) * dz;
    const y2 = heights[i][j + 1];
    terrain.moveTo(x1, y1, z1);
    terrain.lineTo(x2, y2, z2);
  }
}

// connect north–south lines
for (let j = 0; j <= SIZE; j++) {
  for (let i = 0; i < SIZE; i++) {
    const x1 = i * dx, z1 = j * dz;
    const y1 = heights[i][j];
    const x2 = (i + 1) * dx, z2 = z1;
    const y2 = heights[i + 1][j];
    terrain.moveTo(x1, y1, z1);
    terrain.lineTo(x2, y2, z2);
  }
}

clearAll()
clearAFrame()
terrain.pointed=true
// add to the scene
stage.add(terrain);