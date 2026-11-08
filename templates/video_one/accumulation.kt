// This box's vertex / fragment tabs own the video-local accumulation shader.
// The movie provides ping-pong images and documented weights; Alt+R reloads the shader.
movie.accumulationModuleFactory = { configuration -> moduleHere(configuration) }
