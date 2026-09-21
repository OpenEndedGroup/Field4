package afield

import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import com.microsoft.playwright.Route
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Exercises the production preparation scripts in Chromium; no fixture image is published. */
class ScreenshotOutputReadinessTest {
    private fun withPage(content: String, script: String = "", test: (Page) -> Unit) {
        Playwright.create().use { playwright ->
            playwright.chromium().launch(BrowserType.LaunchOptions().setHeadless(true)).use { browser ->
                browser.newPage().use { page ->
                    page.route("http://capture.test/**") { route ->
                        route.fulfill(Route.FulfillOptions().setContentType("text/html").setBody("""
                            <div id="overlaycanvas"></div><div class="box" data-uid="subject">
                            <div class="outputArea">$content</div></div>
                        """.trimIndent()))
                    }
                    page.navigate("http://capture.test/")
                    page.evaluate("""() => {
                        window.fieldClientOpen = true;
                        window.fieldBackdoorUiReady = true;
                        const prepare = request => {
                            document.querySelector('.outputArea').style.visibility = 'visible';
                            return JSON.stringify({uid: request.uid, success: true});
                        };
                        window.fieldBackdoorPrepareBoxScreenshot = prepare;
                        window.fieldBackdoorPrepareWindowScreenshot = prepare;
                        $script
                    }""")
                    test(page)
                }
            }
        }
    }

    private fun assertBoth(page: Page, expected: Boolean, openOutputs: Boolean = true) {
        // Failure fixtures need only exercise expiry; success fixtures allow loaded CI hosts room
        // to schedule Chromium. Neither assertion is a performance threshold.
        val timeout = if (expected) 5_000 else 700
        val box = requestBoxScreenshot(page, BoxScreenshotRequest("subject", openOutputs = openOutputs), timeout)
        val window = requestWindowScreenshot(page, WindowScreenshotRequest(uid = "subject", openOutputs = openOutputs), timeout)
        if (expected) {
            assertTrue(box.success, box.message)
            assertTrue(window.success, window.message)
        } else {
            assertFalse(box.success, "box capture unexpectedly accepted missing/unready content")
            assertFalse(window.success, "window capture unexpectedly accepted missing/unready content")
            assertTrue(box.message.contains("output"), box.message)
            assertTrue(window.message.contains("output"), window.message)
        }
    }

    @Test fun missingOutputIsNotSuccessfulContent() = withPage("") { assertBoth(it, false) }

    @Test fun emptyDisclosureIsNotSuccessfulContent() = withPage(
        "<div class='disclosure-content' style='width:300px;height:200px'></div>"
    ) { assertBoth(it, false) }

    @Test fun intentionalHandleOnlyCaptureIsAllowed() = withPage("") { assertBoth(it, true, openOutputs = false) }

    @Test fun titleWithoutLaidOutContentIsNotEnough() = withPage(
        "title<div class='disclosure-content' style='width:300px;height:0;overflow:hidden'>hidden body</div>"
    ) { assertBoth(it, false) }

    @Test fun smallRealOutputIsAllowedAndRevealedBeforeWaiting() = withPage(
        "<div class='disclosure-content' style='width:8px;height:8px'>x</div>",
        "document.querySelector('.outputArea').style.visibility = 'hidden';"
    ) { assertBoth(it, true) }

    @Test fun fieldZeroWidthOverflowShellDoesNotHideRealContent() = withPage(
        "<div class='disclosure-content' style='width:320px;height:180px'>real output</div>",
        "document.querySelector('.outputArea').style.width = '0px';"
    ) { assertBoth(it, true) }

    @Test fun inlineChannelValueIsRealOutput() = withPage(
        "<div class='disclosure'><div class='inline-value'>inline status</div><div class='disclosure-content' style='display:none'>inline status</div></div>"
    ) { assertBoth(it, true) }

    @Test fun continuouslyChangingGeometryFails() = withPage(
        "<div class='disclosure-content' style='width:300px;height:200px'>changing</div>",
        """let width = 300;
           document.querySelector('.outputArea').getBoundingClientRect = () => ({width: width++, height: 200});"""
    ) { assertBoth(it, false) }

    @Test fun busyOutputFailsForWindowAsWellAsBox() = withPage(
        "<div class='disclosure-content' style='width:300px;height:200px'><div aria-busy='true'>loading</div></div>"
    ) { assertBoth(it, false) }
}
