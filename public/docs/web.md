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

Back and forward step through the pages this bar has seen. They are the panel's own: the rest of
Field, and its other pages, are not part of them.

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

## Where to go next

- [Box Output](box_output.md): channels, and opening and closing panels.
- [A Terminal in a Box](terminal.md): the same kind of panel for a shell or program; the bar above
  a page is drawn like its header row.
- [Let AI4 drive a live webpage](ai4_live_iframe.md): the page's calls, given to AI4 as tools.
- Source: [`LiveOutputIframe.kt`](https://github.com/OpenEndedGroup/Field4/blob/main/src/jvmMain/kotlin/afield/ui/output/LiveOutputIframe.kt)
  (`web()` and the handle), [`fieldWeb.js`](https://github.com/OpenEndedGroup/Field4/blob/main/src/commonMain/resources/fieldWeb.js)
  (the bar), and [`framing-relief.cjs`](https://github.com/OpenEndedGroup/Field4/blob/main/electron/framing-relief.cjs)
  (why any site can be framed, and keep its login, in Field's window).

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
