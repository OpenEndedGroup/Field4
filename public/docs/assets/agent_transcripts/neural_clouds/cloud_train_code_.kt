import afield.graphics.volume.*

// Published for the boxes below.
prop<CloudScene>(cloudScene)
prop<NeuralCloud>(cloudNet)
prop<AmbientFit>(cloudAmbient)

cloudScene = CloudScene()

// Training points are drawn where these cameras see cloud. cloud_compare's view is not one of them.
val cameras = [
    CloudScene.Camera(doubleArrayOf(0.0, 1.2, 7.5), doubleArrayOf(0.0, 0.95, 0.0)),
    CloudScene.Camera(doubleArrayOf(-5.5, 2.4, 4.5), doubleArrayOf(0.0, 0.9, 0.0)),
    CloudScene.Camera(doubleArrayOf(4.0, 0.6, -6.0), doubleArrayOf(0.0, 1.0, 0.0)),
]

// Each label is the mean of 8 path-traced estimates: noisy, and the network averages the noise out.
val samples = NeuralCloud.samples(cloudScene, cameras, count = 12000, pathsPerSample = 8)
cloudNet = NeuralCloud.train(samples, hidden = intArrayOf(16, 16), steps = 8000)
cloudAmbient = NeuralCloud.ambient(samples)

// #include "cloud/network" and "cloud/scene" in this box's descendants now mean these.
cloudHere(cloudNet, cloudAmbient, cloudScene)

display("training", "${samples.size} samples, ${cloudNet.weights}, loss ${cloudNet.trainingLoss.takeLast(200).average().f4}")
