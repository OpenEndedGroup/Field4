# What Field Exposes, and What Stops It

Field is an application with a **web server** inside it, and that server is how Field's own
editor talks to Field. When you open a document, the window you are looking at is a browser,
and everything you do in it — typing in a box, dragging a box, running code — travels to
Field's JVM over HTTP and a WebSocket.

That is a good architecture. It is also a *listening socket on your machine*, so this page
answers the question that follows: **what can reach it, and what stops a web page you happen
to be visiting from driving your editor?**

You do not need to do anything here to use Field. Read it if you run Field on a network you
share — a studio, a university, a conference wifi.

## The short version

Field's server binds `0.0.0.0`, so **any machine on your network can reach it**, and it
authenticates nothing. It also tells every website you have open that they may call it. Both
of those are deliberate and both have a feature depending on them.

There is one switch, off by default:

```diffstatic {data-lang=bash}
AFIELD_API_REQUIRE_SAME_SITE=1
```

It closes the *website* half — cross-site writes and cross-site WebSocket connections. It does
not close the *network* half. **On a network you do not trust, the thing protecting you is the
network, not Field.**

## Who can reach your Field

| they can… | by default | with `AFIELD_API_REQUIRE_SAME_SITE=1` |
| --- | --- | --- |
| **a website you have open** — change your documents, run your code | yes | **no** |
| **a website you have open** — read your checkout path, your commit messages, your source | yes | yes, still |
| **another machine on your wifi** — anything at all | yes | yes, still |
| **you, and Field's own editor** | yes | yes |

Three things in that table are worth saying in words.

**"Run your code" is literal.** Field's most privileged commands — the ones that launch a box —
run the code in your document, in Field's JVM, with Field's privileges. There is no sandbox
between them and the network. This is what the switch is for.

**Reads are not closed by anything.** The switch inspects writes only, because a page load and
a WebSocket connection are both reads and both have to go through. So a website can still ask
your Field for the absolute path of your checkout, your recent commit messages, and your source
text — with the switch fully on. If that matters to you, the answer is the network, not a
setting.

**"Localhost only" would not help.** It is tempting to assume that a server which only accepts
connections from your own machine is safe. Your browser is on your own machine too, so a page
you visit is on the inside of that check already. This is why the guard asks *which website*
rather than *which machine*.

## What to do

| if you are… | do this |
| --- | --- |
| worried about websites you visit | set `AFIELD_API_REQUIRE_SAME_SITE=1` |
| on a network you do not fully trust | treat Field as an open door: firewall its port, or do not run it there |
| running automation, or the agent backdoor | nothing. Those send no browser origin, so the switch does not affect them — that is intended, and it is also the limitation |
| pointing a Vision Pro or another device at Field | leave the switch on; devices are unaffected for the same reason |

Setting it for one session:

```diffstatic {data-lang=bash}
AFIELD_API_REQUIRE_SAME_SITE=1 ./field.sh
```

Field prints a line at startup saying which way the switch is set, either way, so you can
confirm it took.

## What the switch actually refuses

With the switch on, Field refuses a state-changing request that came from a website other than
itself, and closes a WebSocket connection opened by one. It works out "other than itself" from
headers your browser sets and a page cannot forge, and it is deliberately strict about two
cases that look safe and are not:

- **A different port on `localhost` is a different website.** `http://localhost:9493` is not
  `https://localhost:9092`, and a page served from the first is refused.
- **A hostname that resolves to your machine is not your machine.** An attacker can point
  `field.attacker.example` at `127.0.0.1`, at which point every header agrees and all of them
  are lying. Field requires the address it was asked for to be `localhost` or a literal IP,
  and never resolves a name to decide — resolving is the attacker's move.

What it allows, on purpose: a request with no browser origin at all. That is `curl`, a script,
Field's own native clients and your headset. Nothing is borrowing your browser's credentials in
that case, so it is not what this guard is about — and refusing it would break every
non-browser client Field has.

!!! warning "Treat this as remote control, not a public website"
    Field's server is a remote control for your editor, not a service designed to face the
    open internet. Do not port-forward it, do not put it behind a public reverse proxy, and do
    not expose it to a network you would not hand a shell on.

## One more thing, if you use documentation annotations

The annotation feature and the documentation-improvement endpoint get their own guards, because
the improvement endpoint can start a coding agent with write access to your checkout. It
requires all of: the feature switched on, a matching per-run token, a request from your own
machine, a same-site request, and a target directory that exists. Any one of those failing stops
it. The documentation mirror it uses is restricted to your own machine as well.

## For maintainers

- `dev_docs/SERVER_SURFACE_AUDIT.md` — the reasoning behind everything above: the trust
  boundary, the five defences, the attack shapes each one closes, and a measured probe run
  against a real Field with the switch on.
- `dev_docs/server_surface_inventory.md` — the ledger. Every route, every `/ws` command, every
  static mount, with its guard. Asserted against the source in both directions, so a new door
  cannot appear without the build saying so. Adding a route or a `/ws` command means editing it.
