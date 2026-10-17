# Field's server surface

Field's editor talks to its JVM over HTTP and WebSocket. Running Field runs a web server and a
code-execution environment. The defaults are intended for a desktop application on your own machine.

## Desktop defaults

- HTTPS binds **127.0.0.1**, rather than all network interfaces. Other devices cannot connect to
  the default listener.
- Browser-origin protection is **enabled** for API reads and writes, and for the `/ws` and
  `/controllers/ws` command channels. Foreign browser origins and DNS-rebinding browser requests
  are refused. The origin check recognises localhost and literal IP addresses.
- The local development HTTPS certificate is self-signed. Electron trusts it; a normal browser
  may ask for a local exception.
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
separate things. A reverse proxy or custom hostname also needs deliberate integration work;
the default browser policy recognises localhost and literal IP addresses without DNS lookup.

## Route inventory

[`dev_docs/server_surface_inventory.md`](https://github.com/OpenEndedGroup/Field4/blob/main/dev_docs/server_surface_inventory.md)
lists every HTTP route, static mount and command channel, with the access controls each one has
of its own. A route marked `none` has none beyond the listener and the application-wide browser
policy above.
