import afield.io.osc.query.*

// A slider or checkbox for every number and boolean the remote publishes. Moving one sets the
// remote; when the remote moves (box 5 animates /demo/lfo), the slider follows over LISTEN.
val remote = oscQueryRemote("127.0.0.1", 9020).refresh()
oscQueryMirror(remote, "mirror") { it.path.startsWith("/demo") }
