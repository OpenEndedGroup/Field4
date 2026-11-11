// A channel that held a `lines(...)` viewer, then ordinary HTML: a 640 x 360 card.
lines(listOf(fline { moveTo(0, 0); lineTo(640, 360); thickness = 2.0 }), channel = "picture", w = 640.0, h = 360.0)
display("picture", "<div id='mode-switch-card' style='width:640px;height:360px;background:#123'></div>")
