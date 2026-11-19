# Spatial Ink

Spatial Ink is a native Field4Vision head for drawing curves in physical space. Build and run
Field4Vision on a Vision Pro against a Field checkout with spatial ink support. It uses the
same Field host as the other heads. Select it in the main window or set `FIELD4_SERVER` at launch.

1. Choose **Spatial Ink** in the main window's bottom toolbar and allow hand tracking.
2. Choose **Arm Draw**, release your right hand, then touch your thumb and index finger.
   Move the pinch through space to draw. Release to finish and send the stroke to Field.
3. Choose **Pause Drawing** before using other controls. **Undo Stroke** removes the last
   completed stroke. **Retry Field Sync** appears if the host could not process the sketch.

Raw ink appears immediately in gray. Field fits lines and arcs in three dimensions, snaps
nearby endpoints with a tolerance of 18 mm, and returns white curves. The headset keeps raw
ink visible while it waits for the host. Breaking hand tracking cancels the current,
unfinished stroke. Settled strokes keep their positions as new ones arrive.

Field stores the last acknowledged raw and fitted snapshots for each connection in the
workspace's `.spatial-ink` folder. The fitted file is named `<session>.json` and the raw file
is named `<session>.raw.json`. Use
`afield.io.spatialink.readSpatialInk(java.io.File("/path/to/session.json"))`
to get FLines. Each carries its source stroke and graph endpoint IDs. Positions use the
headset's immersive origin in metres. Leaving the space ends that drawing session.
Reconnecting after a transport error saves a new snapshot pair containing the current drawing.

The fitting approach takes its cue from [CASSIE](https://em-yu.github.io/research/cassie/).
This first pass implements Field-native line and arc fitting with endpoint junctions.
CASSIE's interior curve constraints, planarity and surfacing are future work. The simulator
can build and launch the app, but pinch tracking needs a headset.
