# Jam with a classmate

The classroom shader jam, in StackSL. This box lists everyone publishing on the class's
network (the same one browser ShaderTrinkets use) and hands the stream you pick to the
program as a second picture beside `state`.

In the program, `0 sample1` reads the stream at this pixel, the way `prev` reads the last
frame; `0.01 0 vec2 sample1` reads it a little to the side. The starting program keeps a
slow grey memory of the stream: 97% of the last frame, drifting a little, plus 5% of the
stream's brightness (`luma`), so movement leaves soft trails.

## Joining a class

Run `WebRTCNetwork.join("https://<your aaaframe>")` once, in any box. The first time,
Field opens aaaframe's sign-in in a small window and remembers the class and your login.
Before you join, the list shows publishers on Field's own relay.

Click a name under `publishers` to switch stream. Until a stream arrives, the picture
shows the receiver's status line ("waiting for @… to publish"), so you can see what it is
waiting for.

Needs Electron installed for the hidden page that receives the stream
(`./gradlew electronInstall`).
