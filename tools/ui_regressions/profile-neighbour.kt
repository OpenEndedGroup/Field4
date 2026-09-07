// A second box makes attribution and source navigation observable.
val neighbourSink = arrayOfNulls<ByteArray>(1)
loop {
    if (frameNumber == 0) println("PROFILE_NEIGHBOUR_READY")
    neighbourSink[0] = ByteArray(32768)
}
