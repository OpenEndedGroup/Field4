"""Kits: the box code, box-held sources and asset files every document of one kind receives.

A kit is a directory holding `kit.json` and the files it names (see `afield.stdlib.Kits` for the
format, and public/docs/kits.md for the user's page):

    make <kit> --document D --role KEY=BOX ...   make a kit from a document in a running Field
    rehash <kit> [--check]                       recompute kit.json's hashes and revision from its files
    stage <kit> [--check]                        stage a kit.json for what is staged in git
    activate <kit> --document D [--install]      install the kit in a new document, or relaunch one
    migrate <kit> --document D [--dry-run]       bring a document up to the kit, explicitly
    publish --document D [--name N]              publish a document as a Welcome starting point

The rule throughout: the document owns a copy, and nothing upgrades it but `migrate`. A migration
compares three hashes for every property and asset (the document's record, the document, the kit)
and refuses the document's own edits, refuses while any build runs, relaunches the roles in launch
order and records the new revision last. The commands that touch a document talk to a running Field
(`--port`, default 9092) through the agent backdoor; `InspectKitRequest`, the agent's kit tool, is
the one place the comparison is made.
"""

from __future__ import annotations

import argparse
import asyncio
import datetime
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Callable, Protocol

SCHEMA = "field.kit.v1"
RECORD = "files/kit.json"
DEFAULT_ROLE_PROPERTY = "kitRole"
# Not part of what a kit installs, so not part of its revision: the revision itself, and where a record says the kit lives.
UNHASHED = ("revision", "directory")
ACTIVE_BUILD = ("QUEUED", "RUNNING")
SAFE_NAME = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]*")


class KitError(Exception):
    """A refusal or failure, with a message for the person running the command."""


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def short(value: str | None) -> str:
    return value[:8] if value else "absent"


# ---- the manifest --------------------------------------------------------------------------------


def revision_of(manifest: dict[str, Any]) -> str:
    """The hash of everything the kit installs: the manifest without its revision and directory."""
    body = {k: v for k, v in manifest.items() if k not in UNHASHED}
    return sha256(json.dumps(body, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode())


def entries(manifest: dict[str, Any]) -> list[tuple[str, dict[str, Any] | None, str]]:
    """Every file the kit installs, as (label, the entry to update or None for an asset, path in the kit)."""
    found = []
    for role, spec in manifest.get("roles", {}).items():
        for prop, entry in spec.get("properties", {}).items():
            found.append((f"{role}.{prop}", entry, entry["file"]))
    for asset in manifest.get("assets", {}):
        found.append((asset, None, f"files/{asset}"))
    return found


def validate(manifest: dict[str, Any]) -> None:
    if manifest.get("schema") != SCHEMA:
        raise KitError(f"Not a kit manifest (schema {manifest.get('schema')!r}, expected {SCHEMA})")
    if not isinstance(manifest.get("name"), str) or not SAFE_NAME.fullmatch(manifest["name"]):
        raise KitError("kit.json needs a name of letters, digits, '.', '_' and '-'")
    roles = manifest.get("roles", {})
    for role in manifest.get("launchOrder", []):
        if role not in roles:
            raise KitError(f"launchOrder names {role!r}, which is not a role")
    for role, spec in roles.items():
        if "code" not in spec.get("properties", {}):
            raise KitError(f"role {role!r} installs no code")
    for label, _, path in entries(manifest):
        parts = Path(path).parts
        if Path(path).is_absolute() or ".." in parts or not parts:
            raise KitError(f"{label}: {path!r} must be a path inside the kit")
    if "kit.json" in manifest.get("assets", {}):
        raise KitError("an asset cannot be named kit.json: that is where a document records its kit")


def load(kit: Path) -> dict[str, Any]:
    path = kit / "kit.json"
    if not path.is_file():
        raise KitError(f"No kit.json in {kit}")
    try:
        manifest = json.loads(path.read_text())
    except json.JSONDecodeError as e:
        raise KitError(f"{path} is not valid JSON: {e}") from e
    validate(manifest)
    return manifest


def write_manifest(path: Path, manifest: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(manifest_text(manifest))


def manifest_text(manifest: dict[str, Any]) -> str:
    return json.dumps(manifest, indent=2, ensure_ascii=False) + "\n"


def rehash_manifest(manifest: dict[str, Any], read: Callable[[str], bytes]) -> list[str]:
    """Update every hash from [read] (a path in the kit to its bytes) and the revision; returns what changed."""
    changed = []
    for label, entry, path in entries(manifest):
        value = sha256(read(path))
        if entry is None:
            if manifest["assets"][label] != value:
                manifest["assets"][label] = value
                changed.append(label)
        elif entry.get("sha256") != value:
            entry["sha256"] = value
            changed.append(label)
    manifest["revision"] = revision_of(manifest)
    return changed


def stale_entries(kit: Path, manifest: dict[str, Any]) -> list[str]:
    """Entries whose file in [kit] no longer has the recorded hash, and a revision that is not the hash of the rest."""
    stale = []
    for label, entry, path in entries(manifest):
        file = kit / path
        recorded = manifest["assets"][label] if entry is None else entry["sha256"]
        if not file.is_file() or sha256(file.read_bytes()) != recorded:
            stale.append(label)
    if manifest.get("revision") != revision_of(manifest):
        stale.append("revision")
    return stale


def rehash(kit: Path, check: bool) -> int:
    manifest = load(kit)
    old = manifest.get("revision")
    try:
        changed = rehash_manifest(manifest, lambda path: (kit / path).read_bytes())
    except FileNotFoundError as e:
        raise KitError(f"kit.json names a file that is not in the kit: {e.filename}") from e
    if manifest["revision"] == old:
        print(f"kit {manifest['name']} {short(old)} is current")
        return 0
    print("changed:", ", ".join(changed) or "the manifest itself")
    print(f"revision {short(old)} -> {short(manifest['revision'])}")
    if check:
        return 1
    write_manifest(kit / "kit.json", manifest)
    return 0


def git(cwd: Path, *args: str, data: bytes | None = None) -> bytes:
    run = subprocess.run(["git", *args], cwd=cwd, input=data, capture_output=True)
    if run.returncode:
        raise KitError(f"git {' '.join(args)}: {run.stderr.decode(errors='replace').strip()}")
    return run.stdout


def stage(kit: Path, check: bool) -> int:
    """Stage kit.json rehashed from the index: the manifest the commit will hold, whatever the working tree has.

    Starts from the staged kit.json (stage a new entry first), rehashes each entry from the staged bytes, and
    writes the result to the index only. Uncommitted kit work in the working tree stays out of the commit;
    `rehash` afterwards brings the working tree's kit.json up to date with everything there.
    """
    kit = kit.resolve()
    top = Path(git(kit, "rev-parse", "--show-toplevel").decode().strip())
    rel = kit.relative_to(top.resolve())

    def staged(path: str) -> bytes:
        spec = (rel / path).as_posix()
        if not git(top, "ls-files", "--cached", "--", spec).strip():
            raise KitError(f"{spec} is not staged; git add it, or remove its entry from kit.json")
        return git(top, "show", f":{spec}")

    manifest = json.loads(staged("kit.json"))
    validate(manifest)
    old = manifest.get("revision")
    changed = rehash_manifest(manifest, staged)
    print("changed:", ", ".join(changed) or "nothing")
    print(f"revision {short(old)} -> {short(manifest['revision'])}")
    if check or manifest["revision"] == old:
        return 1 if check and manifest["revision"] != old else 0
    blob = git(top, "hash-object", "-w", "--stdin", data=manifest_text(manifest).encode()).decode().strip()
    git(top, "update-index", "--cacheinfo", f"100644,{blob},{(rel / 'kit.json').as_posix()}")
    return 0


# ---- the host: a running Field ------------------------------------------------------------------


class Host(Protocol):
    """What the document commands need from Field. [LiveHost] is the real one; the tests supply a fake."""

    document_dir: Path

    async def inspect(self, kit: Path | None) -> dict[str, Any]: ...
    async def running_builds(self) -> list[str]: ...
    async def boxes(self) -> list[dict[str, Any]]: ...
    async def get(self, box: str, prop: str) -> str | None: ...
    async def set(self, box: str, prop: str, value: str) -> None: ...
    async def new_box(self, name: str) -> str: ...
    async def launch(self, box: str) -> tuple[bool, str]: ...
    async def save(self) -> None: ...


class LiveHost:
    """A document open in a running Field, reached through the agent backdoor."""

    def __init__(self, port: int, document: str, create: bool = False) -> None:
        from field_python_env import ensure_repo_python

        ensure_repo_python()
        import field_backdoor as fb

        self.fb = fb
        self.port = port
        self.document = document
        self.create = create
        self.client = fb.FieldBackdoor(port=port, workspace=None, default_timeout=180,
                                       logger=fb.SessionLogger(fb.LOG_ROOT, ["field_kit", document]), attach_existing=True)
        self.document_dir = Path()

    async def __aenter__(self) -> "LiveHost":
        await self.client.ensure_server()
        await self.client.connect()
        await self.client.open_document(self.document, scratch=False, must_exist=not self.create, keep_running=True, timeout=120)
        self.document_dir = Path(self.client.server_workspace or "") / self.document
        return self

    async def __aexit__(self, *exc: Any) -> None:
        await self.client.close()

    async def call(self, tool: str, timeout: float = 180, **arguments: Any) -> dict[str, Any]:
        reply = await self.client.ai_tool_call(name=tool, arguments=arguments, timeout=timeout)
        if not reply.get("ok"):
            raise KitError(f"{tool} failed: {reply.get('error') or reply}")
        return reply["result"]

    async def inspect(self, kit: Path | None) -> dict[str, Any]:
        result = await self.call("InspectKitRequest", kit=str(kit.resolve()) if kit else "")
        if not result.get("success"):
            raise KitError(result.get("message", "InspectKitRequest failed"))
        return {"message": result["message"], **result["details"]}

    async def running_builds(self) -> list[str]:
        result = await self.call("ListStudyBuildsRequest")
        return [f"{runner['name']} · {stage['label']} ({stage['latest']['status'].lower()})"
                for runner in result.get("details", {}).get("runners", []) for stage in runner.get("stages", [])
                if isinstance(stage.get("latest"), dict) and stage["latest"].get("status") in ACTIVE_BUILD]

    async def boxes(self) -> list[dict[str, Any]]:
        return (await self.call("ListBoxesRequest")).get("boxes", [])

    async def get(self, box: str, prop: str) -> str | None:
        result = await self.call("GetBoxPropertyRequest", box=box, property=prop)
        return result.get("value") if result.get("success") else None

    async def set(self, box: str, prop: str, value: str) -> None:
        result = await self.call("SetBoxPropertyRequest", box=box, property=prop, value=value)
        if not result.get("success"):
            raise KitError(f"Could not set {prop} on {box}: {result.get('message')}")

    async def new_box(self, name: str) -> str:
        result = await self.call("NewBoxRequest", name=name)
        if not result.get("success"):
            raise KitError(f"Could not make a box named {name}: {result.get('message')}")
        return result["uid"]

    async def launch(self, box: str) -> tuple[bool, str]:
        result = await self.call("LaunchBoxRequest", box=box, timeoutSeconds=150, settleSeconds=0.5)
        errors = "; ".join(result.get("errors") or []) or result.get("error") or result.get("message", "")
        return bool(result.get("success")), errors

    async def save(self) -> None:
        await self.client.save_document()


# ---- what the commands do ------------------------------------------------------------------------


@dataclass
class Plan:
    """What a migration or installation would do, from an inspection."""

    install: list[dict[str, Any]] = field(default_factory=list)
    frozen: list[dict[str, Any]] = field(default_factory=list)
    removed: list[dict[str, Any]] = field(default_factory=list)
    refusals: list[str] = field(default_factory=list)


def plan_from(inspection: dict[str, Any], running: list[str]) -> Plan:
    by_state: dict[str, list[dict[str, Any]]] = {}
    for item in inspection.get("differences", []):
        by_state.setdefault(item["state"], []).append(item)
    refusals = list(inspection.get("migrationRefusals", []))
    # Relaunching providers would cancel or orphan a build still running, and its result would describe the old kit.
    if running:
        refusals.append("a build is running (" + ", ".join(running) + "); migrate once it finishes")
    return Plan(by_state.get("kit_changed", []), by_state.get("frozen_stale", []), by_state.get("removed_from_kit", []), refusals)


def describe(plan: Plan) -> list[str]:
    lines = []
    for title, items, verb in [("install", plan.install, "from the kit"), ("frozen copies", plan.frozen, "replaced with the kit's"),
                               ("no longer in the kit", plan.removed, "left as they are")]:
        if items:
            lines.append(f"  {title}: " + ", ".join(i["item"] for i in items) + f" ({verb})")
    return lines


def kit_file(kit: Path, manifest: dict[str, Any], item: dict[str, Any]) -> Path:
    if item.get("asset"):
        return kit / "files" / item["asset"]
    return kit / manifest["roles"][item["role"]]["properties"][item["property"]]["file"]


async def install(host: Host, kit: Path, manifest: dict[str, Any], inspection: dict[str, Any], items: list[dict[str, Any]]) -> None:
    """Install [items] from the kit: assets into files/, properties onto each role's box (made if the role has none)."""
    role_property = manifest.get("roleProperty", DEFAULT_ROLE_PROPERTY)
    boxes = {role: info.get("box") for role, info in inspection.get("roles", {}).items()}
    for item in items:
        source = kit_file(kit, manifest, item)
        if item.get("asset"):
            target = host.document_dir / "files" / item["asset"]
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(source, target)
            continue
        role = item["role"]
        if not boxes.get(role):
            boxes[role] = await host.new_box(manifest["roles"][role].get("name", role))
            await host.set(boxes[role], role_property, role)
        await host.set(boxes[role], item["property"], source.read_text())


async def launch_in_order(host: Host, manifest: dict[str, Any], inspection: dict[str, Any]) -> None:
    roles = inspection.get("roles", {})
    for role in manifest.get("launchOrder", []):
        box = roles.get(role, {}).get("box")
        if not box:
            raise KitError(f"No box plays role {role!r}")
        ok, message = await host.launch(box)
        if not ok:
            raise KitError(f"Launching {role} ({roles[role].get('name') or box}) failed: {message}")
        print(f"  launched {role}")


def record(host: Host, kit: Path, manifest: dict[str, Any]) -> None:
    write_manifest(host.document_dir / RECORD, {**manifest, "directory": str(kit.resolve())})


def require_consistent(kit: Path, manifest: dict[str, Any]) -> None:
    stale = stale_entries(kit, manifest)
    if stale:
        raise KitError(f"kit.json does not match the kit's files ({', '.join(stale)}); run rehash first")


async def settled(host: Host, kit: Path, what: str) -> dict[str, Any]:
    """Inspect again after installing: everything the kit holds must now be in the document."""
    after = await host.inspect(kit)
    left = [i["item"] for i in after.get("differences", []) if i["state"] != "removed_from_kit"]
    if left or after.get("problems"):
        raise KitError(f"After {what}, the document still differs from the kit: " + ", ".join(left + after.get("problems", [])))
    return after


async def migrate(host: Host, kit: Path, dry_run: bool = False) -> int:
    manifest = load(kit)
    require_consistent(kit, manifest)
    inspection = await host.inspect(kit)
    if not inspection.get("recordedRevision"):
        raise KitError("This document is not on a kit yet; install one with activate --install")
    old, new = inspection["recordedRevision"], manifest["revision"]
    plan = plan_from(inspection, await host.running_builds())
    if plan.refusals:
        print(f"Refusing to migrate {host.document_dir.name}:")
        for refusal in plan.refusals:
            print(f"  {refusal}")
        print("Nothing was changed.")
        return 2
    if old == new and not (plan.install or plan.frozen):
        print(f"{host.document_dir.name} is already on kit {manifest['name']} {short(new)}")
        return 0
    print(f"{host.document_dir.name}: kit {manifest['name']} {short(old)} -> {short(new)}")
    for line in describe(plan) or ["  nothing to install; relaunching and recording the new revision"]:
        print(line)
    if dry_run:
        return 0
    await install(host, kit, manifest, inspection, plan.install + plan.frozen)
    after = await settled(host, kit, "installing")
    running = await host.running_builds()
    if running:
        raise KitError("a build started during the migration (" + ", ".join(running) + "); the record still says " +
                       f"{short(old)}. Migrate again once it finishes")
    try:
        await launch_in_order(host, manifest, after)
    except KitError as e:
        raise KitError(f"{e}. The record still says {short(old)}; fix the role and migrate again") from e
    record(host, kit, manifest)
    await host.save()
    print(f"  recorded kit {manifest['name']} {short(new)}")
    return 0


async def activate(host: Host, kit: Path, install_kit: bool = False) -> int:
    manifest = load(kit)
    require_consistent(kit, manifest)
    inspection = await host.inspect(kit)
    recorded = inspection.get("recordedRevision")
    plan = plan_from(inspection, await host.running_builds())
    if install_kit:
        if recorded:
            raise KitError(f"This document is already on kit {inspection.get('kit')} {short(recorded)}; use migrate to change it")
        if plan.refusals:
            print(f"Refusing to install kit {manifest['name']} in {host.document_dir.name}:")
            for refusal in plan.refusals:
                print(f"  {refusal}")
            return 2
        print(f"{host.document_dir.name}: installing kit {manifest['name']} {short(manifest['revision'])}")
        await install(host, kit, manifest, inspection, plan.install + plan.frozen)
        inspection = await settled(host, kit, "installing")
    else:
        if not recorded:
            raise KitError("This document is not on a kit; pass --install to install this one")
        if recorded != manifest["revision"] or inspection.get("differences") or inspection.get("problems"):
            raise KitError(f"{inspection['message']} Migrate it before activating")
        print(f"{host.document_dir.name}: running kit {manifest['name']} {short(recorded)} in launch order")
    await launch_in_order(host, manifest, inspection)
    if install_kit:
        record(host, kit, manifest)
        print(f"  recorded kit {manifest['name']} {short(manifest['revision'])}")
    await host.save()
    return 0


def plural(n: int, one: str, many: str) -> str:
    return f"{n} {one if n == 1 else many}"


def parse_reference(text: str, what: str) -> tuple[str, str]:
    role, dot, prop = text.partition(".")
    if not dot or not role or not prop:
        raise KitError(f"{what} {text!r} should be ROLE.PROPERTY")
    return role, prop


async def make(host: Host, kit: Path, name: str, roles: list[str], sources: list[str], frozen: list[str],
               assets: list[str], role_property: str = DEFAULT_ROLE_PROPERTY) -> int:
    """Make a kit at [kit] from the document: mark each role's box, copy its code and sources, and record the kit."""
    if kit.exists() and any(kit.iterdir()):
        raise KitError(f"{kit} already holds files; make a kit in a new directory")
    if not roles:
        raise KitError("Name at least one role, as --role KEY=BOX")
    inspection = await host.inspect(None)
    if inspection.get("recordedRevision"):
        raise KitError(f"This document is already on kit {inspection.get('kit')}; change that kit instead")
    by_name: dict[str, list[str]] = {}
    for box in await host.boxes():
        by_name.setdefault(box["name"], []).append(box["uid"])
    manifest: dict[str, Any] = {"schema": SCHEMA, "name": name, "roleProperty": role_property, "launchOrder": [], "roles": {}, "assets": {}}
    texts: dict[str, str] = {}
    boxes: dict[str, str] = {}
    for spec in roles:
        role, eq, box_name = spec.partition("=")
        if not eq or not SAFE_NAME.fullmatch(role):
            raise KitError(f"--role {spec!r} should be KEY=BOX, with a key of letters, digits, '.', '_' and '-'")
        found = by_name.get(box_name, [])
        if len(found) != 1:
            raise KitError(f"{'No' if not found else len(found)} boxes named {box_name!r}; name the role's box uniquely")
        boxes[role] = found[0]
        manifest["launchOrder"].append(role)
        manifest["roles"][role] = {"name": box_name, "properties": {}}
        code = await host.get(found[0], "code")
        if not code:
            raise KitError(f"{box_name} has no code")
        manifest["roles"][role]["properties"]["code"] = {"file": f"roles/{role}/code.kt", "sha256": ""}
        texts[f"roles/{role}/code.kt"] = code
    for spec in sources:
        ref, _, extension = spec.partition(":")
        role, prop = parse_reference(ref, "--source")
        if role not in boxes:
            raise KitError(f"--source {spec!r} names role {role!r}, which no --role gives")
        value = await host.get(boxes[role], prop)
        if value is None:
            raise KitError(f"{manifest['roles'][role]['name']} has no text property {prop!r}")
        path = f"roles/{role}/{prop}.{extension or 'txt'}"
        manifest["roles"][role]["properties"][prop] = {"file": path, "sha256": ""}
        texts[path] = value
    for spec in frozen:
        ref, eq, original = spec.partition("=")
        role, prop = parse_reference(ref, "--frozen")
        if role not in boxes:
            raise KitError(f"--frozen {spec!r} names role {role!r}, which no --role gives")
        value = await host.get(boxes[role], prop)
        if value is None:
            raise KitError(f"{manifest['roles'][role]['name']} has no text property {prop!r}")
        if eq:
            of_role, of_prop = parse_reference(original, "--frozen copy of")
            source = manifest["roles"].get(of_role, {}).get("properties", {}).get(of_prop)
            if source is None:
                raise KitError(f"--frozen {spec!r}: {original} is not a --source")
            if texts[source["file"]] != value:
                raise KitError(f"{ref} differs from {original}, so one file cannot hold both; make {ref} its own frozen copy")
            path = source["file"]
        else:
            path = f"roles/{role}/{prop}.txt"
            texts[path] = value
        manifest["roles"][role]["properties"][prop] = {"file": path, "sha256": "", "frozen": True}
    for asset in assets:
        source = host.document_dir / "files" / asset
        if not source.is_file():
            raise KitError(f"No file {asset} in the document's files/")
        manifest["assets"][asset] = ""
    validate(manifest)
    kit.mkdir(parents=True, exist_ok=True)
    for path, text in texts.items():
        (kit / path).parent.mkdir(parents=True, exist_ok=True)
        (kit / path).write_text(text)
    for asset in assets:
        (kit / "files" / asset).parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(host.document_dir / "files" / asset, kit / "files" / asset)
    rehash_manifest(manifest, lambda path: (kit / path).read_bytes())
    write_manifest(kit / "kit.json", manifest)
    for role, box in boxes.items():
        await host.set(box, role_property, role)
    record(host, kit, manifest)
    after = await host.inspect(kit)
    if after.get("differences") or after.get("problems"):
        raise KitError("The document does not match the kit just made from it: " + after["message"])
    await host.save()
    print(f"made kit {name} {short(manifest['revision'])} in {kit}: " +
          ", ".join(f"{role} ({plural(len(spec['properties']), 'property', 'properties')})" for role, spec in manifest["roles"].items()) +
          (f"; {plural(len(assets), 'asset', 'assets')}" if assets else ""))
    print(f"  {host.document_dir.name} is on it")
    return 0


def default_templates() -> Path:
    return Path(os.environ.get("AFIELD_PERSONAL_TEMPLATES_DIR") or Path.home() / ".afield/templates")


SIDECAR = re.compile(r".+_.+_\.[A-Za-z0-9]+")


async def publish(host: Host, name: str, into: Path, replace: bool = False) -> int:
    """Publish the document as a Welcome starting point named [name] in [into], a personal templates root."""
    if not SAFE_NAME.fullmatch(name):
        raise KitError(f"A starting point name is letters, digits, '.', '_' and '-', not {name!r}")
    inspection = await host.inspect(None)
    if not inspection.get("recordedRevision"):
        raise KitError("This document is not on a kit; make or install one first")
    if inspection.get("differences") or inspection.get("problems"):
        raise KitError(f"{inspection['message']} Publish a document that matches its kit")
    running = await host.running_builds()
    if running:
        raise KitError("a build is running (" + ", ".join(running) + "); publish once it finishes")
    target = into / name
    if target.exists() and not replace:
        raise KitError(f"{target} exists; pass --replace to replace it (the old one is kept under .replaced/)")
    await host.save()
    source = host.document_dir
    manifest = json.loads((source / RECORD).read_text())
    into.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix=f".{name}-", dir=into) as temp:
        staged = Path(temp) / name
        staged.mkdir()
        # The document as Field saved it, the kit's assets and its record: no backups, history, chats or build attempts.
        for path in source.iterdir():
            if path.is_file() and (path.name in ("metadata.edn", "AGENTS.md", "README.md") or SIDECAR.fullmatch(path.name)):
                if "_ai4_" not in path.name:
                    shutil.copyfile(path, staged / path.name)
        for asset in [*manifest.get("assets", {}), "kit.json"]:
            (staged / "files" / asset).parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(source / "files" / asset, staged / "files" / asset)
        if not (staged / "metadata.edn").is_file():
            raise KitError("Field has not saved this document's metadata.edn")
        if target.exists():
            kept = into / ".replaced" / f"{name}-{datetime.datetime.now():%Y%m%d-%H%M%S}"
            kept.parent.mkdir(parents=True, exist_ok=True)
            target.rename(kept)
            print(f"  kept the previous {name} as {kept}")
        staged.rename(target)
    print(f"published {source.name} as starting point {name} in {into} (kit {manifest['name']} {short(manifest['revision'])})")
    return 0


# ---- the command line ----------------------------------------------------------------------------


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0], formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command", required=True)

    def with_document(p: argparse.ArgumentParser) -> argparse.ArgumentParser:
        p.add_argument("--document", required=True, help="the document, by name in the running Field's workspace")
        p.add_argument("--port", type=int, default=int(os.environ.get("PORT") or 9092), help="the running Field's port")
        return p

    for name in ("rehash", "stage"):
        p = sub.add_parser(name)
        p.add_argument("kit", type=Path)
        p.add_argument("--check", action="store_true", help="change nothing; exit 1 if anything would change")
    p = with_document(sub.add_parser("make"))
    p.add_argument("kit", type=Path)
    p.add_argument("--name", help="the kit's name (default: the kit directory's name)")
    p.add_argument("--role", action="append", default=[], help="KEY=BOX: the box named BOX plays role KEY; repeat in launch order")
    p.add_argument("--source", action="append", default=[], help="ROLE.PROPERTY[:EXT]: a box-held source the kit installs")
    p.add_argument("--frozen", action="append", default=[], help="ROLE.PROPERTY[=ROLE.SOURCE]: a frozen copy, of a source or its own")
    p.add_argument("--asset", action="append", default=[], help="a file in the document's files/ folder the kit installs")
    p.add_argument("--role-property", default=DEFAULT_ROLE_PROPERTY)
    p = with_document(sub.add_parser("activate"))
    p.add_argument("kit", type=Path)
    p.add_argument("--install", action="store_true", help="install the kit in a document not on one (creating it if needed)")
    p = with_document(sub.add_parser("migrate"))
    p.add_argument("kit", type=Path)
    p.add_argument("--dry-run", action="store_true", help="say what would happen and change nothing")
    p = with_document(sub.add_parser("publish"))
    p.add_argument("--name", help="the starting point's name (default: the kit's)")
    p.add_argument("--into", type=Path, default=None, help="the templates root (default ~/.afield/templates)")
    p.add_argument("--replace", action="store_true")
    a = parser.parse_args(argv)
    try:
        if a.command == "rehash":
            return rehash(a.kit, a.check)
        if a.command == "stage":
            return stage(a.kit, a.check)
        return asyncio.run(run_with_host(a))
    except KitError as e:
        print(f"field_kit {a.command}: {e}", file=sys.stderr)
        return 2


async def run_with_host(a: argparse.Namespace) -> int:
    async with LiveHost(a.port, a.document, create=a.command == "activate" and a.install) as host:
        if a.command == "make":
            return await make(host, a.kit, a.name or a.kit.resolve().name, a.role, a.source, a.frozen, a.asset, a.role_property)
        if a.command == "activate":
            return await activate(host, a.kit, a.install)
        if a.command == "migrate":
            return await migrate(host, a.kit, a.dry_run)
        record_name = json.loads((host.document_dir / RECORD).read_text())["name"] if (host.document_dir / RECORD).is_file() else None
        return await publish(host, a.name or record_name or a.document, a.into or default_templates(), a.replace)


if __name__ == "__main__":
    sys.path.insert(0, str(Path(__file__).resolve().parent))
    sys.exit(main())
