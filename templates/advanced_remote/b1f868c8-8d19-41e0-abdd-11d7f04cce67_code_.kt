// ——————————————
// 1) Simple 1D Perlin-style noise
// ——————————————
const _perm = [
  151,160,137, 91, 90, 15,131, 13,201, 95, 96, 53,194,233,  7,225,
  140, 36,103, 30, 69,142,  8, 99, 37,240, 21, 10, 23,190,   6,148,
  247,120,234, 75,  0, 26,197, 62, 94,252,219,203,117, 35, 11, 32,
   57,177, 33, 88,237,149, 56, 87,174, 20,125,136,171,168, 68,175,
   74,165, 71,134,139, 48, 27,166, 77,146,158,231, 83,111,229,122,
   60,211,133,230,220,105, 92, 41, 55, 46,245, 40,244,102,143, 54,
   65, 25, 63,161,   1,216, 80, 73,209, 76,132,187,208, 89, 18,169,
  200,196,135,130,116,188,159, 86,164,100,109,198,173,186,   3, 64,
   52,217,226,250,124,123,   5,202, 38,147,118,126,255, 82, 85,212,
  207,206, 59,227, 47, 16, 58, 17,182,189, 28, 42,223,183,170,213,
  119,248,152,   2, 44,154,163, 70,221,153,101,155,167, 43,172,  9,
  129, 22, 39,253, 19, 98,108,110, 79,113,224,232,178,185,112,104,
  218,246, 97,228,251, 34,242,193,238,210,144, 12,191,179,162,241,
   81, 51,145,235,249, 14,239,107, 49,192,214, 31,181,199,106,157,
  184, 84,204,176,115,121, 50, 45,127,   4,150,254,138,236,205, 93,
  222,114, 67, 29, 24, 72,243,141,128,195, 78, 66,215, 61,156,180
];

let perm = [];
for (let i = 0; i < 256; i++) {
  perm[i]     = _perm[i];
  perm[256+i] = _perm[i];
}

function fade(t) {
  return t * t * t * (t * (t * 6 - 15) + 10);
}

function lerp(a, b, t) {
  return a + t * (b - a);
}

// A quick integer hash based on bit-twiddling:
function hash1d(x) {
  x = ((x << 13) ^ x) >>> 0;
  return (x * (x * x * 15731 + 789221) + 1376312589) & 0x7fffffff;
}

// Gradient at integer gridpoints is either +1 or -1
function grad(hash, x) {
  return (hash & 1) === 0 ?  x : -x;
}

// 1D noise in [-1,1]
function perlin1d(x) {
  let xi = Math.floor(x) & 255;
  let xf = x - Math.floor(x);
  let h0 = hash1d(xi);
  let h1 = hash1d(xi + 1);
  let g0 = grad(perm[xi], xf);
  let g1 = grad(perm[xi+1], xf - 1);
  let u  = fade(xf);
  return lerp(g0, g1, u);
}

// ——————————————
// 2) Generate mountain via octaves
// ——————————————

/**
 * @param {number} width       — total horizontal span
 * @param {number} maxHeight   — vertical scale of the highest peak
 * @param {number} numPoints   — how many sample points along the span
 * @param {number} octaves     — number of noise layers
 * @param {number} persistence — amplitude decay per octave (e.g. 0.5)
 * @param {number} scale       — controls horizontal “stretch” of noise
 */
function generatePerlinMountain(width, maxHeight, numPoints, octaves, persistence, scale) {
  let points = [];
  for (let i = 0; i <= numPoints; i++) {
    let x = (i / numPoints) * width;
    let y = 0;
    let amplitude = 1;
    let frequency = 1;
    let maxAmp = 0;

    // sum octaves of noise
    for (let o = 0; o < octaves; o++) {
      y += amplitude * perlin1d(11+(x / scale) * frequency);
      maxAmp += amplitude;
      amplitude *= persistence;
      frequency *= 2;
    }

    // normalize to [-1,1], then scale to [0, maxHeight]
    y = ((y / maxAmp) * 0.5 + 0.5) * maxHeight;
    points.push({ x, y });
  }
  return points;
}

clearAll()
// ——————————————
// 3) Draw it with FLine
// ——————————————

const WIDTH       = 30;
const MAX_HEIGHT  = 9;
const NUM_POINTS  = 400;
const OCTAVES     = 5;
const PERSISTENCE = 0.4;
const SCALE       = 4;

const mountainPts = generatePerlinMountain(
  WIDTH, MAX_HEIGHT, NUM_POINTS,
  OCTAVES, PERSISTENCE, SCALE
);

const mountain = new FLine();
mountain.filled  = true;
mountain.pointed = false;
mountain.color   = vec(0.4, 0.3, 0.2, 1);  // earthen brown

mountain.moveTo(0, 0, 0);
mountainPts.forEach(pt => {
  mountain.lineTo(pt.x, pt.y, 0);
});
mountain.lineTo(WIDTH, 0, 0);  // close back down

stage.add(mountain);