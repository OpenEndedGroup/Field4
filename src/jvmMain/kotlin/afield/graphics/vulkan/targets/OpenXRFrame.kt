package afield.graphics.vulkan.targets

/** Keeps a located frame alive across caller-thread scene rendering and queue-thread submission. */
internal fun <F : Any> withOpenXRFrame(
    prepare: () -> F?,
    renderScene: () -> Unit,
    submit: (F, Boolean) -> Unit,
    release: (F) -> Unit,
) {
    val frame = prepare()
    var sceneFailure: Throwable? = null
    try {
        renderScene()
    } catch (failure: Throwable) {
        sceneFailure = failure
        throw failure
    } finally {
        if (frame != null) {
            try {
                submit(frame, sceneFailure == null)
            } catch (failure: Throwable) {
                if (sceneFailure == null) throw failure
                sceneFailure.addSuppressed(failure)
            } finally {
                release(frame)
            }
        }
    }
}
