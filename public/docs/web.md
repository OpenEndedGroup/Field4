# A Web Page in a Box

`web()` puts a web page in a box's output area, with a small bar above it: back, forward, and an
address you can type into. Click in the page and use it as you would in a browser; links you follow
move the address along with them.

```diffstatic_runner {data-lang=kotlin screenshot="assets/web/wikipedia.png" capture="box" capture-profile="electron" scale="1.5" wait="8" timeout="120" embed=true name="web-wikipedia" alt="A box's output panel titled web: a dark bar with back and forward buttons and the address https://en.wikipedia.org/wiki/IFrame, over Wikipedia's IFrame disambiguation page, which lists the HTML element, the video-compression frame, the video-game term and Apple's video format."}
web("en.wikipedia.org/wiki/Iframe")
```

An address without a scheme gets `https://`. The page is 1000 by 640 pixels in a panel called
`web`; pass `channel`, `width` and `height` to change them, and give each page its own channel when
a box shows more than one:

```diffstatic {data-lang=kotlin}
web("github.com", channel = "github", width = 1200, height = 800)
web("news.ycombinator.com", channel = "news")
```

In Field's own window, a page can be any site. Sites that refuse to be shown inside another page
(GitHub, Google, YouTube, most sites you log into) appear anyway, and they keep the cookies a
login needs, which a page inside another page normally loses. Opened in another browser, a Field
document shows only the sites that allow framing.

Back and forward step through this panel's history, leaving the rest of Field and its other pages
where they are.

## Working with the page from code

`web()` returns the page as a `LiveOutputIframe`, the handle `outputIframe(channel)` also gives
you. Its calls reach into the live page in Field's window, whichever site it is on, and return
futures, so the box does not wait for the page:

```diffstatic_runner {data-lang=kotlin screenshot="assets/web/title.png" capture="editor" outputs="inline" open-outputs=true capture-profile="electron" wait-output=true wait-output-kind="inline" wait="8" padding="10" timeout="120" embed=true name="web-title" w="760" h="200" alt="The box's five lines in Field's editor. Under the last line a black block shows the box's value, a CompletableFuture that has not completed, and then the line the page sent back: title: 'IFrame - Wikipedia'."}
val page = web("en.wikipedia.org/wiki/Iframe")
page
    .waitFor("document.querySelector('h1')")
    .thenCompose { page.evaluate("document.title") }
    .thenAccept { title -> println("title: $title") }
```

`evaluate` runs JavaScript in the page and returns its value as JSON. `inspect` returns the page's
DOM, or the elements matching a selector; `click` and `fill` act on a selector once it appears;
`navigate` loads a new address; `screenshot` returns the page's pixels as a data URL; and
`watchIframe` calls back when a value in the page changes
(see [the page as FLines](#the-page-as-flines)).

`devTools()` opens Chromium's DevTools in its own window with the page's `<iframe>` selected.
Expand it in **Elements** for the page's `#document`; in **Console**, pick the page's site from the
context menu at the top (it starts at `top`, which is Field itself):

```diffstatic {data-lang=kotlin}
web("github.com").devTools()
```

The same handle is what [Let AI4 drive a live webpage](ai4_live_iframe.md) gives AI4 as tools.

## Running JavaScript in the page

`evaluate` runs a script inside the page, on whatever site it is, and gives back the value of its
last line. These examples use `go { ... }` and `await()`, so each step waits for the page without
holding up the box. The first reads the stories in Wikipedia's "In the news" into Kotlin:

```diffstatic_runner {data-lang=kotlin screenshot="assets/web/headlines.png" capture="editor" outputs="inline" open-outputs=true capture-profile="electron" wait-output=true wait-output-kind="inline" wait="10" padding="10" timeout="150" embed=true name="web-headlines" w="900" h="260" alt="The box's code in Field's editor. Under the println line a black block holds the list the page returned: four items from Wikipedia's In the news, each a sentence long, the longer ones cut short with a count of the characters left out."}
val wiki = web("en.wikipedia.org/wiki/Main_Page", channel = "wiki", width = 900, height = 560)
val inTheNews = "[...document.querySelectorAll('#mp-itn li')].slice(0, 4).map(li => li.innerText)"

go {
    wiki.waitFor("document.querySelectorAll('#mp-itn li').length > 2", timeoutMillis = 30_000).await()
    println(wiki.evaluate(inTheNews).await())
}
```

A script can change the page too. This one tips every `div` on the page a little way into 3D:
each `div` gets its own random angles as CSS variables, and one added style sheet turns them into
a rotation, seen through a perspective of its own. The sheet is built with `CSSStyleSheet` rather
than written as a `<style>` tag, which many sites' security policies refuse to run. Reload the page
with ++enter++ in its address bar to put it back.

```diffstatic_runner {data-lang=kotlin screenshot="assets/web/tilt.png" capture="box" capture-profile="electron" scale="1.5" wait="12" timeout="150" embed=true name="web-tilt" alt="Wikipedia's Main Page in the box's wiki panel after the script: its panels lean at different small angles in 3D, overlapping like cards, the featured article on the left and the article for improvement on the right, and the page's top has tipped out of view."}
val wiki = web("en.wikipedia.org/wiki/Main_Page", channel = "wiki", width = 900, height = 560)
val tilt =
    """
    const divs = document.querySelectorAll('div')
    for (const div of divs) {
        div.style.setProperty('--rx', (Math.random() * 8 - 4) + 'deg')
        div.style.setProperty('--ry', (Math.random() * 8 - 4) + 'deg')
    }
    const tumble = new CSSStyleSheet()
    tumble.replaceSync(`
        div { transform: perspective(900px) rotateX(var(--rx)) rotateY(var(--ry)) }
    `)
    document.adoptedStyleSheets = [...document.adoptedStyleSheets, tumble]
    divs.length
"""

go {
    wiki.waitFor("document.querySelectorAll('#mp-itn li').length > 2", timeoutMillis = 30_000).await()
    println("tilted ${wiki.evaluate(tilt).await()} divs")
}
```

The script ends with `divs.length` so that its last value is a number. Its value comes back as
JSON, and a page object such as the style sheet has no JSON form.

## The page as FLines

What a script reads can become Field's own geometry. This box shows Wikipedia again and, in a
second panel, draws the page's layout: every `div` on the page's screen as an outline, raised by
how deeply it is nested, in a 3D view you can orbit. The drawing keeps up as the page changes:

```diffstatic_runner {data-lang=kotlin screenshot="assets/web/layout.png" capture="box" capture-profile="electron" scale="1" wait="15" timeout="180" embed=true name="web-layout" alt="Two panels in one box. Above, Wikipedia's Main Page; below, a black 3D view of thin outlines, one rectangle for every div on the page's screen, stacked into a slanted block by how deeply each is nested, blue at the bottom of the stack and yellow at the top."}
import kotlinx.serialization.json.*

val page = web("en.wikipedia.org/wiki/Main_Page", channel = "wiki", width = 900, height = 500)

/*
Once per page: a counter that goes up whenever a div could have moved. Running this again
finds the counter already there and adds nothing. Until the page has loaded it gives null,
which watchIframe takes as "not yet".
*/
val watch =
    """
    if (document.readyState != 'complete') null
    else (window.__fieldLayout ??= (() => {
        const layout = { version: 0 }
        const bump = () => layout.version++
        new MutationObserver(bump).observe(document.documentElement,
            { subtree: true, childList: true, attributes: true })
        const sizes = new ResizeObserver(bump)
        for (const div of document.querySelectorAll('div')) sizes.observe(div)
        for (const event of ['transitionend', 'animationend', 'scroll', 'resize'])
            addEventListener(event, bump, true)
        return layout
    })()).version
"""

// Every div on the page's screen: the part of it on screen, and how deep it is in the page.
val divs =
    """
    [...document.querySelectorAll('div')].map(div => {
        const r = div.getBoundingClientRect()
        const top = Math.max(r.top, 0), bottom = Math.min(r.bottom, innerHeight)
        let depth = 0
        for (let e = div; e.parentElement; e = e.parentElement) depth++
        return [r.left, top, r.width, bottom - top, depth]
    }).filter(([x, y, w, h]) => w > 2 && h > 2 && y < innerHeight && y + h > 0)
"""

fun outlines(rects: JsonElement) =
    rects.jsonArray.map { row ->
        val (x, y, w, h, depth) = row.jsonArray.map { it.jsonPrimitive.double }
        val z = depth * 24.0
        fline {
            moveTo(vec(x, -y, z))
            lineTo(vec(x + w, -y, z))
            lineTo(vec(x + w, -y - h, z))
            lineTo(vec(x, -y - h, z))
            close()
            color = vec(0.3 + depth / 30.0, 0.8, 1.0 - depth / 30.0, 0.6)
            thickness = 1.0
        }
    }

// Redraw whenever the counter moves. One watch per box: running this box again replaces it.
// The viewer frames the first drawing and keeps your orbit, pan and zoom after that.
watchIframe(page, watch) {
    threeLines(outlines(page.evaluate(divs).await()), channel = "layout", width = 900, height = 420)
}
```

`watchIframe(page, expression)` evaluates `expression` every quarter of a second and runs its block
when the value changes. Here the expression is a counter the page keeps itself: the `??=` installs
the observers that bump it the first time, and finds them already there every time after, so
evaluating it again and again adds nothing. Running the box again replaces its watch rather than
adding a second, and stopping the box ends it. Scroll the page, or tip its `div`s with the script
above, and the drawing follows. A box that draws a page another box shows reaches it with
`outputIframe("Wiki", "wiki")`: the box's name, then the page's channel.

!!! note
    These calls need the page visible in Field's Electron window. A page whose panel is closed, or
    a document open only in another browser, has nothing for them to reach.

## Profiles: a page with its own logins

A page normally shares cookies and logins with every other page in Field's window. Give it a
`profile` and it keeps them, along with its local storage and cache, in a directory of its own:

```diffstatic {data-lang=kotlin}
web("github.com", profile = "github")
```

The profile is a [file spec](files_and_paths.md), and the directory is made the first time it is
used. A bare name such as `"github"` is `web-profiles/github` in the document's `files/`
directory, so the login travels with the document. To share one login across every document in a
project, name a directory there:

```diffstatic {data-lang=kotlin}
web("github.com", profile = "%project/web-profiles/github")
```

Pages with the same profile share its logins; a page with another profile, or none, sees none of
them. The bar shows the profile's name at its right end, with the directory as its tooltip.
`webProfileDirectory("github")` gives the directory itself, and
[cookies from code](#cookies-from-code) reads and writes what is in it.

A page with a profile works only in Field's Electron window. Everything on
[`LiveOutputIframe`](#working-with-the-page-from-code) works on it, and so does
[blocking](#ads-and-trackers). `devTools()` opens DevTools on the page itself.

!!! warning
    A profile directory holds the site's login cookies. Anyone who gets a copy of the directory,
    or of a document or project that contains it, can use those logins. Keep profiles out of
    anything you share or commit. A profile also grows with the site's cache, to tens or hundreds
    of megabytes for a busy site.

## Cookies from code

`webProfile("github")` is the profile `web(url, profile = "github")` uses, and its `cookies` read
and write the cookies its pages see. Each call returns a future:

```diffstatic {data-lang=kotlin}
val github = webProfile("github")
github.cookies.get("https://github.com").thenAccept { cookies ->
    cookies.forEach { println("${it.name}  expires ${it.expires ?: "when Field quits"}") }
}
```

`get(url)` gives the cookies a request to that address would carry; `get()` with no address gives
every cookie in the profile, and `name` and `domain` narrow either. `set` adds or replaces one, and
a page in the profile sees it on its next request:

```diffstatic {data-lang=kotlin}
webProfile("github").cookies.set("https://github.com", "tz", "Europe/Paris")
```

`remove(url, name)` takes one away, `clear(url)` every cookie for an address, and `clear()` the
whole profile, which logs every page in it out. `sharedWebProfile` is the profile of pages
without one.

`onCookieChange` calls back whenever a cookie in the profile changes, whether a page or code
changed it. Running the box again replaces the watch, and stopping the box ends it:

```diffstatic {data-lang=kotlin}
onCookieChange(webProfile("github")) { change ->
    println("${if (change.removed) "-" else "+"} ${change.cookie.name} (${change.cause})")
}
```

Changing a cookie's value arrives as two changes: the old value removed (`overwrite`) and the
new one added (`inserted`).

!!! note
    Cookies are reached through Field's Electron window, so these calls need it showing this
    document, as the other page calls do.

## History

Field remembers each page you visit with `web(...)`: its address and title, the time, the box and
output area that showed it, and the address you came from. Each profile keeps
its own, in its directory; pages without a profile keep theirs in `web-history.jsonl` in the
document's `files/`. Like the profile, the history stays with the document.

`history.visits()` reads it back, oldest first:

```diffstatic {data-lang=kotlin}
webProfile("github").history.visits(host = "github.com", limit = 10).forEach {
    println("${it.time}  ${it.title.ifEmpty { it.url }}")
}
```

`host` also matches subdomains; `since` and `until` bound the time; `contains` searches the
address and title; `box` keeps one box's pages, by name or uid; and `limit` keeps the most recent
that many. `sharedWebProfile.history` is the history of pages without a profile.

Each visit's `from` is the address its page showed just before, so the history is also a graph of
which page led to which. `links()` counts each step:

```diffstatic {data-lang=kotlin}
sharedWebProfile.history.links(host = "wikipedia.org").forEach { (step, count) ->
    println("$count×  ${step.first.substringAfterLast('/')} → ${step.second.substringAfterLast('/')}")
}
```

`history.clear()` forgets it all. The file is plain JSON, one visit per line, so other programs can
read it too.

## User scripts and styles

`userScript` runs Javascript in every `web(...)` page at an address, each time a page there
loads, the way a userscript manager such as TamperMonkey does. A page at that address that is
already open runs it straight away:

```diffstatic {data-lang=kotlin}
val dropFootnotes = """document.querySelectorAll("sup.reference").forEach(note => note.remove())"""
userScript("en.wikipedia.org", dropFootnotes)
```

The first argument selects a host, or a pattern where `*` matches anything:
`"https://github.com/"` followed by `*` is every GitHub page over https. The script runs in the page
itself, so it can use the page's own variables as well as its DOM, and the site's
Content-Security-Policy does not stop it. `runAt` says when: `"end"` (the default) once the HTML has
been read, `"idle"` once the page has finished loading, or `"start"`, before any of the page's own
scripts, to change what they see:

```diffstatic {data-lang=kotlin}
userScript(
    "en.wikipedia.org",
    "window.matchMedia = () => ({ matches: false, addEventListener() {} })",
    runAt = "start",
    name = "no-dark-mode",
)
```

`userStyle` adds a stylesheet the same way. Running the box again with new CSS restyles open pages
without reloading them, so you can edit a site's look as you watch:

```diffstatic {data-lang=kotlin}
userStyle("news.ycombinator.com", "body, td { font-family: 'Iosevka Web', monospace !important }")
```

Each box keeps one script and one style under each `name`. Running the box again replaces them;
stopping it removes them, so new pages no longer get them. A removed style comes off open pages at
once; a removed script stays in effect in pages it already ran in until they reload. With
`profile = "github"`, only pages with that [profile](#profiles-a-page-with-its-own-logins) run it.
Scripts run in the page a `web(...)` shows, not in frames inside it, such as ads. An error goes to
the page's console, which `devTools()` opens.

## Javascript boxes inside the page

A page can also be a [Javascript root](remotejs.md). Right-click the box that calls `web(...)`
and choose **Set Main Language: Javascript**; then every Javascript box connected under it runs
inside that page, with everything a Javascript box has: `print` beside the line, errors marked on
the line that raised them, the box's last value, Stop and `launch`. It is a live console into the
site, kept in boxes:

```diffstatic {data-lang=javascript}
const repos = [...document.querySelectorAll("[itemprop~='name'] a, a[data-hovercard-type='repository']")]
repos.forEach(a => a.style.outline = "2px solid #48c9ff")
repos.map(a => a.textContent.trim())
```

Under `web("github.com/OpenEndedGroup")` that outlines every repository on the page and gives back
their names. The code runs beside the page's own scripts the way a browser extension's does: it
shares the page's DOM, so it can read and change anything on the page, but not the variables the
page's scripts keep (a `userScript` runs among those). A site's Content-Security-Policy does not
stop it, so it works on GitHub as well as on Wikipedia.

Kotlin works too. Make the `web(...)` box a [Kotlin → head](kotlin_in_the_head.md) root
instead, and the Kotlin boxes under it are compiled to Javascript and run in the page the same
way, with the browser's API typed, as `kotlinx.browser` and `org.w3c.dom`:

```diffstatic {data-lang=kotlin}
import kotlinx.browser.document
import org.w3c.dom.asList

println("${document.querySelectorAll("a").asList().size} links on ${document.title}")
```

The page starts running Javascript the first time a box under it runs, and again after it
navigates, since each new page starts fresh. Javascript boxes that are not under a page keep
running in your ordinary [RemoteJS](remotejs.md) browser tab or headset, and never in a page.

## Downloads

A file a page offers for download (a link with `download`, an attachment, a file the page makes
for you) is saved, without a dialog, into the document's `files/downloads/`, under the name the
site gave it, with ` (1)`, ` (2)` and so on added if that name is taken. It is then a file like
any other in the document: `"downloads/report.pdf".file` finds it.

Use `onDownload` to respond when a download finishes:

```diffstatic {data-lang=kotlin}
onDownload { d ->
    if (d.completed) println("${d.file.name}: ${d.bytes} bytes of ${d.mimeType}")
}
```

`state` is `completed`, `cancelled` or `interrupted`. Running the box again replaces the watch
and stopping it ends it. `webDownloadsFolder()` gives the folder. Downloads start going there once
a box in the document has called `web(...)`; Field's own downloads, such as an exported image, are
not affected.

## Ads and trackers

Field blocks ads and trackers in the pages it shows. It uses the filter lists uBlock Origin uses
(EasyList, EasyPrivacy and uBlock's own), through Ghostery's filter engine. Matching requests
never leave the machine, and element-hiding rules tidy away the empty slots they leave. Field's
own pages are never filtered.

`blocking()` says what was blocked on a page, and `setBlocking(false)` turns blocking off for
that page's site (for a site that breaks without its ads, or one you want to support). Field
reloads the page and remembers the choice for the site:

```diffstatic {data-lang=kotlin}
val page = web("www.theguardian.com")
page.blocking().thenAccept { b ->
    println("${b.blocked} requests blocked on ${b.site}")
    b.recent.take(5).forEach { println("  ${it.type}: ${it.url.take(80)}") }
}
```

```diffstatic {data-lang=kotlin}
web("www.theguardian.com").setBlocking(false)
```

The lists are downloaded when Field starts and kept for a few days; offline, the kept copy is used.
To change the lists, set `AFIELD_WEB_BLOCKING` before starting Field: `ads` (the default),
`full` (adds annoyances such as cookie banners and newsletter pop-ups), or `off`.

## Where to go next

- [Box Output](box_output.md): channels, and opening and closing panels.
- [A Terminal in a Box](terminal.md): the same kind of panel for a shell or program; the bar above
  a page is drawn like its header row.
- [Let AI4 drive a live webpage](ai4_live_iframe.md): the page's calls, given to AI4 as tools.
- [Three.js scenes](three_output.md#redrawing-a-scene-as-it-changes): the viewer `threeLines`
  draws into, and how it redraws only what changed.
- [Connecting an output iframe to its box](output_requests.md): the other direction, for a page
  you write that asks the box for something.
- [Drawing from data](drawing_from_data.md): other material to draw from.
- Source: [`LiveOutputIframe.kt`](https://github.com/OpenEndedGroup/Field4/blob/main/src/jvmMain/kotlin/afield/ui/output/LiveOutputIframe.kt)
  (`web()` and the handle), [`fieldWeb.js`](https://github.com/OpenEndedGroup/Field4/blob/main/src/commonMain/resources/fieldWeb.js)
  (the bar), [`framing-relief.cjs`](https://github.com/OpenEndedGroup/Field4/blob/main/electron/framing-relief.cjs)
  (why any site can be framed, and keep its login, in Field's window),
  [`content-blocker.cjs`](https://github.com/OpenEndedGroup/Field4/blob/main/electron/content-blocker.cjs)
  (ads and trackers), [`web-profiles.cjs`](https://github.com/OpenEndedGroup/Field4/blob/main/electron/web-profiles.cjs)
  (profiles, cookies and history), [`user-scripts.cjs`](https://github.com/OpenEndedGroup/Field4/blob/main/electron/user-scripts.cjs)
  (user scripts and styles), [`web-downloads.cjs`](https://github.com/OpenEndedGroup/Field4/blob/main/electron/web-downloads.cjs)
  (downloads), and [`page-heads.cjs`](https://github.com/OpenEndedGroup/Field4/blob/main/electron/page-heads.cjs)
  (Javascript boxes inside a page).

`web()` and every member of `LiveOutputIframe` are in the reference, under
[LiveOutputIframe.kt](reference/visualization.md#liveoutputiframe-kt).

Open questions and next builds:

- **DevTools on the page alone.** `devTools()` opens DevTools for the whole Field tab with the
  `<iframe>` selected, because Chromium resolves a point over an out-of-process frame to its owner
  element. A DevTools window attached to the frame's own target would open straight onto the
  page's DOM and console.
- **Safety once AI4 reads the web.** A page can carry text written for AI4, and AI4 can run code.
  Once a turn has read a framed page, the tools that write or run code should ask first.
- **The page as material.** [The page as FLines](#the-page-as-flines) turns a page's layout into
  geometry; its text, tables and images could become Field's values the same way: a table, a
  plot, a texture.
- **A page that calls back.** `watchIframe` looks every quarter of a second. A page could instead
  tell Field the moment something changes, by posting a message to the Field page that hosts it.
- **Logins, all the way through.** A framed GitHub sign-in now reaches GitHub, which answers a
  wrong password as it would in a browser; a complete sign-in, two-factor prompts, passkeys, and
  whether a login lasts to the next session have not been tried. Google refuses to sign in inside
  embedded browsers.
