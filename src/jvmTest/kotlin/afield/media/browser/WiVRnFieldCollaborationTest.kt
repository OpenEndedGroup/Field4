package afield.media.browser

import kotlinx.serialization.json.*
import org.junit.Assume
import org.junit.Test
import org.junit.Rule
import org.junit.rules.Timeout
import java.util.concurrent.TimeUnit
import kotlin.test.*

/** Optional live-server proof. Use a dedicated document/box, never a user's working document.
 * FIELD_VR_COLLAB_LOCAL_URL, FIELD_VR_COLLAB_REMOTE_URL, FIELD_VR_COLLAB_BOX must all be set.
 * Remote self-signed TLS is accepted explicitly for this test, not for the production browser.
 * Replays evdev input into real Chromium clients; does not claim physical Quest/USB validation.
 */
class WiVRnFieldCollaborationTest {
    @get:Rule val timeout: Timeout = Timeout.seconds(120)

    @Test fun `headset scoped input and independent remote focus share a live Field document`() {
        val localUrl = System.getenv("FIELD_VR_COLLAB_LOCAL_URL")
        val remoteUrl = System.getenv("FIELD_VR_COLLAB_REMOTE_URL")
        val uid = System.getenv("FIELD_VR_COLLAB_BOX")
        Assume.assumeTrue("Live collaboration requires explicit isolated Field URLs and box uid",
            localUrl != null && remoteUrl != null && uid != null)
        Assume.assumeTrue(System.getProperty("os.name").startsWith("Linux"))
        HeadlessPuppetBrowser(width = 1400, height = 1000).use { local ->
            HeadlessPuppetBrowser(width = 1400, height = 1000).use { remote ->
                fun js(browser: HeadlessPuppetBrowser, source: String): JsonElement =
                    browser.evaluate(source).get(15, TimeUnit.SECONDS).value
                fun waitFor(browser: HeadlessPuppetBrowser, source: String) {
                    val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
                    while (System.nanoTime() < end) {
                        if (runCatching { js(browser, source).jsonPrimitive.boolean }.getOrDefault(false)) return
                        Thread.sleep(100)
                    }
                    println("COLLAB_DIAGNOSTIC " + js(browser,"""JSON.stringify({url:location.href,
                        code:window.globalEditor?.getValue(), current:window.currentSelectedCode?.map(b=>({uid:b.uid,code:b.code})),
                        selected:window.selectedList?.map(b=>({uid:b.uid,code:b.code})), property:window.currentSelectedProperty,
                        body:document.body.innerText.slice(-1800)})"""))
                    fail("Timed out: $source")
                }
                for (browser in listOf(local, remote)) {
                    browser.addEventListener { if(it.name == "console" && it.payload["level"]?.jsonPrimitive?.content == "error") println("COLLAB_EVENT $it") }
                    browser.loaded.get(30, TimeUnit.SECONDS)
                    browser.cdp("Security.setIgnoreCertificateErrors", buildJsonObject { put("ignore", true) }).get(15,TimeUnit.SECONDS)
                }
                local.navigate(localUrl!!).get(30,TimeUnit.SECONDS)
                remote.navigate(remoteUrl!!).get(30,TimeUnit.SECONDS)
                val prepare = """(() => {
                    if (!window.fieldBackdoorPrepareWindowScreenshot) return false;
                    const result=JSON.parse(window.fieldBackdoorPrepareWindowScreenshot({
                        uid:${JsonPrimitive(uid)},property:'code',showEditor:true}));
                    return result.success && !!window.monaco?.editor.getEditors().length;
                })()"""
                waitFor(local, prepare);waitFor(remote, prepare)
                val editor = "window.globalEditor"
                val selected = "window.currentSelectedCode?.[0]?.uid === ${JsonPrimitive(uid)} && window.currentSelectedProperty === 'code'"
                waitFor(local,selected);waitFor(remote,selected)
                val marker = "//vr" + System.nanoTime().toString().takeLast(8)
                // These are real editor focus operations; no model-value mutation is used.
                js(local,"$editor.focus(); true")
                js(remote,"""(() => { const probe=document.createElement('input');probe.id='remote-local-focus';
                    document.body.appendChild(probe);probe.focus();
                    return true; })()""")
                remote.focus().get(15,TimeUnit.SECONDS)
                assertEquals("remote-local-focus",js(remote,"document.activeElement.id").jsonPrimitive.content)
                val source = QueuedWiVRnSource()
                WiVRnBrowserInput(local,local.viewport,source,"us","","",{},{emptySet()}).use { input ->
                    // Ctrl+Home, then //vr + Enter, entirely through the evdev translator.
                    source.key(29,1);source.key(102,1);source.key(102,0);source.key(29,0)
                    val digits = mapOf('1' to 2,'2' to 3,'3' to 4,'4' to 5,'5' to 6,'6' to 7,'7' to 8,'8' to 9,'9' to 10,'0' to 11)
                    val codes = listOf(53,53,47,19) + marker.drop(4).map { digits.getValue(it) } + 28
                    for(code in codes) {source.key(code,1);source.key(code,0)}
                    input.poll();local.flushInput().get(15,TimeUnit.SECONDS)
                    waitFor(local,"$editor.getValue().includes(${JsonPrimitive(marker)})")
                    waitFor(remote,"$editor.getValue().includes(${JsonPrimitive(marker)})")
                    assertEquals("",js(remote,"document.getElementById('remote-local-focus').value").jsonPrimitive.content)
                    assertEquals("remote-local-focus",js(remote,"document.activeElement.id").jsonPrimitive.content)
                    // Independent remote input then flows back to the headset editor.
                    remote.focus().get(15,TimeUnit.SECONDS)
                    js(remote,"$editor.setPosition({lineNumber:1,column:1}); $editor.focus(); true")
                    val point = js(remote,"""(() => {const r=$editor.getDomNode().getBoundingClientRect();
                        const p=$editor.getScrolledVisiblePosition({lineNumber:1,column:1});
                        return {x:r.x+p.left+2,y:r.y+p.top+p.height/2};})()""").jsonObject
                    val pointer = PuppetBrowserUvInput(remote,remote.viewport)
                    pointer.down(u = point.getValue("x").jsonPrimitive.double/1400, v = point.getValue("y").jsonPrimitive.double/1000)
                    pointer.up();remote.flushInput().get(15,TimeUnit.SECONDS)
                    val reverseMarker = "//remote" + marker.drop(4)
                    remote.insertText(reverseMarker + "\n");remote.flushInput().get(15,TimeUnit.SECONDS)
                    waitFor(remote,"$editor.getValue().includes(${JsonPrimitive(reverseMarker)})")
                    waitFor(local,"$editor.getValue().includes(${JsonPrimitive(reverseMarker)})")
                    assertEquals(js(local,"$editor.getValue()"),js(remote,"$editor.getValue()"))
                    println("VR_COLLAB_PROOF "+buildJsonObject {
                        put("localUrl",localUrl);put("remoteUrl",remoteUrl);put("boxUid",uid)
                        put("twoWayDocumentSync",true);put("remoteFocusUnaffected",true)
                        put("inputSource","replayed evdev events into real Chromium")
                        put("physicalHeadsetValidated",false)
                        put("sharedCode",js(local,"$editor.getValue()"))
                    })
                }
            }
        }
    }
}
