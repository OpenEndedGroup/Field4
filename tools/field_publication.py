"""Save the browser's publication as a local ZIP without logging the whole archive in one RPC."""
from __future__ import annotations

import base64
import json
import os
import tempfile
import uuid
from pathlib import Path
from typing import Any


async def publish_document(client: Any, path: str, *, timeout: float) -> dict[str, Any]:
    target = Path(path).expanduser().resolve()
    if target.suffix.lower() != ".zip":
        raise ValueError("Publication path must end in .zip")
    if target.exists():
        raise ValueError(f"Publication already exists: {target}")
    if not client.playwright_state:
        await client.open_playwright(timeout)
    token = json.dumps("publication-" + uuid.uuid4().hex)

    async def evaluate(script: str) -> Any:
        response = await client.playwright_input(action="pageEval", script=script, script_mode="body", timeout=timeout)
        if not response.get("ok"):
            raise RuntimeError(response.get("message") or "Publication browser operation failed")
        return response.get("result")

    partial = None
    try:
        result = await evaluate("""
            await import('./publishDocument.js');
            const result = await window.FieldPublication.build();
            const bytes = new Uint8Array(await result.blob.arrayBuffer());
            window.__fieldPublicationTransfers ??= Object.create(null);
            window.__fieldPublicationTransfers[TOKEN] = bytes;
            return {bytes:bytes.length, manifest:result.manifest, warnings:result.warnings};
        """.replace("TOKEN", token))
        size = result["bytes"]
        if not isinstance(size, int) or not 0 < size <= 256 * 1024 * 1024:
            raise RuntimeError("Publication archive exceeds the 256 MB transfer limit")
        target.parent.mkdir(parents=True, exist_ok=True)
        with tempfile.NamedTemporaryFile(prefix=".field-publication-", suffix=".zip", dir=target.parent, delete=False) as stream:
            partial = Path(stream.name)
            for start in range(0, size, 192 * 1024):
                end = min(size, start + 192 * 1024)
                chunk = await evaluate(f"""
                    const bytes = window.__fieldPublicationTransfers[{token}].subarray({start},{end});
                    let binary = '';
                    for(let i=0;i<bytes.length;i+=8192) binary += String.fromCharCode(...bytes.subarray(i,i+8192));
                    return btoa(binary);
                """)
                decoded = base64.b64decode(chunk, validate=True)
                if len(decoded) != end - start:
                    raise RuntimeError("Publication transfer returned an incomplete chunk")
                stream.write(decoded)
        # Linking refuses an existing destination even if another writer created it during capture.
        os.link(partial, target)
        manifest = result["manifest"]
        return {"ok": True, "path": str(target), "bytes": size, "manifest": manifest,
                "warnings": result["warnings"], "capturedOutputs": sum(bool(o.get("path")) for o in manifest["outputs"])}
    finally:
        if partial is not None:
            partial.unlink(missing_ok=True)
        try:
            await evaluate(f"delete window.__fieldPublicationTransfers?.[{token}]; return true;")
        except Exception:
            pass
