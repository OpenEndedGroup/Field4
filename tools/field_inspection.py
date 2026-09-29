"""Shared CLI/MCP control of the existing Three inspector; no gallery-specific JavaScript."""
import json
from pathlib import Path


def inspection_script(view_id, state=None, section=None, hidden=None, assembly=None, pick=None, note=None, region=None, xray=None, xray_opacity=None):
    request=json.dumps(dict(viewId=view_id,state=state,section=section,hidden=hidden,assembly=assembly,pick=pick,note=note,region=region,xray=xray,xrayOpacity=xray_opacity),allow_nan=False)
    return """const request = %s;
function find(w) {
    if (w.FieldInspector && new URL(w.location.href).searchParams.get('uid') === request.viewId) return w;
    for (const frame of w.document.querySelectorAll('iframe')) {
        try { const found=find(frame.contentWindow); if(found)return found; } catch(e) { /* unrelated origin */ }
    }
    return null;
}
let target=null;
const deadline=Date.now()+10000;
while(Date.now()<deadline) { target=find(window); if(target && target.FieldInspector.snapshot().partIds.length)break; await new Promise(resolve=>setTimeout(resolve,50)); }
if(!target || !target.FieldInspector.snapshot().partIds.length)throw new Error('No rendered inspector for view '+request.viewId);
const api=target.FieldInspector;
if(request.state){if(request.state.view)api.review(request.state);else api.restore(request.state);}
if(request.section)api.section(request.section.normal, request.section.fraction);
if(request.hidden)for(const [id,hide] of Object.entries(request.hidden))api.hide(id,hide);
if(request.assembly!==null)api.assembly(request.assembly);
if(request.xray!==null || request.xrayOpacity!==null)api.xray(request.xray ?? api.snapshot().xray,request.xrayOpacity ?? api.snapshot().xrayOpacity);
if(request.region)api.region(request.region);
if(request.pick)api.pick(request.pick.x,request.pick.y,request.pick.kind || 'face');
return request.note!==null ? api.observe(request.note) : api.snapshot();""" % request


async def inspect_view(client, view_id, *, state=None, section=None, hidden=None, assembly=None, pick=None, note=None, region=None, received=False, evidence_dir=None, include_resolved=False, xray=None, xray_opacity=None, timeout=60):
    if include_resolved and not received:
        raise ValueError("Review history requires received=True")
    if evidence_dir and not received:
        raise ValueError("Image export requires received=True")
    if received:
        if any(value is not None for value in (state,section,assembly,pick,note,region,xray,xray_opacity)) or hidden:
            raise ValueError("Reading the user's inbox cannot also change the capture browser")
        import asyncio, ssl, urllib.request, urllib.parse
        def read():
            url=f"https://localhost:{client.port}/three-review?"+urllib.parse.urlencode({"uid":view_id, "includeResolved": str(include_resolved).lower()})
            with urllib.request.urlopen(url,context=ssl._create_unverified_context(),timeout=timeout) as response:
                inbox = json.load(response)
            result = {"ok": True, "result": inbox}
            if evidence_dir:
                def image(digest):
                    image_url = f"https://localhost:{client.port}/three-review-image?" + urllib.parse.urlencode({"uid": view_id, "sha256": digest})
                    with urllib.request.urlopen(image_url, context=ssl._create_unverified_context(), timeout=timeout) as response:
                        return response.read(2_000_001)
                result["evidenceFiles"] = export_review_evidence(inbox, evidence_dir, image)
            return result
        return await asyncio.to_thread(read)
    import field_backdoor as fb
    await fb.ensure_playwright_attached(client,timeout=timeout,reason="inspect-view")
    return await client.playwright_input(action="pageEval",script=inspection_script(view_id,state,section,hidden,assembly,pick,note,region,xray,xray_opacity),script_mode="body",timeout=timeout,wait_ms=100)


def store_observation(path, *, artifact_digest, view, text, verdict="note"):
    """Append-only observations. A rebuild cannot quietly inherit an acceptance or erase rejection."""
    import datetime, uuid
    if verdict not in {"note","accepted","rejected"}:raise ValueError("Invalid review verdict")
    if not artifact_digest or view.get("artifactDigest")!=artifact_digest:raise ValueError("View and artifact identities differ")
    record=dict(schemaVersion=1,id=uuid.uuid4().hex,createdAt=datetime.datetime.now(datetime.timezone.utc).isoformat(),artifactDigest=artifact_digest,view=view,text=text,verdict=verdict)
    path=Path(path);path.parent.mkdir(parents=True,exist_ok=True)
    with path.open("a") as out:out.write(json.dumps(record,allow_nan=False)+"\n")
    return record


def export_review_evidence(inbox, directory, read_image):
    """Export retained notes plus verified PNGs; never navigate or move the human's viewer."""
    import hashlib
    import re
    directory = Path(directory)
    directory.mkdir(parents=True, exist_ok=True)
    exported = []
    for index, note in enumerate(inbox.get("notes", [])):
        evidence = note.get("evidence") or {}
        if not evidence:
            continue  # Older notes have geometry references but no retrospectively invented images.
        folder = directory / f"note-{index + 1:03d}"
        folder.mkdir(exist_ok=True)
        files = {}
        for kind in ("context", "crop"):
            metadata = evidence[kind]
            digest = metadata["sha256"]
            if not re.fullmatch(r"[0-9a-f]{64}", digest):
                raise ValueError("Invalid review image identity")
            data = read_image(digest)
            if len(data) > 2_000_000 or hashlib.sha256(data).hexdigest() != digest or not data.startswith(b"\x89PNG\r\n\x1a\n"):
                raise ValueError("Review image differs from its saved reference")
            path = folder / f"{kind}.png"
            path.write_bytes(data)
            files[kind] = str(path.resolve())
        path = folder / "note.json"
        path.write_text(json.dumps(note, indent=2))
        exported.append({"id": note.get("id"), "note": str(path.resolve()), **files})
    (directory / "inbox.json").write_text(json.dumps(inbox, indent=2))
    return exported


async def update_review_note(client, view_id, *, note_id, status, summary, actor, checked_revision, expected_version, timeout=60):
    """Resolve/reopen a specific note with optimistic concurrency and an explicit audit trail."""
    payload = dict(id=note_id, status=status, summary=summary, actor=actor,
                   checkedRevision=checked_revision, expectedVersion=expected_version)
    return await _write_review_note(client, view_id, payload, timeout)


async def reply_review_note(client, view_id, *, note_id, text, actor, checked_revision, expected_version, timeout=60):
    """Append a durable reply without changing the note's open/resolved status."""
    payload = dict(id=note_id, reply=text, actor=actor, checkedRevision=checked_revision, expectedVersion=expected_version)
    return await _write_review_note(client, view_id, payload, timeout)


async def _write_review_note(client, view_id, payload, timeout):
    if getattr(client, "read_only", False):
        return {"ok": False, "error": "The attached document is read-only"}
    import asyncio, ssl, urllib.request, urllib.parse, urllib.error
    def write():
        url = f"https://localhost:{client.port}/three-review-note?" + urllib.parse.urlencode({"uid": view_id})
        request = urllib.request.Request(url, data=json.dumps(payload, allow_nan=False).encode(),
                                         headers={"Content-Type": "application/json"}, method="POST")
        try:
            with urllib.request.urlopen(request, context=ssl._create_unverified_context(), timeout=timeout) as response:
                return json.load(response)
        except urllib.error.HTTPError as error:
            return {"ok": False, "status": error.code, "error": error.read(32_000).decode(errors="replace")}
    return await asyncio.to_thread(write)
