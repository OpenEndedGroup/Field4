import afield.four.synth.*

// One buffer, three paths. The dry beat is a real kick, snare and closed hat from Tidal's
// Dirt-Samples, mixed offline by patRenderStereoDirt. Then it is lowpassed at 900 Hz twice:
// by the Signal graph's own ChamberlinLowpass, and by a Faust program compiled at runtime.
// Three players, a brightness plot, and the seam claim as a number.
//
// The samples are a pinned, checksummed download of a few Dirt-Samples folders. Run
// `./gradlew fetchDocsDirtSamples` once; without them this box stops and says so, rather
// than playing a synth stand-in.
//
// The first run pays 13-22s of Truffle warm-up for the Faust compile. The second does not.
showSoundPathComparison()
displayOpen("path0")
