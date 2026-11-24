// A treemap for `treemap-real-mouse.yaml` to click and drag with the real mouse. The selection is written to a
// channel the regression reads.
val tree = treemapTree("root") {
    branch("a") { leaf("a1", 6.0); leaf("a2", 6.0); leaf("a3", 4.0) }
    branch("b") { leaf("b1", 3.0); leaf("b2", 2.0); leaf("b3", 2.0) }
    leaf("c", 6.0)
}
treemap(tree, w = 600.0, h = 360.0) {
    onSelect = { picked -> display("state", "<pre id='treemap-real-mouse-state'>${picked.joinToString(",") { it.key }}</pre>") }
}
display("state", "<pre id='treemap-real-mouse-state'></pre>")
