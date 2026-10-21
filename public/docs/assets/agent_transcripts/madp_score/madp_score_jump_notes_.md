# jump cut

A box that edits the score while it plays. On the way in and five times on the way across, it picks a
new size and a place near the playhead and moves there with `box.set`, leaving a fading ghost of where
it was. It really moves on the canvas, so the score is different every pass. Drag it back whenever you
like.
