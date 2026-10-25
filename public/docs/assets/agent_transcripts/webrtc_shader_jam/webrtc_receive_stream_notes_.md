Lists who is publishing (buttons under `publishers`) and draws the stream you click on
a quad in the renderer's window. Until you pick, it shows the first publisher it sees.
In the shader the stream is `uniform sampler2D remote`. Re-running keeps the stream
connected; `source.status` says what it is waiting for.
