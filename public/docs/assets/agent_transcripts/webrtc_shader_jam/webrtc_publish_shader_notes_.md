# WebRTC shader jam

The classroom shader jam, from Field. This box draws a fragment shader (its `fragment`
tab) into a 640×360 view and publishes the view as a live video stream named
`@you/field-shader`. The box beside it lists everyone who is publishing and shows the
one you pick, as a texture, in the renderer's window.

## Joining a class

Run this once, in any box, with your class's aaaframe address:

```diffstatic {data-lang=kotlin}
WebRTCNetwork.join("https://aaaframe.example.edu")
```

The first time, a small Field window opens on aaaframe's GitHub sign-in. Sign in as you
would in the browser; the window closes by itself and the panels connect as `@you`.
Field remembers the class and your login (in `~/.afield/webrtc/aaaframe-sign-ins.json`,
readable only by you), so later sessions need nothing. When the login expires, the
window opens again.

Then, in a browser ShaderTrinket, type `you/field-shader` into Receive to pull this
stream into an `iChannel`; and pick any classmate's name in the receiving box's list
to sample their Publish in Field.

Before you join a class, both boxes use Field's own relay, and the receiving box shows
this box's stream.

## On the aaaframe side

Nothing new: an aaaframe server with ShaderTrinket's signalling, as the class already
runs. A server in local mode (`AAAFRAME_LOCAL_AUTH=1`) needs no sign-in at all.

## Known gaps

- Field's Electron app only, for publishing and for signing in.
- Video only; no sound.
- No TURN server: two machines on networks that both block direct connections cannot
  see each other.
- A ShaderTrinket whose shader does not animate publishes no frames.
- On Linux the received stream is copied through the CPU.

More: the WebRTC Streams page in Field's docs.
