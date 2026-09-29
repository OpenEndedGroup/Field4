#!/usr/bin/env python3
"""Field4 MCP server — a thin stdio bridge onto the existing agent backdoor.

This is *not* a new automation surface. It is a stdio Model Context Protocol
server that translates MCP ``tools/call`` requests into the exact same
WebSocket messages ``tools/field_backdoor.py`` already speaks (``agent-snapshot``,
``agent-open-document``, ``macro-transcript-command``, ``agent-create-box``,
``agent-box-screenshot``). All the semantics — the operation transcript op
vocabulary, the awaited launch/settle barrier, box output capture — live in the
JVM (`operation_transcript.kt`) and are reused verbatim. See
``dev_docs/agents/OPERATION_TRANSCRIPT.md`` and ``FIELD_BACKDOOR.md``.

Design intent (why a bridge, not a JVM MCP server): the transcript/backdoor
split (`dev_docs/agents/README.md`) parked in-JVM MCP servers
until the transcript path was stable. It now is, so MCP comes back as the
thinnest possible adapter over that stable path — no server code re-enters the
JVM, and the floor-plan prototype under ``src/personal/kotlin/plans/mcp`` stays
reference-only.

Register with Claude Code (project scope) via .mcp.json, or:

    claude mcp add field -- /path/to/field4/.venv/bin/python3 \
        /path/to/field4/tools/field_mcp.py

The server talks to an *already running* Field. It never spawns the JVM: the
whole point is to converse about the document you have open. If Field is not
running (or has no document open), tools return a friendly error telling you to
open it.

IMPORTANT: MCP stdio requires stdout to carry ONLY JSON-RPC frames. Every log
line here goes to stderr; nothing else may write to stdout.
"""

from __future__ import annotations

import asyncio
import base64
import json
import os
import re
import sys
import traceback
import urllib.parse
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

sys.path.insert(0, str(Path(__file__).resolve().parent))
try:
    from .field_python_env import ensure_repo_python  # noqa: E402
except ImportError:  # Executed as tools/field_mcp.py rather than imported as a package.
    from field_python_env import ensure_repo_python  # noqa: E402

# Explicitly bootstrap before importing the backdoor library. Keeping this call in the entrypoint
# means importing field_backdoor can never restart an arbitrary test runner or MCP host.
ensure_repo_python()

from field_backdoor import (  # noqa: E402
    DEVELOPER_PORT,
    DEFAULT_TIMEOUT,
    BackdoorError,
    FieldBackdoor,
    SessionLogger,
    health_check,
    http_get_json,
    make_unverified_ssl_context,
)
from verification_runtime import field_instance_info  # noqa: E402

SERVER_NAME = "field"
SERVER_VERSION = "0.1.0"
DEFAULT_PROTOCOL_VERSION = "2024-11-05"
ADHOC_MACRO_NAME = "mcp-adhoc"

# Source-body properties, dropped from overview/outline (kept only in full snapshots).
SOURCE_PROPERTY_KEYS = frozenset({
    "code", "preamble", "customization", "metadata",
    "fragment", "vertex", "vertex_fragment", "fragment_s", "vertex_s",
    "compute", "compute_s", "mesh", "mesh_s", "task", "task_s",
    "raygen", "raygen_s", "miss", "miss_s", "closesthit", "closesthit_s",
    "anyhit", "anyhit_s", "intersection", "intersection_s",
    "callable", "callable_s",
})

# ai4 tools whose function the native field_* macro tools already cover; skipped
# when flattening the ai4 registry into the field_* namespace to avoid duplicates.
AI_TOOLS_SKIP = {
    "MacroListRequest", "MacroReadRequest", "MacroWriteRequest", "MacroRunRequest",
}

# Debug-probe error codes that are legitimate *probe outcomes* (the probe ran but
# the line/symbol didn't cooperate). These are returned to the model as data. Any
# OTHER error string is a setup/availability failure (e.g. no JDWP port, bad target)
# and is raised as an isError so the model can't miss the actionable guidance.
PROBE_OUTCOME_CODES = {
    "symbol_not_visible", "timed_out_waiting_for_hit", "missing_debug_info",
    "thread_not_inspectable", "no_executable_location", "class_not_loaded",
    "thread_not_found", "request_install_failed", "invalid_request",
    "vm_died", "vm_disconnected", "event_loop_failure",
}
PROBE_OUTCOME_HELP = {
    "timed_out_waiting_for_hit": "The line never executed within timeout_ms. Raise timeout_ms, "
        "pick a line that actually runs, or trigger the code path while probing.",
    "symbol_not_visible": "The line executed but no local/field named that symbol was in scope "
        "there. Check the symbol name and pick a line where it is live.",
    "missing_debug_info": "That class was compiled without local-variable debug info.",
    "no_executable_location": "No executable bytecode maps to that source line — pick a line with "
        "a real statement (not a blank/brace/decl line).",
    "class_not_loaded": "The target class is not loaded in the JVM yet (and did not load within "
        "timeout_ms). Exercise the code path first, or raise timeout_ms.",
    "thread_not_found": "No thread matched the given 'thread' name.",
}

def _probe_note_line(where: str, symbol: Any, line: Any, phase: Any,
                     error: Any, samples: list[dict[str, Any]]) -> str:
    """One compact line summarizing a probe, for a box's notes debug trail."""
    when = datetime.now(timezone.utc).astimezone().strftime("%Y-%m-%d %H:%M:%S")
    head = f"[debug {when}] {symbol} @ {where}:{line}"
    if not samples:
        return f"{head} → ERROR {error} (0 samples)" if error else f"{head} → no samples ({phase})"
    first = samples[0]
    vtext = str(first.get("valueText", ""))
    if len(vtext) > 120:
        vtext = vtext[:119] + "…"
    vtype = first.get("valueType") or first.get("valueKind") or "?"
    extra = f" +{len(samples) - 1} more" if len(samples) > 1 else ""
    return (f"{head} → {vtype} {vtext} ({first.get('resolution')}){extra}; "
            f"{len(samples)} sample(s), {phase}")


_CAMEL_1 = re.compile(r"(.)([A-Z][a-z]+)")
_CAMEL_2 = re.compile(r"([a-z0-9])([A-Z])")


def flatten_ai_tool_name(ai4_name: str) -> str:
    """SemanticRetrievalSearchRequest -> field_semantic_retrieval_search."""
    base = ai4_name[:-7] if ai4_name.endswith("Request") else ai4_name
    snake = _CAMEL_2.sub(r"\1_\2", _CAMEL_1.sub(r"\1_\2", base)).lower()
    return f"field_{snake}"


def log(*parts: Any) -> None:
    """Diagnostic logging — stderr only, never stdout."""
    print("[field-mcp]", *parts, file=sys.stderr, flush=True)


class McpToolError(Exception):
    """A tool-level failure surfaced to the model as an isError result."""


class FieldMcpServer:
    def __init__(self, *, port: int, timeout: float) -> None:
        self.port = port
        self.timeout = timeout
        self.ssl_context = make_unverified_ssl_context()
        self.client: FieldBackdoor | None = None
        self.attached_document: str | None = None
        self.protocol_version = DEFAULT_PROTOCOL_VERSION
        # ai4 tools discovered dynamically from the JVM registry once attached.
        self._ai_tools: list[dict[str, Any]] = []       # MCP descriptors (flattened)
        self._ai_name_map: dict[str, str] = {}          # flat MCP name -> ai4 tool name
        self._ai_tools_loaded = False
        logger_root = Path(__file__).resolve().parent.parent / ".field-backdoor" / "logs"
        self.logger = SessionLogger(logger_root, ["field_mcp", f"--port={port}"])

    # ---- Field connection (lazy; never spawns the JVM) --------------------

    def _loopback_base(self) -> str:
        # Prefer the HTTPS main port with the shared unverified context so we
        # do not depend on the loopback plaintext mirror's exact port math.
        return f"https://localhost:{self.port}"

    def _discover_open_documents(self) -> list[dict[str, Any]]:
        """Return live documents from /api/welcome-docs, richest first."""
        url = f"{self._loopback_base()}/api/welcome-docs"
        try:
            data = http_get_json(url, self.ssl_context, timeout=self.timeout)
        except Exception as exc:  # noqa: BLE001 — endpoint may be empty during startup
            log("welcome-docs unavailable:", exc)
            return []
        if not isinstance(data, dict):
            return []
        found: list[dict[str, Any]] = []
        # Workspace docs annotated with a live session count. The /api/welcome-docs
        # payload serializes this list under "documents" (see WelcomeDocsResponse);
        # earlier code read "docs", which silently missed every workspace doc so
        # only ephemeral runningDocuments (scratch) ever surfaced.
        for doc in data.get("documents", []) or []:
            if int(doc.get("runningSessionCount", 0) or 0) > 0:
                found.append(
                    {
                        "documentName": doc.get("filename"),
                        "sessionCount": int(doc.get("runningSessionCount", 0) or 0),
                        "ephemeral": False,
                        "sessionKey": None,
                    }
                )
        # Running docs not in the workspace listing (scratch, absolute paths…).
        for entry in data.get("runningDocuments", []) or []:
            session_key = None
            open_url = entry.get("openUrl") or ""
            if "?" in open_url:
                params = urllib.parse.parse_qs(open_url.split("?", 1)[1])
                session_key = (params.get("sessionKey") or [None])[0]
            found.append(
                {
                    "documentName": entry.get("documentName"),
                    "sessionCount": int(entry.get("sessionCount", 0) or 0),
                    "ephemeral": bool(entry.get("ephemeral", False)),
                    "sessionKey": session_key,
                }
            )
        # De-dupe by documentName, prefer non-ephemeral with more sessions.
        by_name: dict[str, dict[str, Any]] = {}
        for item in found:
            name = item["documentName"]
            if not name:
                continue
            prev = by_name.get(name)
            if prev is None or (not item["ephemeral"] and prev["ephemeral"]):
                by_name[name] = item
        ordered = sorted(
            by_name.values(),
            key=lambda d: (d["ephemeral"], -d["sessionCount"], str(d["documentName"]).lower()),
        )
        return ordered

    async def _ensure_client(self) -> FieldBackdoor:
        existing = self.client
        if existing is not None:
            if existing.closed_reason is None and existing.websocket is not None:
                return existing
            # Connection dropped (e.g. Field was restarted) — discard the dead
            # client and reconnect + re-attach rather than reuse a stale socket.
            log(f"connection lost ({existing.closed_reason}); reconnecting")
            try:
                await existing.close()
            except Exception:  # noqa: BLE001
                pass
            self.client = None
            self.attached_document = None
        if not health_check(self.port, self.ssl_context):
            raise McpToolError(
                f"Field4 is not reachable on https://localhost:{self.port}. "
                "Open Field4 (or run ./gradlew runJvm) and open a document, then retry. "
                "This bridge intentionally does not launch the JVM — it talks to the "
                "document you already have open."
            )
        client = FieldBackdoor(
            port=self.port,
            workspace=None,
            default_timeout=self.timeout,
            logger=self.logger,
        )
        await client.connect()
        self.client = client
        return client

    async def _ensure_attached(self, document: str | None = None, scratch: bool = False) -> FieldBackdoor:
        client = await self._ensure_client()
        if self.attached_document is not None and document is None and not scratch:
            return client
        target = document
        if target is None and not scratch:
            open_docs = self._discover_open_documents()
            real = [d for d in open_docs if not d["ephemeral"]]
            # Prefer real (workspace) documents; only fall back to ephemeral scratch
            # docs when no real ones are open. `candidates` is therefore already
            # narrowed to the relevant tier, so any remaining ambiguity (>1) should
            # prompt for an explicit name rather than silently attaching to the first.
            candidates = real or open_docs
            if not candidates:
                raise McpToolError(
                    "No document is currently open in Field4. Open one in the app "
                    "(or pass a document name to field_open_document), then retry."
                )
            if len(candidates) > 1:
                names = ", ".join(str(d["documentName"]) for d in candidates)
                raise McpToolError(
                    f"Multiple documents are open ({names}). "
                    "Call field_open_document with an explicit document name."
                )
            target = str(candidates[0]["documentName"])
        await client.open_document(target, scratch=scratch, timeout=self.timeout)
        self.attached_document = client.document_name
        log(f"attached to document: {self.attached_document!r} ({len(client.boxes)} boxes)")
        await self._load_ai_tools(client, announce=True)
        return client

    async def _load_ai_tools(self, client: FieldBackdoor, *, announce: bool) -> None:
        """Fetch the ai4 tool registry and flatten it into the field_* namespace."""
        try:
            descriptors = await client.ai_tools_list(timeout=self.timeout)
        except Exception as exc:  # noqa: BLE001 — ai4 tools are best-effort
            log("ai tools list failed (continuing with native tools):", exc)
            return
        tools: list[dict[str, Any]] = []
        name_map: dict[str, str] = {}
        native = {t["name"] for t in self.TOOLS}
        for d in descriptors:
            ai4_name = d.get("name")
            if not ai4_name or ai4_name in AI_TOOLS_SKIP:
                continue
            flat = flatten_ai_tool_name(ai4_name)
            if flat in native or flat in name_map:
                continue  # never shadow a native tool
            tools.append({
                "name": flat,
                "description": d.get("description", f"ai4 tool {ai4_name}."),
                "inputSchema": d.get("inputSchema") or {"type": "object", "properties": {}},
            })
            name_map[flat] = ai4_name
        changed = not self._ai_tools_loaded or {t["name"] for t in tools} != {t["name"] for t in self._ai_tools}
        self._ai_tools = tools
        self._ai_name_map = name_map
        self._ai_tools_loaded = True
        log(f"loaded {len(tools)} ai4 tools: {', '.join(sorted(name_map))}")
        if announce and changed:
            self._notify("notifications/tools/list_changed", {})

    def _resolve_box(self, client: FieldBackdoor, token: str) -> str:
        if token in client.boxes:
            return token
        matches = [uid for uid, box in client.boxes.items() if box.get("name") == token]
        if len(matches) == 1:
            return matches[0]
        if not matches:
            raise McpToolError(
                f"No box named or ided {token!r} in {client.document_name!r}. "
                "Call field_snapshot to see available boxes."
            )
        raise McpToolError(
            f"{len(matches)} boxes are named {token!r}; reference one by its uid instead."
        )

    # ---- Tool implementations --------------------------------------------

    async def tool_inspect_view(self, args: dict[str, Any]) -> dict[str, Any]:
        from field_inspection import inspect_view
        return await inspect_view(await self._ensure_attached(), args["viewId"], state=args.get("state"),
            section=args.get("section"), hidden=args.get("hidden"), assembly=args.get("assembly"), pick=args.get("pick"), note=args.get("note"), region=args.get("region"), received=bool(args.get("received", False)), evidence_dir=args.get("evidenceDirectory"), include_resolved=bool(args.get("includeResolved", False)), timeout=self.timeout)

    async def tool_reply_note(self, args: dict[str, Any]) -> dict[str, Any]:
        from field_inspection import reply_review_note
        return await reply_review_note(await self._ensure_attached(), args["viewId"], note_id=args["noteId"],
            text=args["text"], actor=args["actor"], checked_revision=args["checkedRevision"],
            expected_version=args["expectedVersion"], timeout=self.timeout)

    async def tool_review_note(self, args: dict[str, Any]) -> dict[str, Any]:
        from field_inspection import update_review_note
        return await update_review_note(await self._ensure_attached(), args["viewId"], note_id=args["noteId"],
            status=args["status"], summary=args["summary"], actor=args["actor"], checked_revision=args["checkedRevision"],
            expected_version=args["expectedVersion"], timeout=self.timeout)

    async def tool_status(self, _args: dict[str, Any]) -> dict[str, Any]:
        healthy = health_check(self.port, self.ssl_context)
        result: dict[str, Any] = {"port": self.port, "fieldRunning": healthy}
        if healthy:
            result["openDocuments"] = self._discover_open_documents()
            # Where a document name passed to field_open_document will resolve. This bridge has
            # no workspace argument to confuse with the server's, but it also gave an agent no
            # way to ask -- the same blind spot from the other side. The running Field's
            # workspace comes from a machine-global preference by default, and has been observed
            # pointing into an unrelated checkout. null means the server predates the
            # `workspace` field of GET /api/instance-info, so nothing has been verified.
            instance = field_instance_info(self.port, self.ssl_context)
            workspace = (instance or {}).get("workspace")
            result["serverWorkspace"] = workspace if isinstance(workspace, str) and workspace else None
        result["attachedDocument"] = self.attached_document
        return result

    async def tool_list_open_documents(self, _args: dict[str, Any]) -> dict[str, Any]:
        if not health_check(self.port, self.ssl_context):
            raise McpToolError(f"Field4 is not running on port {self.port}.")
        return {"openDocuments": self._discover_open_documents()}

    async def tool_open_document(self, args: dict[str, Any]) -> dict[str, Any]:
        document = args.get("document")
        scratch = bool(args.get("scratch", False))
        create = bool(args.get("create", False))
        client = await self._ensure_attached(document=document, scratch=scratch)
        # Same default as the backdoor's `open <name>` since 2026-09-28: a name that is not in
        # the workspace is a refusal unless the caller asked for a new document. The server has
        # already made the (unsaved) empty session by the time it answers, so forget the
        # attachment rather than leave the conversation pointed at it.
        if document and not scratch and not create and client.document_existed is not True:
            self.attached_document = None
            if client.document_existed is None:
                raise McpToolError(
                    f"Cannot tell whether {document!r} exists: this Field predates `documentExisted`. "
                    "Restart Field from this checkout, or pass create=true and check boxCount."
                )
            raise McpToolError(
                f"No document named {document!r} in the server's workspace; Field would have created "
                "a new empty one. Check the spelling (field_list_open_documents), or pass create=true."
            )
        return {
            "documentName": client.document_name,
            "scratch": client.scratch,
            "boxCount": len(client.boxes),
            # Opening a name that is not in the server's workspace CREATES a document, so a
            # typo used to return `boxCount: 0` and nothing else -- indistinguishable from a
            # real document that happens to be empty. Both of these are read back from the
            # server's reply; `null` means it predates them, so you have verified nothing.
            # Same correction as `field_status` gaining `serverWorkspace` on 2026-09-12:
            # this tool was not lying, it had no way to answer at all.
            "documentExisted": client.document_existed,
            "attachedToLiveDocument": client.attached_to_live_document,
        }

    async def tool_snapshot(self, args: dict[str, Any]) -> dict[str, Any]:
        include_code = bool(args.get("include_code", True))
        client = await self._ensure_attached()
        snapshot = await client.refresh_snapshot(timeout=self.timeout)
        if include_code:
            return snapshot
        # Overview mode: drop large source bodies so the model can survey the
        # graph without pulling every shader/kotlin body into context.
        boxes = []
        for box in snapshot.get("boxes", []):
            trimmed = {k: v for k, v in box.items() if k not in SOURCE_PROPERTY_KEYS}
            present = sorted(k for k in SOURCE_PROPERTY_KEYS if box.get(k))
            trimmed["_sourceProperties"] = present
            boxes.append(trimmed)
        return {**snapshot, "boxes": boxes}

    async def tool_outline(self, args: dict[str, Any]) -> list[dict[str, Any]]:
        include_tombstoned = bool(args.get("include_tombstoned", False))
        client = await self._ensure_attached()
        snapshot = await client.refresh_snapshot(timeout=self.timeout)
        lines: list[str] = []
        live = 0
        tombstoned = 0
        for box in snapshot.get("boxes", []):
            if box.get("_tombstone"):
                tombstoned += 1
                if not include_tombstoned:
                    continue
            live += 1
            uid = (box.get("uid") or "")[:8]
            name = box.get("name") or "(unnamed)"
            props = [k for k in sorted(SOURCE_PROPERTY_KEYS) if box.get(k)]
            n_children = len(box.get("children") or [])
            running = any(v for v in (box.get("runningStrands") or {}).values())
            parts = [f"{name}  [{uid}]"]
            if props:
                parts.append("{" + ",".join(props) + "}")
            if n_children:
                parts.append(f"{n_children}ch")
            if running:
                parts.append("▶running")
            line = "  ·  ".join(parts)
            note = box.get("notes")
            if note:
                flat = " ".join(str(note).split())
                preview = flat[:140] + ("…" if len(flat) > 140 else "")
                line += f"\n      notes: {preview}"
            lines.append(line)
        header = f"{snapshot.get('documentName')} — {live} live box(es)"
        if tombstoned and not include_tombstoned:
            header += f" ({tombstoned} tombstoned, hidden)"
        text = "\n".join([header, ""] + lines)
        return [{"type": "text", "text": text}]

    async def tool_read_note(self, args: dict[str, Any]) -> dict[str, Any]:
        token = args.get("box")
        if not token:
            raise McpToolError("field_read_note requires a 'box' (uid or name).")
        client = await self._ensure_attached()
        await client.refresh_snapshot(timeout=self.timeout)
        uid = self._resolve_box(client, str(token))
        box = client.boxes[uid]
        return {"uid": uid, "name": box.get("name"), "notes": box.get("notes")}

    async def tool_write_note(self, args: dict[str, Any]) -> dict[str, Any]:
        token = args.get("box")
        notes = args.get("notes")
        if not token:
            raise McpToolError("field_write_note requires a 'box' (uid or name).")
        if notes is None:
            raise McpToolError("field_write_note requires 'notes' text.")
        client = await self._ensure_attached()
        await client.refresh_snapshot(timeout=self.timeout)
        uid = self._resolve_box(client, str(token))
        return await client.set_property(
            uid=uid, property_name="notes", value=str(notes), timeout=self.timeout
        )

    async def tool_read_box(self, args: dict[str, Any]) -> dict[str, Any]:
        token = args.get("box")
        if not token:
            raise McpToolError("field_read_box requires a 'box' (uid or name).")
        client = await self._ensure_attached()
        await client.refresh_snapshot(timeout=self.timeout)
        uid = self._resolve_box(client, str(token))
        return client.boxes[uid]

    async def tool_lifecycle_census(self, args: dict[str, Any]) -> dict[str, Any]:
        client = await self._ensure_attached()
        token = args.get("box")
        uid = None
        if token:
            await client.refresh_snapshot(timeout=self.timeout)
            uid = self._resolve_box(client, str(token))
        return await client.lifecycle_census(uid=uid, timeout=self.timeout)

    async def tool_create_box(self, args: dict[str, Any]) -> dict[str, Any]:
        name = str(args.get("name", "Untitled"))
        frame = args.get("frame") or {}
        client = await self._ensure_attached()
        payload = await client.create_box(
            name=name,
            x=float(frame.get("x", args.get("x", 0.0))),
            y=float(frame.get("y", args.get("y", 0.0))),
            w=float(frame.get("w", args.get("w", 300.0))),
            h=float(frame.get("h", args.get("h", 170.0))),
            properties=args.get("properties") or {},
            timeout=self.timeout,
            auto_graphics_parent=False,
        )
        return payload

    async def tool_run_transcript(self, args: dict[str, Any]) -> dict[str, Any]:
        content = args.get("yaml") or args.get("content")
        if not content:
            raise McpToolError("field_run_transcript requires a 'yaml' operation transcript.")
        name = str(args.get("name") or ADHOC_MACRO_NAME)
        scope = str(args.get("scope") or "document")
        client = await self._ensure_attached()
        write = await client.macro_transcript_command(
            command="write", name=name, content=str(content), scope=scope, timeout=self.timeout
        )
        if not write.get("success", False):
            raise McpToolError(f"Failed to write transcript {name!r}: {write.get('message')}")
        run = await client.macro_transcript_command(
            command="run", name=name, scope=scope, timeout=max(self.timeout, 120.0)
        )
        return run

    async def tool_write_macro(self, args: dict[str, Any]) -> dict[str, Any]:
        name = args.get("name")
        content = args.get("content")
        if not name or content is None:
            raise McpToolError("field_write_macro requires 'name' and 'content'.")
        client = await self._ensure_attached()
        return await client.macro_transcript_command(
            command="write", name=str(name), content=str(content),
            scope=str(args.get("scope") or "document"), timeout=self.timeout,
        )

    async def tool_run_macro(self, args: dict[str, Any]) -> dict[str, Any]:
        name = args.get("name")
        if not name:
            raise McpToolError("field_run_macro requires a 'name'.")
        client = await self._ensure_attached()
        return await client.macro_transcript_command(
            command="run", name=str(name),
            scope=str(args.get("scope") or "document"), timeout=max(self.timeout, 120.0),
        )

    async def tool_list_macros(self, args: dict[str, Any]) -> dict[str, Any]:
        client = await self._ensure_attached()
        return await client.macro_transcript_command(
            command="list", scope=str(args.get("scope") or "document"), timeout=self.timeout
        )

    async def tool_read_macro(self, args: dict[str, Any]) -> dict[str, Any]:
        name = args.get("name")
        if not name:
            raise McpToolError("field_read_macro requires a 'name'.")
        client = await self._ensure_attached()
        return await client.macro_transcript_command(
            command="read", name=str(name),
            scope=str(args.get("scope") or "document"), timeout=self.timeout,
        )

    async def tool_transcript_page(self, args: dict[str, Any]) -> dict[str, Any]:
        client = await self._ensure_attached()
        return await client.macro_transcript_command(
            command="page",
            offset=int(args.get("offset", 0)),
            limit=int(args.get("limit", 80)),
            mode=str(args.get("mode") or ""),
            timeout=self.timeout,
        )

    async def tool_screenshot_box(self, args: dict[str, Any]) -> list[dict[str, Any]]:
        token = args.get("box")
        if not token:
            raise McpToolError("field_screenshot_box requires a 'box' (uid or name).")
        client = await self._ensure_attached()
        await client.refresh_snapshot(timeout=self.timeout)
        uid = self._resolve_box(client, str(token))
        out_dir = Path(__file__).resolve().parent.parent / ".field-backdoor" / "mcp-screenshots"
        out_dir.mkdir(parents=True, exist_ok=True)
        out_path = out_dir / f"{uid}.png"
        payload = await client.screenshot_box(
            uid=uid,
            path=str(out_path),
            padding=float(args.get("padding", 24.0)),
            pan_into_view=bool(args.get("pan_into_view", True)),
            open_outputs=bool(args.get("open_outputs", False)),
            timeout=max(self.timeout, 60.0),
        )
        saved = Path(payload.get("path", out_path))
        if not saved.exists():
            raise McpToolError(f"Screenshot did not produce a file: {json.dumps(payload)}")
        data = saved.read_bytes()
        return [
            {"type": "text", "text": f"Screenshot of box {uid} saved to {saved}"},
            {"type": "image", "data": base64.b64encode(data).decode("ascii"), "mimeType": "image/png"},
        ]

    async def tool_debug_probe(self, args: dict[str, Any]) -> dict[str, Any]:
        symbol = args.get("symbol")
        line = args.get("line")
        if not symbol:
            raise McpToolError("field_debug_probe requires a 'symbol' (variable name to watch).")
        if line is None:
            raise McpToolError("field_debug_probe requires a 'line' (1-based source line).")

        token = args.get("box")
        file_arg = args.get("file")
        class_arg = args.get("class")
        if not token and not file_arg and not class_arg:
            raise McpToolError(
                "field_debug_probe needs a target: either a 'box' (uid/name — probes that "
                "box's running snippet) or a 'file'/'class' (probes anywhere in loaded src/)."
            )

        max_hits = int(args.get("max_hits", 16))
        timeout_ms = int(args.get("timeout_ms", 5_000))
        thread = args.get("thread")
        thread_name = str(thread) if thread else None
        note_box = args.get("note_box")
        # wait_for budget must outlast the JVM-side sampling window.
        wait_timeout = max(self.timeout, timeout_ms / 1000.0 + 5.0)

        client = await self._ensure_attached()

        if token:
            # Box mode: probe a running box's snippet code.
            await client.refresh_snapshot(timeout=self.timeout)
            uid = self._resolve_box(client, str(token))
            box = client.boxes[uid]
            property_name = str(args.get("property") or "code")
            all_code = box.get(property_name)
            if not all_code:
                raise McpToolError(
                    f"Box {uid} has no {property_name!r} source to probe. Probes only work "
                    "on a running Kotlin box; run the box first, then probe it."
                )
            payload = await client.debug_probe(
                uid=uid,
                symbol=str(symbol),
                line=int(line),
                all_code=str(all_code),
                property_name=property_name,
                thread_name=thread_name,
                max_hits=max_hits,
                timeout_ms=timeout_ms,
                timeout=wait_timeout,
            )
            target: dict[str, Any] = {"box": uid, "property": property_name}
        else:
            # Source mode: probe any loaded class/file by name. The JDI helper matches
            # on the source basename and/or fully-qualified class name, so a repo path
            # like "src/jvmMain/kotlin/afield/Server.kt" reduces to "Server.kt".
            source_name = Path(str(file_arg)).name if file_arg else None
            class_name = str(class_arg) if class_arg else None
            payload = await client.debug_source_probe(
                symbol=str(symbol),
                line=int(line),
                class_name=class_name,
                source_name=source_name,
                thread_name=thread_name,
                max_hits=max_hits,
                timeout_ms=timeout_ms,
                timeout=wait_timeout,
            )
            target = {}
            if source_name:
                target["file"] = source_name
            if class_name:
                target["class"] = class_name

        samples = payload.get("samples") or []
        scope = payload.get("scope") or []
        error = payload.get("error")

        # Setup/availability failure (no JDWP port, bad/empty target, helper couldn't
        # start): the probe never ran. Raise so the model gets the actionable message
        # front-and-center instead of an empty samples list it might read as "no hits".
        if error and not samples and error not in PROBE_OUTCOME_CODES:
            raise McpToolError(error)

        result: dict[str, Any] = {
            **target,
            "symbol": payload.get("symbol", symbol),
            "line": payload.get("line", line),
            "phase": payload.get("phase"),
            "done": payload.get("done", False),
            "error": error,
            "sampleCount": len(samples),
            "samples": samples,
        }
        if error in PROBE_OUTCOME_HELP:
            result["errorHelp"] = PROBE_OUTCOME_HELP[error]
        if scope:
            result["scope"] = scope

        # Optionally leave a debug-session trail in a box's notes.
        if note_box:
            await client.refresh_snapshot(timeout=self.timeout)
            note_uid = self._resolve_box(client, str(note_box))
            where = target.get("file") or target.get("class") or target.get("box") or "?"
            note_line = _probe_note_line(
                where, result["symbol"], result["line"], result["phase"], error, samples
            )
            existing = client.boxes[note_uid].get("notes") or ""
            new_notes = f"{existing}\n{note_line}" if existing else note_line
            await client.set_property(
                uid=note_uid, property_name="notes", value=new_notes, timeout=self.timeout
            )
            result["notedBox"] = note_uid

        return result

    # ---- MCP protocol -----------------------------------------------------

    TOOLS: list[dict[str, Any]] = [
        {"name": "field_reply_note", "description": "Append a persistent reply to a geometry annotation for findings, decisions, verification or handoff context. Replies do not resolve or reopen notes. Requires the current note version and geometry revision. Original note, previous replies and images remain intact.", "inputSchema": {"type":"object", "required":["viewId","noteId","text","actor","checkedRevision","expectedVersion"], "properties": {
            "viewId":{"type":"string"}, "noteId":{"type":"string"}, "text":{"type":"string","description":"Durable findings and evidence, up to 16000 characters."},
            "actor":{"type":"string","description":"Person or agent/task name."}, "checkedRevision":{"type":"string"}, "expectedVersion":{"type":"integer"}
        }}},
        {"name": "field_review_note", "description": "Resolve a verified geometry annotation, or reopen it. Requires its current version and the geometry revision actually checked; preserves the original note, images and resolution history. Never resolve merely because a note was read or its geometry rebuilt.", "inputSchema": {"type":"object", "required":["viewId","noteId","status","summary","actor","checkedRevision","expectedVersion"], "properties": {
            "viewId":{"type":"string"}, "noteId":{"type":"string"}, "status":{"type":"string","enum":["open","resolved"]},
            "summary":{"type":"string","description":"What changed and how it was verified, or why the note is reopened."}, "actor":{"type":"string","description":"Person or agent/task name."}, "checkedRevision":{"type":"string"}, "expectedVersion":{"type":"integer"}
        }}},
        {"name": "field_inspect_view", "description": "Read unresolved geometry notes with received=true, without opening a browser. Completed notes require includeResolved=true. Reading does not acknowledge a note; use field_review_note after verification. Otherwise inspect, restore a note/view or pick a feature in the capture browser using normalized canvas coordinates.", "inputSchema": {"type":"object", "required":["viewId"], "properties": {
            "viewId":{"type":"string"}, "state":{"type":"object"}, "section":{"type":"object"}, "hidden":{"type":"object"}, "assembly":{"type":"number"}, "received":{"type":"boolean"}, "includeResolved":{"type":"boolean","description":"Include completed notes and their resolution history."}, "evidenceDirectory":{"type":"string","description":"With received=true, export saved notes and their context/crop PNGs here."}, "note":{"type":"string"}, "region":{"type":"object","required":["cx","cy","width","height"],"properties":{"cx":{"type":"number"},"cy":{"type":"number"},"width":{"type":"number"},"height":{"type":"number"},"angle":{"type":"number"}}},
            "pick":{"type":"object","required":["x","y"],"properties":{"x":{"type":"number","minimum":0,"maximum":1},"y":{"type":"number","minimum":0,"maximum":1},"kind":{"type":"string","enum":["point","face","edge","vertex"]}}}}}},
        {
            "name": "field_status",
            "description": "Check whether Field4 is running, which documents are open, which one this bridge is attached to, and the server's own workspace directory (serverWorkspace) that document names resolve against. Safe to call anytime; does not attach.",
            "inputSchema": {"type": "object", "properties": {}},
        },
        {
            "name": "field_list_open_documents",
            "description": "List the documents currently open in the running Field4 app (name, live session count, whether ephemeral/scratch).",
            "inputSchema": {"type": "object", "properties": {}},
        },
        {
            "name": "field_open_document",
            "description": "Attach this conversation to a specific Field4 document by name (or open a scratch document). If omitted, the bridge auto-attaches to the single open document.",
            "inputSchema": {
                "type": "object",
                "properties": {
                    "document": {"type": "string", "description": "Document name as shown in Field4. Omit to auto-pick the open document."},
                    "scratch": {"type": "boolean", "description": "Open a fresh ephemeral scratch document instead."},
                    "create": {"type": "boolean", "description": "Allow creating a new empty document when the name does not exist (default false: an unknown name is an error, not a new document)."},
                },
            },
        },
        {
            "name": "field_snapshot",
            "description": "Return the full state of the attached document: every box with its uid, name, frame, graph edges (children/nested), run state, and (by default) all code/shader source bodies. Set include_code=false for a lightweight graph overview.",
            "inputSchema": {
                "type": "object",
                "properties": {
                    "include_code": {"type": "boolean", "description": "Include full source bodies (default true). False lists which source properties exist without their contents."}
                },
            },
        },
        {
            "name": "field_read_box",
            "description": "Return the full snapshot of a single box (all code/shader bodies, frame, graph edges, run state) by uid or name.",
            "inputSchema": {
                "type": "object",
                "properties": {"box": {"type": "string", "description": "Box uid or unique name."}},
                "required": ["box"],
            },
        },
        {
            "name": "field_lifecycle_census",
            "description": "Count what each box of the attached document (or one box) still holds at runtime: coroutine jobs, running strands, stop hooks, and owned resources by kind (audio patches, patSynth feeders, control callbacks, Runloop preRun/postRun hooks, render-target modules), plus whether its Kotlin engine is live. liveTotal excludes the engine, so a stopped box that left nothing behind reports 0.",
            "inputSchema": {
                "type": "object",
                "properties": {"box": {"type": "string", "description": "Box uid or unique name. Omit for every box."}},
            },
        },
        {
            "name": "field_outline",
            "description": "Token-efficient orientation: a compact one-line-per-box map of the attached document (live boxes only by default, no geometry, short ids), with a preview of each box's notes. Start here to get oriented before pulling full code.",
            "inputSchema": {
                "type": "object",
                "properties": {
                    "include_tombstoned": {"type": "boolean", "description": "Also list deleted (tombstoned) boxes (default false)."}
                },
            },
        },
        {
            "name": "field_read_note",
            "description": "Read a box's notes property (persistent, markdown-formatted, user-visible in-document memory). Read notes first to orient; they carry human/agent intent in prose.",
            "inputSchema": {
                "type": "object",
                "properties": {"box": {"type": "string", "description": "Box uid or unique name."}},
                "required": ["box"],
            },
        },
        {
            "name": "field_write_note",
            "description": "Write a box's notes property (persistent markdown, saved to disk, visible in the app). Use this to record durable findings, intent, and orientation for the user and future sessions. Overwrites existing notes.",
            "inputSchema": {
                "type": "object",
                "properties": {
                    "box": {"type": "string", "description": "Box uid or unique name."},
                    "notes": {"type": "string", "description": "Markdown notes to store on the box."},
                },
                "required": ["box", "notes"],
            },
        },
        {
            "name": "field_create_box",
            "description": "Create a new box in the attached document at a canvas frame, optionally seeding properties (e.g. {\"code\": \"...\"}).",
            "inputSchema": {
                "type": "object",
                "properties": {
                    "name": {"type": "string"},
                    "frame": {"type": "object", "properties": {"x": {"type": "number"}, "y": {"type": "number"}, "w": {"type": "number"}, "h": {"type": "number"}}},
                    "properties": {"type": "object", "description": "Initial box properties, e.g. code / fragment / notes."},
                },
                "required": ["name"],
            },
        },
        {
            "name": "field_run_transcript",
            "description": "Run an inline operation-transcript (YAML with a top-level 'operations:' list) against the attached document. Writes it as a macro then runs it; returns {success, applied, skipped, errors, output}. This is the write path: create/edit/connect/execute/launch/stop boxes. See dev_docs/agents/OPERATION_TRANSCRIPT.md for the op vocabulary.",
            "inputSchema": {
                "type": "object",
                "properties": {
                    "yaml": {"type": "string", "description": "The operation transcript YAML."},
                    "name": {"type": "string", "description": "Macro name to store it under (default mcp-adhoc)."},
                    "scope": {"type": "string", "description": "document | project | workspace (default document)."},
                },
                "required": ["yaml"],
            },
        },
        {
            "name": "field_write_macro",
            "description": "Save a named operation-transcript macro without running it.",
            "inputSchema": {
                "type": "object",
                "properties": {
                    "name": {"type": "string"},
                    "content": {"type": "string", "description": "Operation transcript YAML."},
                    "scope": {"type": "string"},
                },
                "required": ["name", "content"],
            },
        },
        {
            "name": "field_run_macro",
            "description": "Run a previously saved macro by name against the attached document.",
            "inputSchema": {
                "type": "object",
                "properties": {"name": {"type": "string"}, "scope": {"type": "string"}},
                "required": ["name"],
            },
        },
        {
            "name": "field_list_macros",
            "description": "List saved operation-transcript macros for the given scope.",
            "inputSchema": {"type": "object", "properties": {"scope": {"type": "string"}}},
        },
        {
            "name": "field_read_macro",
            "description": "Read the YAML of a saved macro by name.",
            "inputSchema": {
                "type": "object",
                "properties": {"name": {"type": "string"}, "scope": {"type": "string"}},
                "required": ["name"],
            },
        },
        {
            "name": "field_transcript_page",
            "description": "Read a page of the attached document's recorded operation history (the running transcript of edits).",
            "inputSchema": {
                "type": "object",
                "properties": {
                    "offset": {"type": "integer"},
                    "limit": {"type": "integer"},
                    "mode": {"type": "string", "description": "e.g. 'hide-waits' to omit wait ops."},
                },
            },
        },
        {
            "name": "field_screenshot_box",
            "description": "Render a PNG screenshot of a box's output and return it as an image. Requires the server's Playwright/graphics context; may be slow on first use.",
            "inputSchema": {
                "type": "object",
                "properties": {
                    "box": {"type": "string", "description": "Box uid or unique name."},
                    "padding": {"type": "number"},
                    "pan_into_view": {"type": "boolean"},
                    "open_outputs": {"type": "boolean"},
                },
                "required": ["box"],
            },
        },
        {
            "name": "field_debug_probe",
            "description": (
                "Ask the running JVM what a variable's value is at runtime, and get the answer. "
                "Installs a non-suspending JDWP probe (via the JDI debug helper) that watches one "
                "symbol at one source line and samples its value each time that line executes — it "
                "never pauses the render/compute loop. Returns the observed 'samples' (value text, "
                "type, thread, resolution) plus any in-scope 'scope' symbols.\n\n"
                "Two targeting modes (give exactly one):\n"
                "  • Source mode — probe ANYWHERE in loaded src/ code: pass 'file' (e.g. "
                "'Server.kt' or 'src/jvmMain/kotlin/afield/Server.kt') and/or 'class' (fully "
                "qualified, e.g. 'afield.Server'), with 'line' the real file line.\n"
                "  • Box mode — probe a running box's snippet: pass 'box' (uid/name), 'line' within "
                "the box 'property' (default 'code'); run the box first.\n\n"
                "Preconditions: Field launches with remote debugging on by default (JDWP); the "
                "target class must be loaded (or load within timeout_ms), and the running JVM must "
                "be built from the source whose line numbers you pass. Good for 'what is X here?', "
                "'is this loop producing the values I expect?', and inspecting live state.\n\n"
                "Pass 'note_box' (a box uid/name) to append a one-line summary of this probe to that "
                "box's 'notes' — a running debug-session trail kept in the document itself."
            ),
            "inputSchema": {
                "type": "object",
                "properties": {
                    "symbol": {"type": "string", "description": "Variable name to watch."},
                    "line": {
                        "type": "integer",
                        "description": "1-based source line where the symbol is in scope.",
                    },
                    "file": {
                        "type": "string",
                        "description": "Source mode: file path or basename, e.g. 'Server.kt'.",
                    },
                    "class": {
                        "type": "string",
                        "description": "Source mode: fully-qualified class, e.g. 'afield.Server'.",
                    },
                    "box": {
                        "type": "string",
                        "description": "Box mode: box uid or unique name.",
                    },
                    "property": {
                        "type": "string",
                        "description": "Box mode: source property to probe (default 'code').",
                    },
                    "max_hits": {
                        "type": "integer",
                        "description": "Max value samples to collect before completing (default 16).",
                    },
                    "timeout_ms": {
                        "type": "integer",
                        "description": "How long to wait for the line to execute, in ms (default 5000).",
                    },
                    "thread": {
                        "type": "string",
                        "description": "Optional: only sample hits on this thread name.",
                    },
                    "note_box": {
                        "type": "string",
                        "description": "Optional: box uid/name to append this probe's summary to its "
                                       "'notes' (a debug-session trail kept in the document).",
                    },
                },
                "required": ["symbol", "line"],
            },
        },
    ]

    def _dispatch_table(self):
        return {
            "field_status": self.tool_status,
            "field_inspect_view": self.tool_inspect_view,
            "field_review_note": self.tool_review_note,
            "field_reply_note": self.tool_reply_note,
            "field_list_open_documents": self.tool_list_open_documents,
            "field_open_document": self.tool_open_document,
            "field_snapshot": self.tool_snapshot,
            "field_outline": self.tool_outline,
            "field_read_box": self.tool_read_box,
            "field_lifecycle_census": self.tool_lifecycle_census,
            "field_read_note": self.tool_read_note,
            "field_write_note": self.tool_write_note,
            "field_create_box": self.tool_create_box,
            "field_run_transcript": self.tool_run_transcript,
            "field_write_macro": self.tool_write_macro,
            "field_run_macro": self.tool_run_macro,
            "field_list_macros": self.tool_list_macros,
            "field_read_macro": self.tool_read_macro,
            "field_transcript_page": self.tool_transcript_page,
            "field_screenshot_box": self.tool_screenshot_box,
            "field_debug_probe": self.tool_debug_probe,
        }

    def _all_tools(self) -> list[dict[str, Any]]:
        """Native bridge tools plus dynamically-discovered ai4 registry tools."""
        return [*self.TOOLS, *self._ai_tools]

    async def _call_ai_tool(self, msg_id: Any, flat_name: str, arguments: dict[str, Any]) -> dict[str, Any]:
        ai4_name = self._ai_name_map.get(flat_name)
        try:
            client = await self._ensure_attached()
            payload = await client.ai_tool_call(
                name=ai4_name, arguments=arguments, timeout=max(self.timeout, 120.0)
            )
            if not payload.get("ok", False):
                msg = payload.get("error") or "unknown error"
                return self._ok(msg_id, {"content": [{"type": "text", "text": f"{flat_name} failed: {msg}"}], "isError": True})
            result = payload.get("result")
            text = json.dumps(result, indent=2, default=str)
            # The RPC can succeed while the tool refuses a stale revision or a read-only write.
            failed = isinstance(result, dict) and (result.get("success") is False or result.get("ok") is False)
            content = [{"type": "text", "text": text}]
            # Images travel as MCP image blocks, never as base64 text in the tool result.
            for image in payload.get("images", [])[:8]:
                url = image.get("dataUrl", "")
                header, separator, encoded = url.partition(",")
                if not separator or header not in ("data:image/png;base64", "data:image/jpeg;base64", "data:image/webp;base64"):
                    continue
                if image.get("caption"):
                    content.append({"type": "text", "text": image["caption"]})
                content.append({"type": "image", "mimeType": header[5:-7], "data": encoded})
            return self._ok(msg_id, {"content": content, "isError": failed})
        except McpToolError as exc:
            return self._ok(msg_id, {"content": [{"type": "text", "text": str(exc)}], "isError": True})
        except Exception as exc:  # noqa: BLE001
            log("ai tool crashed:", "".join(traceback.format_exception(exc)))
            return self._ok(msg_id, {"content": [{"type": "text", "text": f"Unexpected error: {exc}"}], "isError": True})

    def _notify(self, method: str, params: dict[str, Any]) -> None:
        self._write({"jsonrpc": "2.0", "method": method, "params": params})

    async def handle_request(self, msg: dict[str, Any]) -> dict[str, Any] | None:
        method = msg.get("method")
        msg_id = msg.get("id")
        # Notifications (no id) get no response.
        is_notification = "id" not in msg

        if method == "initialize":
            client_pv = (msg.get("params") or {}).get("protocolVersion")
            if isinstance(client_pv, str) and client_pv:
                self.protocol_version = client_pv
            return self._ok(msg_id, {
                "protocolVersion": self.protocol_version,
                # listChanged: true — ai4 tools appear/refresh once we attach to Field.
                "capabilities": {"tools": {"listChanged": True}},
                "serverInfo": {"name": SERVER_NAME, "version": SERVER_VERSION},
            })

        if method in ("notifications/initialized", "initialized"):
            return None

        if method == "ping":
            return self._ok(msg_id, {})

        if method == "tools/list":
            # Best-effort: if Field is up with a document open, attach so the ai4
            # tools are discovered and included. Never fail listing if it is not.
            try:
                await self._ensure_attached()
            except McpToolError:
                pass
            except Exception as exc:  # noqa: BLE001
                log("tools/list attach failed (native tools only):", exc)
            return self._ok(msg_id, {"tools": self._all_tools()})

        if method == "tools/call":
            params = msg.get("params") or {}
            name = params.get("name")
            arguments = params.get("arguments") or {}
            # Route flattened ai4 tools through the provider-neutral registry.
            if name in self._ai_name_map:
                return await self._call_ai_tool(msg_id, name, arguments)
            handler = self._dispatch_table().get(name)
            if handler is None:
                return self._error(msg_id, -32602, f"Unknown tool: {name}")
            try:
                result = await handler(arguments)
                if isinstance(result, list):  # already a content array (e.g. image)
                    content = result
                else:
                    content = [{"type": "text", "text": json.dumps(result, indent=2, default=str)}]
                return self._ok(msg_id, {"content": content, "isError": False})
            except McpToolError as exc:
                return self._ok(msg_id, {"content": [{"type": "text", "text": str(exc)}], "isError": True})
            except BackdoorError as exc:
                log("backdoor error:", exc)
                return self._ok(msg_id, {"content": [{"type": "text", "text": f"Field backdoor error: {exc}"}], "isError": True})
            except Exception as exc:  # noqa: BLE001 — never crash the server loop
                log("tool crashed:", "".join(traceback.format_exception(exc)))
                return self._ok(msg_id, {"content": [{"type": "text", "text": f"Unexpected error: {exc}"}], "isError": True})

        if is_notification:
            return None
        return self._error(msg_id, -32601, f"Method not found: {method}")

    @staticmethod
    def _ok(msg_id: Any, result: Any) -> dict[str, Any]:
        return {"jsonrpc": "2.0", "id": msg_id, "result": result}

    @staticmethod
    def _error(msg_id: Any, code: int, message: str) -> dict[str, Any]:
        return {"jsonrpc": "2.0", "id": msg_id, "error": {"code": code, "message": message}}

    async def serve(self) -> None:
        log(f"starting; target Field port {self.port}")
        loop = asyncio.get_running_loop()
        reader = asyncio.StreamReader()
        protocol = asyncio.StreamReaderProtocol(reader)
        try:
            await loop.connect_read_pipe(lambda: protocol, sys.stdin)
            use_reader = True
        except (NotImplementedError, ValueError):
            use_reader = False  # e.g. Windows: fall back to blocking readline in a thread

        while True:
            if use_reader:
                line = await reader.readline()
            else:
                raw = await asyncio.to_thread(sys.stdin.buffer.readline)
                line = raw
            if not line:
                break  # stdin closed → client went away
            text = line.decode("utf-8").strip()
            if not text:
                continue
            try:
                msg = json.loads(text)
            except json.JSONDecodeError:
                log("dropping non-JSON line:", text[:200])
                continue
            try:
                response = await self.handle_request(msg)
            except Exception as exc:  # noqa: BLE001
                log("handler crashed:", "".join(traceback.format_exception(exc)))
                response = self._error(msg.get("id"), -32603, f"Internal error: {exc}")
            if response is not None:
                self._write(response)

        if self.client is not None:
            try:
                await self.client.close()
            except Exception:  # noqa: BLE001
                pass
        log("stdin closed; exiting")

    @staticmethod
    def _write(obj: dict[str, Any]) -> None:
        data = json.dumps(obj, default=str).encode("utf-8")
        sys.stdout.buffer.write(data + b"\n")
        sys.stdout.buffer.flush()


def resolve_mcp_port(port: int | None, environ: dict[str, str] | None = None) -> int:
    """The port this bridge attaches to, announced on stderr when it was not explicit.

    Unlike `field_backdoor.py`, whose default is now a free automation port, this bridge keeps
    the developer port: it never launches a JVM (see the module docstring), so it is
    `--attach-existing` by construction, and a free automation port would by definition have
    nothing on it. `.mcp.json` pins `PORT=9092` explicitly; a bare launch says what it chose.
    """
    if port is not None:
        return port
    env = os.environ if environ is None else environ
    if env.get("PORT"):
        return int(env["PORT"])
    log(f"no --port or PORT given; attaching to the developer Field on {DEVELOPER_PORT}")
    return DEVELOPER_PORT


def main() -> int:
    import argparse

    parser = argparse.ArgumentParser(description="Field4 MCP stdio bridge")
    parser.add_argument(
        "--port",
        type=int,
        default=None,
        help=f"Port of the running Field to attach to (default: $PORT, else {DEVELOPER_PORT}).",
    )
    parser.add_argument("--timeout", type=float, default=DEFAULT_TIMEOUT)
    args = parser.parse_args()
    args.port = resolve_mcp_port(args.port)

    server = FieldMcpServer(port=args.port, timeout=args.timeout)
    try:
        asyncio.run(server.serve())
    except KeyboardInterrupt:
        pass
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
