// One buffer, three paths. The dry beat is rendered once by patVoice and then lowpassed at
// 900 Hz twice — by the Signal graph's own ChamberlinLowpass, and by a Faust program compiled
// at runtime. Three players, a brightness plot, and the seam claim as a number.
//
// The first run pays 13-22s of Truffle warm-up for the Faust compile. The second does not.
showSoundPathComparison()
displayOpen("path0")
