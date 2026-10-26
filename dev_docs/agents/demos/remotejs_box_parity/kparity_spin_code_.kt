// {{ remoteAR }}
// Launch this, then Stop it: the frame count stops changing.
onStart {
    onFrame { frame ->
        if (frame.frameNumber % 30 == 0) display("main", "frame ${frame.frameNumber}")
    }
}
