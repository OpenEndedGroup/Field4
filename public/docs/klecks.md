# Klecks painting in a box

[Klecks](https://github.com/bitbof/klecks) is a layered browser painting app. Run this in a Kotlin box:

```diffstatic {data-lang=kotlin}
val painting = klecks(width = 800, height = 600)
```

The box opens an editable Klecks panel. Paint with its brushes, layers, and tools. Click **Submit** in
Klecks, then confirm **Submit** in its dialog to save the layered PSD and a flattened PNG in the
Field document. The status at the lower left reads **Saved in Field document** when the transfer
finishes. Running the box again restores the saved layers. A separate `channel` gives the box
another independent painting: `klecks(channel = "study", width = 400, height = 400)`.

Kotlin can read the last submitted image:

```diffstatic {data-lang=kotlin}
val painting = klecks()
val png: ByteArray? = painting.pngBytes()
val layeredPsd: ByteArray? = painting.psdBytes()
```

Those values update after Submit. Strokes not yet submitted are kept in the browser, so running the
box again or reopening the page brings them back until you Submit. Each PSD and PNG is limited to about 24 MB;
the panel reports a save error if either exceeds that limit. The image data is stored with the
box as document sidecars, and Field's normal document save writes it to disk.

This is a speculative integration. It uses Klecks' official embed build and its public PSD/PNG
methods. Kotlin currently reads completed paintings; live brush events and Kotlin-driven brush
editing are future work.
