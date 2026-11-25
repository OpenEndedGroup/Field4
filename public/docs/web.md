# A Web Page in a Box

`web()` puts a web page in a box's output area, with a small bar above it: back, forward, and an
address you can type into. Click in the page and use it as you would in a browser; links you follow
move the address along with them.

```diffstatic {data-lang=kotlin}
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

```diffstatic {data-lang=kotlin}
val page = web("en.wikipedia.org/wiki/Iframe")
page.waitFor("document.querySelector('h1')").thenCompose { page.evaluate("document.title") }
    .thenAccept { title -> println("title: $title") }
```

`evaluate` runs JavaScript in the page and returns its value as JSON. `inspect` returns the page's
DOM, or the elements matching a selector; `click` and `fill` act on a selector once it appears;
`navigate` loads a new address; `screenshot` returns the page's pixels as a data URL.

`devTools()` opens Chromium's DevTools in its own window with the page's `<iframe>` selected.
Expand it in **Elements** for the page's `#document`; in **Console**, pick the page's site from the
context menu at the top (it starts at `top`, which is Field itself):

```diffstatic {data-lang=kotlin}
web("github.com").devTools()
```

The same handle is what [Let AI4 drive a live webpage](ai4_live_iframe.md) gives AI4 as tools.

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
- **The page as material.** The handle reads a page's DOM; the next step is turning it into
  Field's own values (a table, a plot, an FLine) in a box downstream of the page.
- **Logins, all the way through.** A framed GitHub sign-in now reaches GitHub, which answers a
  wrong password as it would in a browser; a complete sign-in, two-factor prompts, passkeys, and
  whether a login lasts to the next session have not been tried. Google refuses to sign in inside
  embedded browsers.
