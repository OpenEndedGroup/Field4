// 1) Create an FLine for the fern points
var fern = new FLine();
fern.pointed = true;                // draw each vertex as a dot
fern.filled  = false;
fern.color   = vec(0, 0.6, 0, 1);   // a fern-green

// 2) Start at the origin
var x = 0, y = 0;

// 3) Number of points to generate
var iterations = 50;

for (var i = 0; i < iterations; i++) {
  var r = Math.random(), x1, y1;

  // 4) Choose one of the four Barnsley maps
  if (r < 0.25) {
    // stem
    x1 = 0;
    y1 = 0.16 * y;
  }
  else if (r < 0.5) {
    // largest leaflets
    x1 =  0.85 * x + 0.04 * y;
    y1 = -0.04 * x + 0.85 * y + 1.6;
  }
  else if (r < 0.75) {
    // left fine leaflets
    x1 =  0.20 * x - 0.26 * y;
    y1 =  0.23 * x + 0.22 * y + 1.6;
  }
  else {
    // right fine leaflets
    x1 = -0.15 * x + 0.28 * y;
    y1 =  0.26 * x + 0.24 * y + 0.44;
  }

  // 5) Move to the new point and plot it
  x = x1;  y = y1;
//  fern.moveTo(x, y, 0);
   fern.lineTo(x, y, 0);
}

clearAFrame()
clearAll()

fern.stroked=false
// 6) Add to the scene
stage.add(fern);

window.fern = fern