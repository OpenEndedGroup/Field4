package afield.examples

/** Trusted pointer input for the general geometry review browser regression. */
object ThreeFeatureReviewWorkout {
    fun checkGestures() {
        // Exercise pointer capture and the real Save note button through Field-owned Playwright.
        kotlinx.coroutines.runBlocking(afield.playwrightDispatcher) {
            val page = checkNotNull(afield.globalPlaywriteContext)
            val frame = page.frames().first { it.url().contains("_features") }
            frame.locator("#feature-mode").selectOption("region")
            val canvas = frame.locator("canvas").first().boundingBox()
            val center = frame.evaluate("""() => {
                const api=FieldInspector, source=api.sources().find(s=>s.source.id==='quad');
                return api.project(source.id,[-40,15,0]);
            }""") as Map<*, *>
            val x = canvas.x + (center["x"] as Number).toDouble() * canvas.width
            val y = canvas.y + (center["y"] as Number).toDouble() * canvas.height
            page.mouse().move(x-35,y-25)
            page.mouse().down()
            try { repeat(8) { i -> page.mouse().move(x-35+(i+1)*70.0/8,y-25+(i+1)*50.0/8) } }
            finally { page.mouse().up() }
            frame.waitForFunction("() => FieldInspector.snapshot().selection?.kind === 'region'")
            val angleSlider = frame.locator("#feature-angle")
            angleSlider.focus()
            val steps = (25 - angleSlider.inputValue().toInt()) / 5
            repeat(kotlin.math.abs(steps)) { angleSlider.press(if (steps > 0) "ArrowRight" else "ArrowLeft") }
            check(angleSlider.inputValue() == "25" && frame.locator("#feature-angle-value").textContent() == "25°") {
                "Angle slider and readout disagree"
            }
            check(frame.evaluate("""() => {
                const s=FieldInspector.snapshot().selection;
                return s.region.angle===25 && s.region.surfaces.length===1 &&
                    s.region.surfaces[0].partId===FieldInspector.sources().find(p=>p.source.id==='quad').id;
            }""") == true) { "Mouse-drawn rotated rectangle selected a hidden surface" }
            frame.locator("#feature-note").fill("Only the visible surface in this rotated rectangle.")
            frame.locator("#feature-save").click()
            // The acknowledgement is checked by the next page-eval, after this Field
            // execution releases the main thread on which the inbox receives the note.
        }
        println("Manual rectangle drag, rotation and closest-surface selection passed; note sent to Field")
    }
}
