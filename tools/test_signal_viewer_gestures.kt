// Run through field_backdoor on a private code property after tools/test_signal_viewer.js.
// Uses real Playwright mouse events in the current, open signal output.
val page: com.microsoft.playwright.Page = afield.globalPlaywriteContext ?: throw IllegalStateException("Playwright not active")
val selector = ".field-signal-viewer .fsv-plot"
val rootSelector = ".field-signal-viewer"
fun state(): List<Double> = (page.evaluate("Array.from(document.querySelectorAll('.field-signal-viewer')).map(r=>[Number(r.dataset.start),Number(r.dataset.end)])[0]") as List<*>).map { (it as Number).toDouble() }
val bounds = page.locator(selector).boundingBox()
val before = state()
val x = bounds.x + bounds.width * .6
val y = bounds.y + 70
page.mouse().move(x, y)
page.mouse().down()
page.mouse().move(x + 120, y, com.microsoft.playwright.Mouse.MoveOptions().setSteps(12))
page.mouse().up()
val after = state()
val expected = before[0] - 120.0 / (bounds.width - 64.0) * (before[1] - before[0])
check(kotlin.math.abs(after[0] - expected) < 1e-6) { "Drag pan: $before -> $after expected $expected" }
page.waitForFunction("document.querySelector('.field-signal-viewer').getAttribute('aria-busy')==='false'")
page.mouse().move(x, y)
page.mouse().wheel(0.0, -150.0)
page.waitForTimeout(250.0)
val zoomed = state()
check(zoomed[1]-zoomed[0] < after[1]-after[0]) { "Real wheel did not zoom: $after -> $zoomed" }
val duration = zoomed[1] - zoomed[0]
val nav = page.locator(".fsv-navigation").boundingBox()
page.mouse().click(nav.x + nav.width * .6, nav.y + nav.height / 2)
val navigated = state()
check(navigated[0] > 0) { "Navigation did not pan" }
check(kotlin.math.abs(navigated[1] - navigated[0] - duration) < 1e-6) { "Navigation changed scale" }
page.waitForFunction("document.querySelector('.field-signal-viewer').getAttribute('aria-busy')==='false'")
println("REAL SIGNAL GESTURES PASS: Plotly drag, wheel, whole-domain navigation; view=" + state())
