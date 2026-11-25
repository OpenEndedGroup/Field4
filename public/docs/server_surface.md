# Field's server surface

Field's editor talks to its JVM over HTTP and WebSocket. Running Field runs a web server and a
code-execution environment. The defaults are intended for a desktop application on your own machine.

## Desktop defaults

- HTTPS binds **127.0.0.1**, rather than all network interfaces. Other devices cannot connect to
  the default listener.
- Browser-origin protection is **enabled** for API reads and writes, and for the `/ws` and
  `/controllers/ws` command channels. Foreign browser origins and DNS-rebinding browser requests
  are refused. The origin check recognises localhost, literal IP addresses and exact DNS
  authorities explicitly registered by the local user.
- The local development HTTPS certificate is self-signed. The local Electron session accepts it
  on loopback. A normal browser may ask for a local exception.
- A loopback-only HTTP mirror is available for local automation, at `PORT + 10000` by default.
  Set `AFIELD_HTTP_PORT=off` to disable it.

There is **no login or general API token**. Local programs that send no browser-origin headers
can use the API, including executing code. The origin guard does not authenticate native clients
or protect against another program already running on your machine.

## Connecting another device

For a trusted LAN client, including the visionOS shell, opt into a network listener:

```diffstatic {data-lang=bash}
AFIELD_BIND_HOST=0.0.0.0 ./field.sh
```

In PowerShell:

```diffstatic {data-lang=powershell}
$env:AFIELD_BIND_HOST = "0.0.0.0"
.\field.bat
```

You can instead bind the specific local IP address your device will use. Once you expose the
listener, **other machines that can reach that port can use unauthenticated APIs and execute
code**. Browser-origin protection does not stop a native client on another machine. Use a
trusted private network, a firewall that limits clients, or an SSH tunnel while keeping the
loopback default. Field is not an authenticated remote development service.

The server prints its HTTPS bind address and browser-origin policy at startup. `PORT` changes
the default HTTPS port, 9092. Remote clients must use the appropriate address and port.

### Open a remote Field tab in Electron

Click **Remote…** in the Field desktop tab strip and paste the other computer's Field URL.
An address such as `https://192.168.1.42:9092` opens that server's Welcome page. Paste a document
URL such as `https://192.168.1.42:9092/index.html?file=Sketchbook` to go straight to a document.
The tab shows the server address beside its title. Edits, code execution and saves go to that
computer. Remote tabs use browser behavior for features that need the desktop shell's local
integrations.

**Remote…** looks for Fields when you open it. Select one from **Discovered Fields**, then click
**Connect**. Use the refresh button to look again, or paste an address directly.

- **LAN** uses Bonjour over IPv4. Reachable Field servers announce their actual HTTPS port after
  startup when bound to the network. Both computers need the discovery feature, and their local
  network must pass multicast traffic.
- **Tailscale** uses the local Tailscale client's list of online peers. It checks direct Field
  access on HTTPS port `9092` and Tailscale Serve on `443` and `8443`. Paste the URL for other
  ports or proxies. Tailscale access rules still determine which instances you can reach.

For Field's self-signed development certificate, compare the SHA-256 fingerprint shown by
Electron with the fingerprint from the keystore on the Field computer. For the default keystore,
run this from the server checkout and enter its keystore password (`changeit` by default).

```diffstatic {data-lang=bash}
keytool -list -v -keystore config/field4-local.p12 -alias field4
```

Choose **Trust for This Session** if the fingerprints match. For a server behind a proxy with
a trusted certificate, use its HTTPS URL. An SSH tunnel to Field's loopback HTTP mirror can
also be opened using its local URL, such as `http://localhost:19093` for a tunnel that forwards
local port 19093 to port 19092 on the Field computer.

## Browser integrations and deliberate exceptions

CORS still permits cross-origin replies for integrations, but the application guard refuses
cross-site API requests before they reach most routes. `AFIELD_API_REQUIRE_SAME_SITE=0`
explicitly disables that guard; `-Dafield.api.requireSameSite=false` does the same and takes
precedence over the environment. Enabling LAN binding does **not** disable browser protection.

There are deliberate exceptions:

- `/api/import-offline-doc` accepts cross-origin imports from the offline document viewer.
  It imports a document and cannot run commands.
- Ordinary GET navigations into Field remain allowed so documentation links can open the editor.
  The page initiating a navigation cannot read the resulting Field-origin document.
- Docs annotation and improvement-pass routes apply their own loopback and origin checks.
  The revision pass keeps those checks even when annotation-sharing settings are enabled.
- Non-browser tools with no `Origin` and no `Sec-Fetch-Site` remain allowed. Native automation
  and the visionOS shell rely on this; it does not imply that the caller has authenticated.

Do not turn off the origin guard simply to acknowledge a self-signed certificate: those are
separate things. For a reverse proxy or custom DNS name, register only the authority you actually
serve, including its port. `AFIELD_BROWSER_HOSTS` accepts a comma-separated list of exact browser
authorities; `~/.afield/browser-hosts.json` accepts a JSON array of the same strings. Field reads
these at startup. There are no wildcards, DNS lookups or trusted forwarded headers in this policy.
Unregistered DNS names and cross-site browser requests remain refused. Agent execution routes
retain their separate localhost-only host check.

For a private Tailscale Serve listener, the [VR setup helper](field_vr_ide.md#headset-keyboard-and-mouse-without-desktop-focus)
registers the exact local node hostname and port after verifying an existing private HTTPS listener
points to the requested Field port. Registration does not enable public Funnel or disable the
origin guard.

## Route inventory

[`dev_docs/server_surface_inventory.md`](https://github.com/OpenEndedGroup/Field4/blob/main/dev_docs/server_surface_inventory.md)
lists every HTTP route, static mount and command channel, with the access controls each one has
of its own. A route marked `none` has none beyond the listener and the application-wide browser
policy above.

## See also

See [What a document is on disk](document_on_disk.md) for the data the server reads
and writes.
