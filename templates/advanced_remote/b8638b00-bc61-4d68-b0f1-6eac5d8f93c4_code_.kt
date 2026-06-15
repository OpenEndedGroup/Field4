function drawSierpinski(iterations) {
    var f = new FLine();
    f.pointed = true;
    f.filled  = false;
    f.color   = vec(0,0,0,1);

    var x = 0, y = 0;
    for (var i = 0; i < iterations; i++) {
        var r = Math.random(), x1, y1;
        if (r < 1/3) {
        // bottom‐left corner
            x1 = x/2;
            y1 = y/2;
        } else if (r < 2/3) {
            // bottom‐right corner
            x1 = x/2 + 0.5;
            y1 = y/2;
        } else {
            // top corner
            x1 = x/2 + 0.25;
            y1 = y/2 + Math.sqrt(3)/4;
        }

        x = x1; y = y1;
        f.moveTo(x*8, y*8, 0);

    }
    f.pointed=true
    stage.add(f);
}

clearAll()

drawSierpinski(50000);
