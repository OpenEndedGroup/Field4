import afield.dmx.*
import afield.rectsl.*

// A RectSL scene, drawn on the CPU, on a light rig. Edit the scene and launch again: the receiver
// box shows what arrives. (Try the line's width, 0.25 ->wh below, or area = false further down.)
val scene = RectSLImager("""
#rectsl
->wall 16 8 abstract
->background 0x0c1024
->duration 4
->fps 30

"sun" rect
  4 4 ->wh
  0xff7a1a solid ->fill
  '( 0=-5  2=5  4=-5 ) ->x
  '( 0=0  4=3.14159 ) ->rot
;

"band" rect
  16 1.5 ->wh
  0 -3.25 ->xy
  0x2a9df4 solid ->fill
  '( 0=0.15  2=1  4=0.15 ) ->alpha
;

"sweep" rect
  0.25 8 ->wh
  0xffffff solid ->fill
  '( 0=-8  4=8 ) linear ->x
;
""")

// 16 x 8 RGB pixels in universe 1 and four RGBWA washes in universe 2: one fixture per wall unit.
val rig = SceneOnARig.Rig()

// sACN, unicast to this machine. The port is fixed at 35690 on purpose, so that box 2 can listen on
// the same number without the two boxes having to find each other; change both if it is taken.
val out = sacnOut("127.0.0.1", port = 35690)

// On the sender's own 44 Hz clock: draw the scene at t, shrink it to one pixel per fixture
// (area averaging), colour the fixtures, send. area = false samples the full frame at each
// fixture instead, and the thin line flickers as it crosses the rig.
rig.patch.animate(out) { t ->
    rig.show(scene.frame(t % scene.duration, 256, 128), area = true)
}

// The look that is being sent, in the rig's layout, redrawn ten times a second.
loop {
    showPatch(rig.patch, channel = "sent", w = 480.0, h = 300.0)
    delay(100)
}
