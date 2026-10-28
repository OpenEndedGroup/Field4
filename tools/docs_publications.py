"""Capture real docs documents, share their runtimes, and verify source provenance at site build."""
from __future__ import annotations

import argparse
import asyncio
import hashlib
import json
import os
import re
import shutil
import tempfile
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DOCS = ROOT / "public/docs"
PUBLICATIONS = DOCS / "assets/web_publications"
CATALOG = PUBLICATIONS / "catalog.json"
PILOTS = ("fline_examples", "three_output_gallery")


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def capture_digest() -> str:
    paths = [ROOT / 'src/commonMain/resources' / name for name in
             ('publication-capture.js', 'publication-three.js')]
    return hashlib.sha256(b''.join(p.read_bytes() for p in paths if p.is_file())).hexdigest()


def source_files(source: str, transcript: str | None = None) -> dict[str, str]:
    folder = (DOCS / source).parent
    files = [p for p in folder.rglob("*") if p.is_file() and p.name not in
             {"AGENTS.md", "README.md", "document.md"} and not any(s.startswith(".") for s in p.relative_to(folder).parts)]
    if transcript:
        files.append(ROOT / transcript)
    return {str(p.relative_to(ROOT)): digest(p) for p in sorted(files)}


def catalog() -> dict:
    return json.loads(CATALOG.read_text()) if CATALOG.exists() else {"version": 1, "documents": {}}


def write_catalog(index: dict) -> None:
    CATALOG.parent.mkdir(parents=True, exist_ok=True)
    pending = CATALOG.with_suffix('.new.json')
    pending.write_text(json.dumps(index, indent=2, sort_keys=True) + "\n")
    pending.replace(CATALOG)


def recipe_sources(recipe: dict) -> dict[str, str]:
    return source_files(recipe['source'], recipe.get('transcript'))


def snapshot_hashes(entry: dict) -> dict[str, str]:
    manifest_path = DOCS / entry['outputs']
    base = manifest_path.parent.parent
    paths = [DOCS / entry['file'], manifest_path]
    paths += [base / output['path'] for output in json.loads(manifest_path.read_text())['outputs']]
    return {path.relative_to(DOCS).as_posix(): digest(path) for path in paths}


def publication_problem(entry: dict) -> str | None:
    """Check the exported file graph without executing code or needing a browser."""
    if not all((DOCS / entry[key]).is_file() for key in ('file', 'outputs')):
        return 'snapshot or output manifest missing'
    for name, expected in entry.get('files', {}).items():
        path = DOCS / name
        if not path.is_file() or digest(path) != expected:
            return f'captured file changed: {name}'
    try:
        manifest_path = DOCS / entry['outputs']
        manifest = json.loads(manifest_path.read_text())
        outputs = manifest['outputs']
        if manifest.get('version') != 1 or not outputs or len(outputs) != entry['channels']:
            return 'invalid output manifest'
        base = manifest_path.parent.parent
        for output in outputs:
            path = output.get('path', '')
            if not re.fullmatch(r'outputs/\d+\.html', path) or not (base / path).is_file():
                return f'missing captured channel: {path}'
            for runtime in re.findall(r'(?:src|href)="(\.\./\.\./runtime/[a-f0-9]{20}/[a-zA-Z0-9_.-]+)"', (base / path).read_text()):
                runtime_path = (base / path).parent.joinpath(runtime)
                if not runtime_path.is_file():
                    return f'missing runtime: {runtime}'
                if digest(runtime_path)[:20] != runtime_path.parent.name:
                    return f'shared runtime changed: {runtime}'
    except (ValueError, KeyError, TypeError) as error:
        return f'invalid output manifest: {error}'
    return None


def pending_recipes(available: list[dict], *, mode: str) -> list[dict]:
    index = catalog()
    pending = []
    for recipe in available:
        previous = index['documents'].get(recipe['source']) or index.get('emptyDocuments', {}).get(recipe['source'])
        if (mode == 'always' or not previous or previous['sources'] != recipe_sources(recipe)
                or previous.get('captureDigest') != capture_digest()
                or (previous.get('file') and publication_problem(previous))):
            pending.append(recipe)
    return pending


def record_empty(recipe: dict, sources: dict[str, str]) -> None:
    index = catalog()
    index['documents'].pop(recipe['source'], None)
    index.setdefault('emptyDocuments', {})[recipe['source']] = {
        'sources': sources, 'captureDigest': capture_digest(), 'channels': 0,
        'reason': 'No output-area channels; the code remains available.',
    }
    write_catalog(index)


def sync_publications(*, mode: str = 'incremental', port: int | None = None) -> dict:
    """Refresh supported documents before building the site; an unchanged tree starts no Field."""
    available = recipes('pilot') + recipes('gallery')
    pending = pending_recipes(available, mode=mode)
    supported = {r['source'] for r in available}
    index = catalog()
    orphaned = (set(index['documents']) | set(index.get('emptyDocuments', {}))) - supported
    if pending and mode == 'never':
        raise ValueError(f"{len(pending)} publications need refresh: " + ', '.join(r['name'] for r in pending)
                         + '; run tools/docs_publications.py sync or build with FIELD_DOCS_RUNNER_MODE=incremental')
    if mode == 'never':
        if orphaned:
            raise ValueError(f'Publications for removed recipes: {sorted(orphaned)}; run tools/docs_publications.py sync')
        if any(not entry.get('files') for entry in index['documents'].values()):
            raise ValueError('Publication file integrity metadata needs an upgrade; run tools/docs_publications.py sync')
        registry()
        summary = {'captured': len(index['documents']), 'codeOnly': len(index.get('emptyDocuments', {})),
                   'refreshed': 0, 'retained': len(available)}
        print('Docs publications: ' + json.dumps(summary), flush=True)
        return summary
    report = asyncio.run(capture(pending, port=port, force=True)) if pending else []
    retry_names = {r['name'] for r in report if not r['ok'] and 'Output capture exceeded' in r.get('error', '')}
    if retry_names:
        print('Retrying timed-out captures in a fresh Field session: ' + ', '.join(sorted(retry_names)), flush=True)
        retried = asyncio.run(capture([r for r in pending if r['name'] in retry_names], port=port, force=True))
        by_name = {r['name']: {**r, 'attempts': 2} for r in retried}
        report = [by_name.get(r['name'], r) for r in report]
    report_path = ROOT / 'build/docs-publications-refresh.json'
    report_path.parent.mkdir(parents=True, exist_ok=True)
    report_path.write_text(json.dumps(report, indent=2) + '\n')
    failed = [r for r in report if not r['ok']]
    if failed:
        raise ValueError('Publication capture failed: ' + '; '.join(f"{r['name']}: {r['error']}" for r in failed))
    # Removed or reclassified recipes must not leave a misleading publication behind.
    index = catalog()
    for section in ('documents', 'emptyDocuments'):
        for source in list(index.get(section, {})):
            if source not in supported:
                del index[section][source]
    registry(index=index)
    # Upgrade existing snapshots without altering their captured data. Future merge damage is
    # detected by these hashes and repaired by replaying the affected recipe.
    for entry in index['documents'].values():
        if not entry.get('files'):
            entry['files'] = snapshot_hashes(entry)
    write_catalog(index)
    registry()
    summary = {'captured': len(index['documents']), 'codeOnly': len(index.get('emptyDocuments', {})),
               'refreshed': len(report), 'retained': len(available) - len(report)}
    print('Docs publications: ' + json.dumps(summary), flush=True)
    return summary


def registry(*, verify: bool = True, index: dict | None = None) -> dict:
    result = {"version": 1, "documents": {}}
    for source, entry in (catalog() if index is None else index)["documents"].items():
        if verify:
            for name, expected in entry["sources"].items():
                path = ROOT / name
                if not path.is_file() or digest(path) != expected:
                    raise ValueError(f"Stale publication {source}: {name}; recapture with tools/docs_publications.py")
            transcript = next((name for name in entry['sources'] if name.startswith('dev_docs/agents/transcripts/')), None)
            if source_files(source, transcript) != entry['sources']:
                raise ValueError(f"Stale publication {source}: source file set changed; run tools/docs_publications.py sync")
            for key in ("file", "outputs"):
                if not (DOCS / entry[key]).is_file():
                    raise ValueError(f"Missing publication asset: {entry[key]}")
            if problem := publication_problem(entry):
                raise ValueError(f"Invalid publication {source}: {problem}; run tools/docs_publications.py sync")
        result["documents"][source] = {k: entry[k] for k in ("file", "outputs")}
    return result


def prune_runtimes() -> int:
    """Remove content-addressed runtimes that no retained output references."""
    used = set()
    for entry in catalog()['documents'].values():
        folder = (DOCS / entry['file']).parents[1] / 'outputs'
        for output in folder.glob('*.html'):
            used.update(re.findall(r'\.\./\.\./runtime/([a-f0-9]{20}/[a-zA-Z0-9_.-]+)', output.read_text()))
    removed = 0
    runtime = PUBLICATIONS / 'runtime'
    for path in runtime.glob('*/*'):
        if path.is_file() and path.relative_to(runtime).as_posix() not in used:
            path.unlink()
            removed += 1
    for folder in runtime.glob('*'):
        if folder.is_dir() and not any(folder.iterdir()):
            folder.rmdir()
    return removed


def install(archive: Path, *, name: str, source: str, sources: dict[str, str]) -> dict:
    """Install data only; immutable runtime files are stored once across all documents."""
    if not re.fullmatch(r"[a-z0-9_-]+", name):
        raise ValueError(f"Invalid publication name: {name}")
    target = PUBLICATIONS / name
    with zipfile.ZipFile(archive) as bundle:
        manifest = json.loads(bundle.read("document/outputs.json"))
        if not manifest["outputs"] or not any(o.get("path") for o in manifest["outputs"]):
            raise ValueError("Document has no captured output channels")
        failed = [f"{o.get('uid', '?')}/{o['channel']}: {'; '.join(o.get('warnings', []))}"
                  for o in manifest["outputs"] if not o.get("path")]
        if failed:
            raise ValueError(f"Uncaptured channels: {failed}")
        replacements = {}
        for runtime in ("three-publication-runtime.js", "plotly-2.24.1.min.js", "publication-font.js"):
            if runtime not in bundle.namelist():
                continue
            content = bundle.read(runtime)
            relative = Path("runtime") / hashlib.sha256(content).hexdigest()[:20] / runtime
            shared = PUBLICATIONS / relative
            shared.parent.mkdir(parents=True, exist_ok=True)
            shared.write_bytes(content)
            replacements["../" + runtime] = "../../" + relative.as_posix()
        # Build beside the destination so a failed capture never destroys its previous publication.
        PUBLICATIONS.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(dir=PUBLICATIONS, prefix=".capture-") as temp:
            staged = Path(temp) / name
            for path in ("document/metadata.edn", "document/outputs.json", *[o["path"] for o in manifest["outputs"]]):
                if path not in {"document/metadata.edn", "document/outputs.json"} and not re.fullmatch(r"outputs/\d+\.html", path):
                    raise ValueError(f"Invalid output path: {path}")
                content = bundle.read(path)
                if path.endswith(".html"):
                    html = content.decode()
                    for before, after in replacements.items():
                        html = html.replace(before, after)
                    content = html.encode()
                out = staged / path
                out.parent.mkdir(parents=True, exist_ok=True)
                out.write_bytes(content)
            if target.exists():
                shutil.rmtree(target)
            staged.rename(target)
    entry = {
        "file": f"assets/web_publications/{name}/document/metadata.edn",
        "outputs": f"assets/web_publications/{name}/document/outputs.json",
        "capturedAt": manifest["capturedAt"], "channels": len(manifest["outputs"]),
        "kinds": sorted({kind for o in manifest["outputs"] for kind in o["kinds"]}),
        "sources": sources,
        "captureDigest": capture_digest(),
    }
    entry['files'] = snapshot_hashes(entry)
    index = catalog()
    index["documents"][source] = entry
    index.get('emptyDocuments', {}).pop(source, None)
    write_catalog(index)
    return entry


def recipes(group: str) -> list[dict]:
    if group == "pilot":
        return [{"name": name, "source": f"assets/{name}/metadata.edn"} for name in PILOTS]
    # The existing smoke harness is the authority for which transcripts may execute headless.
    text = (ROOT / "src/jvmMain/kotlin/afield/examples/DemoTranscriptExecSmokeTest.kt").read_text()
    block = text.split("internal val EXECUTE = setOf(", 1)[1].split("internal val COMPILE_ONLY", 1)[0]
    block = re.sub(r'/\*.*?\*/|//[^\n]*', '', block, flags=re.S)
    names = re.findall(r'"([a-z0-9_-]+)"', block)
    return [{"name": name, "source": f"assets/agent_transcripts/{name}/metadata.edn",
             "transcript": f"dev_docs/agents/transcripts/{name}.yaml",
             # The execution harness records ~67s for Morpho's synchronous search; both
             # benchmarks need a dedicated budget rather than the interactive watchdog.
             "longCompute": name in {'petri_tala_layout', 'morpho_circuit_search'}} for name in names
            if (DOCS / f"assets/agent_transcripts/{name}/metadata.edn").is_file()]


async def command(client, text: str) -> dict:
    import field_backdoor as fb
    result = await fb.dispatch_command(client, fb.parse_command(text), text,
                                       auto_playwright=True, auto_playwright_timeout=90)
    if not result["ok"]:
        raise RuntimeError(json.dumps(result.get("data"), ensure_ascii=False)[:3500])
    return result


async def capture(selected: list[dict], *, port: int | None, force: bool) -> list[dict]:
    import field_backdoor as fb
    from field_publication import publish_document
    report = []
    with tempfile.TemporaryDirectory(prefix="field-doc-publications-") as temp:
        workspace = Path(temp) / "workspace"
        workspace.mkdir()
        for recipe in selected:
            if not recipe.get("transcript"):
                shutil.copytree((DOCS / recipe["source"]).parent, workspace / recipe["name"])
        async def start(watchdog_ms: int = 30000):
            log = fb.SessionLogger(fb.LOG_ROOT, ["docs-publications"])
            client = fb.FieldBackdoor(port=port or fb.resolve_cli_port(None, attach_existing=False)[0],
                                     workspace=str(workspace), logger=log, default_timeout=180)
            key = 'AFIELD_RUNLOOP_WATCHDOG_INTERRUPT_MS'
            previous = os.environ.get(key)
            os.environ[key] = str(watchdog_ms)
            try:
                try:
                    await client.ensure_server()
                finally:
                    if previous is None:
                        os.environ.pop(key, None)
                    else:
                        os.environ[key] = previous
                if client.managed_process is None:
                    raise RuntimeError("Capture needs a free automation port and its own Field server")
                await client.connect()
                return client, log
            except BaseException:
                await client.close()
                log.close()
                raise

        budget = 120000 if selected and selected[0].get('longCompute') else 30000
        client, log = await start(budget)
        opened = 0
        try:
            for recipe in selected:
                name, source = recipe["name"], recipe["source"]
                sources = source_files(source, recipe.get("transcript"))
                previous = catalog()["documents"].get(source)
                if not force and previous and previous["sources"] == sources and previous.get('captureDigest') == capture_digest():
                    print(f"RETAIN {name}: {previous['channels']} channels", flush=True)
                    report.append({'name':name,'ok':True,'channels':previous['channels'],'retained':True})
                    continue
                # Bound the compiler, live DOM and WebGL state accumulated across documents.
                wanted_budget = 120000 if recipe.get('longCompute') else 30000
                if opened >= 16 or wanted_budget != budget:
                    await client.close()
                    log.close()
                    budget = wanted_budget
                    client, log = await start(budget)
                    opened = 0
                opened += 1
                try:
                    await client.open_document(name, scratch=False, timeout=180)
                    await client.open_playwright(timeout=90)
                    if transcript := recipe.get("transcript"):
                        await client.macro_transcript_command(command="write", name=name,
                            content=(ROOT / transcript).read_text(), timeout=60)
                        result = await client.macro_transcript_command(command="run", name=name, timeout=180)
                        if not result.get("success"):
                            raise RuntimeError(json.dumps(result)[:3500])
                        import yaml
                        operations = yaml.safe_load((ROOT / transcript).read_text()).get('operations', [])
                        if not any(set(op) & {'execute', 'launch', 'run', 'continue'} for op in operations):
                            # Many starting points intentionally contain only create operations.
                            # Their headless classification permits evaluating the prepared boxes.
                            for op in operations:
                                created = op.get('create', {})
                                if created.get('properties', {}).get('code', '').strip():
                                    await command(client, f"run {created['box']} --timeout 180")
                    else:
                        snapshot = await client.refresh_snapshot(60)
                        boxes = sorted((b for b in snapshot["boxes"] if b.get("code", "").strip()),
                                       key=lambda b: (b["frame"]["y"], b["frame"]["x"]))
                        for i, box in enumerate(boxes):
                            print(f"RUN {name} {i+1}/{len(boxes)}: {box['name']}", flush=True)
                            await command(client, f"run {box['uid']} --timeout 180")
                    # Render closed panels before sampling, and retain them open for the reader.
                    await client.playwright_input(action="pageEval", script_mode="body", timeout=60, script="""
                        for(const box of document.querySelectorAll('.box')){
                            const api=box.querySelector('.disclosure-group')?.__disclosureAPI;
                            for(const panel of box.querySelectorAll('.disclosure')) if(panel.__disclosureId)api?.open(panel.__disclosureId);
                        }
                        return true;
                    """)
                    await client.wait_frames(15, timeout=30)
                    # This gallery was laid out for closed outputs, only 140px between five boxes.
                    if name == "three_output_gallery":
                        snapshot = await client.refresh_snapshot(60)
                        y = 60
                        for box in sorted(snapshot["boxes"], key=lambda b: b["frame"]["y"]):
                            if not box.get("code"):
                                continue
                            await client.set_frame(box["uid"], {**box["frame"], "y": y})
                            y += {"three-output-grid": 960, "three-output-renderers": 1060,
                                  "three-output-winding": 1060}.get(box["uid"], 700)
                    path = Path(temp) / f"{name}.zip"
                    result = await publish_document(client, str(path), timeout=240, include_viewer=False)
                    if not result['capturedOutputs']:
                        record_empty(recipe, sources)
                        report.append({'name': name, 'ok': True, 'channels': 0,
                                       'reason': 'No output-area channels; the code remains available.'})
                        print(f"SKIP {name}: no output-area channels", flush=True)
                        continue
                    entry = install(path, name=name, source=source, sources=sources)
                    report.append({"name": name, "ok": True, "channels": entry["channels"], "warnings": result["warnings"]})
                    print(f"CAPTURE {name}: {entry['channels']} channels", flush=True)
                except Exception as error:
                    message = fb.exception_message(error)
                    report.append({"name": name, "ok": False, "error": message})
                    print(f"FAIL {name}: {message}", flush=True)
                    try:
                        await client.wait_frames(2, timeout=10)
                    except Exception:
                        opened = 16
                finally:
                    if recipe.get('longCompute'):
                        opened = 16
        finally:
            await client.close()
            log.close()
    return report


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("capture", "sync", "check", "list", "prune"))
    parser.add_argument('--mode', choices=('never', 'incremental', 'strict', 'always'), default='incremental')
    parser.add_argument("--group", choices=("pilot", "gallery"), default="pilot")
    parser.add_argument("--name", action="append", help="Select named recipes within the group")
    parser.add_argument("--port", type=int)
    parser.add_argument("--force", action="store_true")
    parser.add_argument("--report", type=Path, default=ROOT / "build/docs-publications-report.json")
    args = parser.parse_args()
    if args.action in ('sync', 'check'):
        sync_publications(mode='never' if args.action == 'check' else args.mode, port=args.port)
        return 0
    if args.action == 'prune':
        print(f'Removed {prune_runtimes()} unused runtime files')
        return 0
    available = recipes(args.group)
    if args.name and (unknown := set(args.name) - {r['name'] for r in available}):
        parser.error(f'Unknown {args.group} recipes: {sorted(unknown)}')
    selected = [r for r in available if not args.name or r["name"] in args.name]
    if args.action == "list":
        print(json.dumps(selected, indent=2))
        return 0
    report = asyncio.run(capture(selected, port=args.port, force=args.force))
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report, indent=2) + "\n")
    return int(any(not r["ok"] for r in report))


if __name__ == "__main__":
    from verification_runtime import verification_lock
    with verification_lock(ROOT, label='Docs publication refresh', progress=lambda message: print(message, flush=True)):
        raise SystemExit(main())
