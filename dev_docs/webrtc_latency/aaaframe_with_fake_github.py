"""Run aaaframe's worldbuild_server in GitHub-login mode with GitHub itself stood in for, so
Field's aaaframe sign-in window (dev_docs/WEBRTC_SOURCES_SINKS.md) can be exercised end to end
without a registered GitHub OAuth app.

    cd ~/temp/aaaframe && AAAFRAME_SESSION_SECRET=s AAAFRAME_HOST=127.0.0.1 AAAFRAME_PORT=8791 \\
      AAAFRAME_GITHUB_CLIENT_ID=fake AAAFRAME_GITHUB_CLIENT_SECRET=fake FAKE_GITHUB_LOGIN=alice \\
      .venv/bin/python /path/to/aaaframe_with_fake_github.py

Everything of aaaframe's runs unmodified: /login, /login/github (PKCE state into the session
cookie), /auth/github/callback (state check, token exchange, user lookup, session["user"]), and the
Set-Cookie that Field captures. Only GitHub is replaced:

- the redirect to https://github.com/login/oauth/authorize is rewritten to this server's
  /fake-github/authorize, which shows a one-button "Authorize" page that redirects to the callback
  with a code and the original state, as GitHub does after the user approves (FAKE_GITHUB_AUTO=1
  redirects at once, as GitHub does for an app the user has already authorized);
- the callback's httpx calls to github.com and api.github.com are answered by a MockTransport
  with a token, FAKE_GITHUB_LOGIN's user record, and a verified primary email.
"""
import os
from urllib.parse import parse_qs, urlencode, urlparse

import httpx

LOGIN = os.environ.get("FAKE_GITHUB_LOGIN", "alice")


def _github(request: httpx.Request) -> httpx.Response:
    path = request.url.path
    if path == "/login/oauth/access_token":
        return httpx.Response(200, json={"access_token": "fake-token", "token_type": "bearer"})
    if path == "/user":
        return httpx.Response(200, json={"id": 4242, "login": LOGIN, "name": LOGIN.title(), "avatar_url": ""})
    if path == "/user/emails":
        return httpx.Response(200, json=[{"email": f"{LOGIN}@example.edu", "primary": True, "verified": True}])
    return httpx.Response(404, json={"message": "not faked: " + path})


_RealAsyncClient = httpx.AsyncClient


class _AsyncClient(_RealAsyncClient):
    def __init__(self, *args, **kwargs):
        mounts = dict(kwargs.pop("mounts", None) or {})
        fake = httpx.MockTransport(_github)
        mounts.setdefault("all://github.com", fake)
        mounts.setdefault("all://api.github.com", fake)
        super().__init__(*args, mounts=mounts, **kwargs)


httpx.AsyncClient = _AsyncClient

from quart import Response, request  # noqa: E402

import importlib  # noqa: E402

# The package re-exports its Quart object as `app`, so take the module itself.
aaaframe = importlib.import_module("worldbuild_server.app")
app = aaaframe.app


@app.after_request
async def _send_github_redirect_here(response):
    location = response.headers.get("Location", "")
    if location.startswith("https://github.com/login/oauth/authorize"):
        response.headers["Location"] = "/fake-github/authorize?" + urlparse(location).query
    return response


@app.get("/fake-github/authorize")
async def _fake_authorize():
    query = parse_qs(request.query_string.decode())
    redirect_uri = query.get("redirect_uri", ["/auth/github/callback"])[0]
    back = redirect_uri + "?" + urlencode({"code": "fake-code", "state": query.get("state", [""])[0]})
    if os.environ.get("FAKE_GITHUB_AUTO"):
        # GitHub skips its consent page for an app the user has already authorized.
        return Response("", status=302, headers={"Location": back})
    return Response(
        f"""<!doctype html><title>Fake GitHub</title>
        <body style="font:16px system-ui;margin:3em">
        <h1>Authorize aaaframe (stand-in for GitHub)</h1>
        <p>Signing in as <b>@{LOGIN}</b>.</p>
        <a id="authorize" href="{back}" style="font-size:20px">Authorize</a></body>""",
        content_type="text/html",
    )


if __name__ == "__main__":
    aaaframe.main()
