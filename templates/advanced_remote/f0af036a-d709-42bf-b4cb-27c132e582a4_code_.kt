function generateMountainPoints(width, maxHeight, iterations, roughness) {
  // start with just the two endpoints
  let points = [
    { x: 0,     y: 0 },
    { x: width, y: 0 }
  ];
  let displacement = maxHeight;

  for (let i = 0; i < iterations; i++) {
    let newPts = [];
    for (let j = 0; j < points.length - 1; j++) {
      let p1 = points[j], p2 = points[j+1];
      // keep the left endpoint
      newPts.push(p1);

      // midpoint with random vertical offset
      let midX = (p1.x + p2.x) / 2;
      let midY = (p1.y + p2.y) / 2
               + (Math.random()*2 - 1) * displacement;
      newPts.push({ x: midX, y: midY });
    }
    // add the final right endpoint
    newPts.push(points[points.length - 1]);
    points = newPts;

    // reduce the displacement for the next pass
    displacement *= roughness;
  }

  return points;
}

// --- draw it with FLine ---

clearAll()
clearAFrame()
// parameters
const WIDTH      = 20;
const MAX_HEIGHT = 8;
const ITERS      = 10;
const ROUGHNESS  = 0.5;

// generate the polyline
const mountainPts = generateMountainPoints(
  WIDTH, MAX_HEIGHT, ITERS, ROUGHNESS
);

// create and configure the FLine
const mountain = new FLine();
mountain.filled  = true;               // fill under the curve
mountain.pointed = false;
mountain.color   = vec(0.4, 0.3, 0.2, 1);  // earthen brown

// start at the left baseline
mountain.moveTo(0, 0, 0);

// draw the ridge
mountainPts.forEach(pt => {
  mountain.lineTo(pt.x, pt.y, 0);
});

// close back down to the baseline on the right
mountain.lineTo(WIDTH, 0, 0);
mountain.pointed=true
mountain.filled=false

stage.add(mountain);