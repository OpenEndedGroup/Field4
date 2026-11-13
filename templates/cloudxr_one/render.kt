// One frame submission per tick, shared by both eyes and the desktop mirror.
loop {
    renderer.windows.forEach { it.pollEvents() }
    renderer.render()
    frame()
}
