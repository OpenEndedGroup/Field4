"""Semantic access to the user's existing Field Electron tabs over loopback CDP.

The Electron preload identifies each tab through its main process. CDP is used only
to inspect and act on that identified WebContentsView; no second page is opened.
"""

from __future__ import annotations

import asyncio
import base64
import json
import os
import urllib.request
from pathlib import Path
from typing import Any

from websockets.asyncio.client import connect


class ElectronUnavailable(Exception):
    pass


def debug_port(field_port: int) -> int:
    setting = os.environ.get("FIELD_ELECTRON_CDP_PORT", "").strip()
    if setting.lower() == "off":
        raise ElectronUnavailable("Field Electron debugging is disabled (FIELD_ELECTRON_CDP_PORT=off).")
    try:
        port = int(setting) if setting else field_port + 20000
    except ValueError as exc:
        raise ElectronUnavailable(f"Invalid FIELD_ELECTRON_CDP_PORT: {setting!r}.") from exc
    if port not in range(1, 65536):
        raise ElectronUnavailable(f"Electron debugging port is out of range: {port}.")
    return port


async def _targets(port: int) -> list[dict[str, Any]]:
    def fetch() -> list[dict[str, Any]]:
        with urllib.request.urlopen(f"http://127.0.0.1:{port}/json/list", timeout=2) as response:
            return json.load(response)

    try:
        return await asyncio.to_thread(fetch)
    except Exception as exc:
        raise ElectronUnavailable(
            f"Field Electron is not reachable on loopback CDP port {port}. "
            "Launch Field's Electron app from this checkout, or set FIELD_ELECTRON_CDP_PORT "
            "to the port used by its existing --remote-debugging-port switch."
        ) from exc


async def _cdp(url: str, method: str, params: dict[str, Any] | None = None) -> dict[str, Any]:
    try:
        async with asyncio.timeout(8):
            async with connect(url, max_size=12 * 1024 * 1024, open_timeout=3) as socket:
                await socket.send(json.dumps({"id": 1, "method": method, "params": params or {}}))
                async for raw in socket:
                    message = json.loads(raw)
                    if message.get("id") != 1:
                        continue
                    if "error" in message:
                        raise ElectronUnavailable(f"Electron CDP {method} failed: {message['error']}")
                    return message.get("result", {})
    except (TimeoutError, OSError) as exc:
        raise ElectronUnavailable(f"Electron CDP {method} did not respond: {exc}") from exc
    raise ElectronUnavailable(f"Electron CDP {method} did not answer.")


async def _evaluate(url: str, expression: str) -> Any:
    result = await _cdp(url, "Runtime.evaluate", {
        "expression": expression,
        "returnByValue": True,
        "awaitPromise": True,
        "userGesture": True,
    })
    if "exceptionDetails" in result:
        details = result["exceptionDetails"]
        exception = details.get("exception", {})
        raise ElectronUnavailable(exception.get("description") or details.get("text") or "Page script failed.")
    return result.get("result", {}).get("value")


async def list_tabs(field_port: int) -> list[dict[str, Any]]:
    candidates = [item for item in await _targets(debug_port(field_port))
                  if item.get("type") == "page" and item.get("webSocketDebuggerUrl")]
    tabs = []
    for target in candidates:
        try:
            identity = await _evaluate(
                target["webSocketDebuggerUrl"],
                "window.fieldElectron?.identity?.() ?? null",
            )
        except Exception:
            continue  # Browser chrome and unrelated CDP pages are not Field tabs.
        if not isinstance(identity, dict) or not identity.get("tabId"):
            continue
        tabs.append({**identity, "targetId": target.get("id"), "_websocket": target["webSocketDebuggerUrl"]})
    return sorted(tabs, key=lambda tab: (tab["windowId"], tab["tabId"]))


async def select_tab(field_port: int, name: str | None = None) -> dict[str, Any]:
    tabs = await list_tabs(field_port)
    if not tabs:
        raise ElectronUnavailable("No Field Electron tabs were identified. The running app may need a restart with the updated preload.")
    if name:
        needle = name.casefold()
        matches = [tab for tab in tabs if needle == (tab.get("tabId") or "").casefold()]
        if not matches:
            matches = [tab for tab in tabs if needle in (tab.get("title") or "").casefold()
                       or needle in (tab.get("url") or "").casefold()]
    else:
        matches = [tab for tab in tabs if tab.get("active")]
    if len(matches) != 1:
        choices = ", ".join(f"{tab['tabId']} ({tab.get('title')})" for tab in tabs)
        raise ElectronUnavailable(
            f"Expected one {'matching' if name else 'active'} Field tab; found {len(matches)}. "
            f"Available: {choices}. Pass an exact tab id or a distinctive title."
        )
    return matches[0]


def public_tab(tab: dict[str, Any]) -> dict[str, Any]:
    return {key: value for key, value in tab.items()
            if not key.startswith("_") and key != "diagnosticLogPath"}


_INSPECT_SCRIPT = r"""(() => {
  const clean = (value) => String(value || '').replace(/\s+/g, ' ').trim().slice(0, 180);
  const visible = (node) => !!(node.getClientRects().length && getComputedStyle(node).visibility !== 'hidden');
  const label = (node) => clean(node.getAttribute('aria-label') || node.labels?.[0]?.innerText ||
    node.getAttribute('title') || node.innerText || node.getAttribute('placeholder') || node.value);
  const controls = [...document.querySelectorAll('button,input,textarea,select,[role="button"],[role="tab"],[contenteditable="true"]')]
    .filter(visible).slice(0, 120).map((node) => ({tag: node.tagName.toLowerCase(), role: node.getAttribute('role'),
      label: label(node), type: node.getAttribute('type'), id: node.id || null,
      disabled: !!node.disabled, value: node.matches('input,textarea,select') ? clean(node.value) : null}));
  const headings = [...document.querySelectorAll('h1,h2,h3,[role="heading"]')]
    .filter(visible).slice(0, 60).map((node) => ({level: node.getAttribute('aria-level') || node.tagName.toLowerCase(), text: clean(node.innerText)}));
  const links = [...document.querySelectorAll('a[href]')].filter(visible).slice(0, 60)
    .map((node) => ({text: label(node), href: node.href}));
  return {title: document.title, url: location.href, headings, controls, links,
    visibleText: String(document.body?.innerText || '').replace(/\s+/g, ' ').trim().slice(0, 12000)};
})()"""


async def inspect_tab(tab: dict[str, Any]) -> dict[str, Any]:
    return {"tab": public_tab(tab), "page": await _evaluate(tab["_websocket"], _INSPECT_SCRIPT)}


async def act_on_tab(tab: dict[str, Any], action: str, target: str, value: str | None = None) -> dict[str, Any]:
    if action not in ("click", "fill"):
        raise ElectronUnavailable("action must be click or fill.")
    payload = json.dumps({"action": action, "target": target, "value": value})
    script = """(args => {
      const clean = s => String(s || '').replace(/\\s+/g, ' ').trim().toLowerCase();
      const needle = clean(args.target);
      const nodes = [...document.querySelectorAll('button,input,textarea,select,a,[role="button"],[role="tab"],[contenteditable="true"]')]
        .filter(n => n.getClientRects().length && getComputedStyle(n).visibility !== 'hidden');
      const label = n => clean(n.getAttribute('aria-label') || n.labels?.[0]?.innerText || n.getAttribute('title') || n.innerText || n.getAttribute('placeholder') || n.value);
      let matches = nodes.filter(n => n.id.toLowerCase() === needle || label(n) === needle);
      if (!matches.length) matches = nodes.filter(n => label(n).includes(needle));
      if (matches.length !== 1) return {ok:false, error:`Matched ${matches.length} visible controls for ${args.target}`, candidates:matches.slice(0,10).map(label)};
      const node = matches[0];
      if (args.action === 'click') node.click();
      else if (node.matches('input,textarea,select')) {
        node.focus();
        const owner = node.tagName.toLowerCase() === 'textarea' ? HTMLTextAreaElement.prototype :
          node.tagName.toLowerCase() === 'select' ? HTMLSelectElement.prototype : HTMLInputElement.prototype;
        Object.getOwnPropertyDescriptor(owner, 'value').set.call(node, args.value || '');
        node.dispatchEvent(new Event('input', {bubbles:true}));
        node.dispatchEvent(new Event('change', {bubbles:true}));
      } else if (node.isContentEditable) {
        node.focus(); node.textContent = args.value || '';
        node.dispatchEvent(new InputEvent('input', {bubbles:true, data:args.value || ''}));
      } else return {ok:false, error:'The matched control is not editable.'};
      return {ok:true, action:args.action, tag:node.tagName.toLowerCase(), label:label(node)};
    })""" + f"({payload})"
    result = await _evaluate(tab["_websocket"], script)
    if not result or not result.get("ok"):
        raise ElectronUnavailable((result or {}).get("error", "Action failed.") +
                                  f" Candidates: {(result or {}).get('candidates', [])}")
    return {"tab": public_tab(tab), "result": result}


async def screenshot_tab(tab: dict[str, Any]) -> list[dict[str, Any]]:
    result = await _cdp(tab["_websocket"], "Page.captureScreenshot", {"format": "png", "captureBeyondViewport": False})
    encoded = result.get("data")
    if not encoded:
        raise ElectronUnavailable("Electron returned an empty screenshot.")
    base64.b64decode(encoded, validate=True)
    return [
        {"type": "text", "text": json.dumps({"tab": public_tab(tab), "source": "live Field Electron tab"})},
        {"type": "image", "mimeType": "image/png", "data": encoded},
    ]


def recent_console(tab: dict[str, Any], limit: int = 30) -> dict[str, Any]:
    log_path = tab.get("diagnosticLogPath")
    if not log_path:
        raise ElectronUnavailable("This Electron process did not expose a diagnostic log. Restart it with the updated app.")
    path = Path(log_path)
    if not path.is_file():
        raise ElectronUnavailable("Electron's diagnostic log is unavailable.")
    records = []
    for line in path.read_text(errors="replace").splitlines()[-3000:]:
        try:
            record = json.loads(line)
        except ValueError:
            continue
        if record.get("event") != "tab-console-message":
            continue
        details = record.get("details", {})
        if details.get("tabId") == tab["tabId"] and details.get("windowId") == tab["windowId"]:
            records.append({"timestamp": record.get("timestamp"), **details})
    return {"tab": public_tab(tab), "messages": records[-max(1, min(limit, 100)):]}
