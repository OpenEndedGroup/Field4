"""tools/field_kit.py: the manifest commands on real files and git, the document commands against a fake Field.

The fake keeps a document as boxes, properties and files, and inspects it with the same three-way rule
`afield.document.workspace.Kits.inspect` applies (KitsTest pins that rule on the JVM side). What these tests pin is
what the commands do with an inspection: what they refuse, what they write, and in which order.
"""

from __future__ import annotations

import asyncio
import contextlib
import hashlib
import io
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import field_kit as fk  # noqa: E402


def sha(text: str) -> str:
    return hashlib.sha256(text.encode()).hexdigest()


class FakeField:
    """One open document: boxes with string properties, a files/ folder on disk, and a log of what was done."""

    def __init__(self, root: Path) -> None:
        self.document_dir = root / "doc"
        (self.document_dir / "files").mkdir(parents=True)
        self.table: dict[str, dict[str, str]] = {}
        self.log: list[str] = []
        self.builds: list[str] = []
        self.fail_launch: str | None = None

    def add(self, name: str, **props: str) -> str:
        uid = f"b{len(self.table)}"
        self.table[uid] = {"name": name, **props}
        return uid

    def manifest(self, path: Path) -> dict | None:
        return json.loads(path.read_text()) if path.is_file() else None

    async def inspect(self, kit: Path | None) -> dict:
        record = self.manifest(self.document_dir / fk.RECORD)
        kit_dir = kit or (Path(record["directory"]) if record else None)
        current = self.manifest(kit_dir / "kit.json") if kit_dir else None
        if record is None and current is None:
            return {"message": "This document is not on a kit.", "kit": "", "recordedRevision": None, "differences": [],
                    "problems": ["This document is not on a kit"], "migrationRefusals": [], "roles": {}}
        manifest = current or record
        role_property = manifest.get("roleProperty", fk.DEFAULT_ROLE_PROPERTY)
        claimed: dict[str, list[str]] = {}
        for uid, props in self.table.items():
            if role_property in props:
                claimed.setdefault(props[role_property], []).append(uid)
        problems = [f"{len(v)} boxes play role '{k}'" for k, v in claimed.items() if len(v) > 1]
        items, roles = [], {}
        names = list(dict.fromkeys([*(record or {}).get("roles", {}), *(current or {}).get("roles", {})]))
        for role in names:
            box = claimed.get(role, [None])[0] if len(claimed.get(role, [])) <= 1 else None
            roles[role] = {"box": box, "name": self.table[box]["name"] if box else None}
            old = (record or {}).get("roles", {}).get(role, {}).get("properties", {})
            new = (current or {}).get("roles", {}).get(role, {}).get("properties", {}) if current else None
            for prop in dict.fromkeys([*old, *(new or {})]):
                text = self.table[box].get(prop) if box else None
                frozen = ((new or old).get(prop) or old.get(prop) or {}).get("frozen", False)
                items.append(self.compare(f"{role} · {prop}", role, prop, None, box, old.get(prop, {}).get("sha256"),
                                          sha(text) if text is not None else None,
                                          (new or {}).get(prop, {}).get("sha256"), frozen, current is not None,
                                          new is not None and prop in new))
        for asset in dict.fromkeys([*(record or {}).get("assets", {}), *(current or {}).get("assets", {})]):
            file = self.document_dir / "files" / asset
            items.append(self.compare(asset, None, None, asset, None, (record or {}).get("assets", {}).get(asset),
                                      hashlib.sha256(file.read_bytes()).hexdigest() if file.is_file() else None,
                                      (current or {}).get("assets", {}).get(asset), False, current is not None,
                                      asset in (current or {}).get("assets", {})))
        differences = [i for i in items if i["state"] != "current"]
        refusals = problems + [f"{i['item']} was edited in this document" for i in differences if i["state"] == "document_edited"]
        return {"message": f"{len(differences)} differ.", "kit": manifest["name"], "recordedRevision": (record or {}).get("revision"),
                "kitRevision": (current or {}).get("revision"), "differences": differences, "problems": problems,
                "migrationRefusals": refusals, "roles": roles, "launchOrder": manifest.get("launchOrder", [])}

    @staticmethod
    def compare(item, role, prop, asset, box, recorded, document, kit, frozen, kit_known, in_kit) -> dict:
        reference = kit if kit_known else recorded
        if kit_known and not in_kit:
            state = "removed_from_kit"
        elif document == reference:
            state = "current"
        elif frozen:
            state = "frozen_stale"
        elif kit_known and document == recorded:
            state = "kit_changed"
        else:
            state = "document_edited"
        return {"item": item, "role": role, "property": prop, "asset": asset, "box": box, "state": state}

    async def running_builds(self) -> list[str]:
        return list(self.builds)

    async def boxes(self) -> list[dict]:
        return [{"uid": uid, "name": props["name"]} for uid, props in self.table.items()]

    async def get(self, box: str, prop: str) -> str | None:
        return self.table[box].get(prop)

    async def set(self, box: str, prop: str, value: str) -> None:
        self.log.append(f"set {self.table[box]['name']}.{prop}")
        self.table[box][prop] = value

    async def new_box(self, name: str) -> str:
        self.log.append(f"new {name}")
        return self.add(name)

    async def launch(self, box: str) -> tuple[bool, str]:
        name = self.table[box]["name"]
        self.log.append(f"launch {name}")
        if name == self.fail_launch:
            return False, "Unresolved reference: oops"
        return True, ""

    async def save(self) -> None:
        self.log.append("save")


def quietly(coroutine):
    out = io.StringIO()
    with contextlib.redirect_stdout(out):
        code = asyncio.run(coroutine)
    return code, out.getvalue()


def quiet(command, *args, **kwargs):
    """A synchronous command (rehash, stage) with its output captured."""
    out = io.StringIO()
    with contextlib.redirect_stdout(out):
        code = command(*args, **kwargs)
    return code, out.getvalue()


class KitTestCase(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = Path(tempfile.mkdtemp(prefix="field-kit-test-"))
        self.addCleanup(lambda: __import__("shutil").rmtree(self.temp, ignore_errors=True))
        self.field = FakeField(self.temp)
        self.kit = self.temp / "rooms"

    def made(self) -> dict:
        """A document with a Rules box (code, a source and a frozen audit copy of it) and a Plan box, made into a kit."""
        f = self.field
        f.add("Rules", code="val rules = 1\n", roomRules='{"rooms": 3}\n', rulesAudit='{"rooms": 3}\n')
        f.add("Plan", code="println(rules)\n")
        (f.document_dir / "files" / "palette.json").write_text('["red"]\n')
        code, out = quietly(fk.make(f, self.kit, "rooms", ["rules=Rules", "plan=Plan"], ["rules.roomRules:json"],
                                    ["rules.rulesAudit=rules.roomRules"], ["palette.json"]))
        self.assertEqual(code, 0, out)
        f.log.clear()
        return fk.load(self.kit)

    def edit_kit(self, path: str, text: str) -> str:
        (self.kit / path).write_text(text)
        self.assertEqual(quiet(fk.rehash, self.kit, check=False)[0], 0)
        return fk.load(self.kit)["revision"]

    def record(self) -> dict:
        return json.loads((self.field.document_dir / fk.RECORD).read_text())


class ManifestTest(KitTestCase):
    def test_make_writes_the_kit_marks_the_roles_and_records_the_document_on_it(self) -> None:
        manifest = self.made()
        self.assertEqual(manifest["launchOrder"], ["rules", "plan"])
        rules = manifest["roles"]["rules"]["properties"]
        self.assertEqual(rules["roomRules"]["file"], "roles/rules/roomRules.json")
        self.assertEqual(rules["rulesAudit"], {**rules["roomRules"], "frozen": True})
        self.assertEqual(rules["code"]["sha256"], sha("val rules = 1\n"))
        self.assertEqual(manifest["revision"], fk.revision_of(manifest))
        self.assertEqual(self.field.table["b0"]["kitRole"], "rules")
        self.assertEqual(self.record()["revision"], manifest["revision"])
        self.assertEqual(self.record()["directory"], str(self.kit.resolve()))
        self.assertEqual(asyncio.run(self.field.inspect(self.kit))["differences"], [])

    def test_the_revision_is_the_hash_of_everything_but_itself_and_the_directory(self) -> None:
        manifest = self.made()
        self.assertEqual(fk.revision_of({**manifest, "directory": "/elsewhere"}), manifest["revision"])
        self.assertNotEqual(fk.revision_of({**manifest, "launchOrder": ["plan", "rules"]}), manifest["revision"])

    def test_rehash_follows_the_files_and_check_changes_nothing(self) -> None:
        manifest = self.made()
        (self.kit / "roles/plan/code.kt").write_text("println(rules * 2)\n")
        code, out = quiet(fk.rehash, self.kit, check=True)
        self.assertEqual(code, 1)
        self.assertIn("plan.code", out)
        self.assertEqual(fk.load(self.kit)["revision"], manifest["revision"])
        quiet(fk.rehash, self.kit, check=False)
        self.assertNotEqual(fk.load(self.kit)["revision"], manifest["revision"])
        self.assertEqual(fk.stale_entries(self.kit, fk.load(self.kit)), [])

    def test_a_manifest_naming_files_outside_the_kit_or_unknown_roles_is_refused(self) -> None:
        manifest = self.made()
        for broken in [{**manifest, "launchOrder": ["walls"]},
                       {**manifest, "assets": {"../escape.txt": ""}},
                       {**manifest, "assets": {"kit.json": ""}},
                       {**manifest, "schema": "field.kit.v0"}]:
            with self.assertRaises(fk.KitError):
                fk.validate(broken)

    def test_stage_hashes_what_is_staged_and_leaves_the_working_tree_alone(self) -> None:
        manifest = self.made()
        git = lambda *a: subprocess.run(["git", *a], cwd=self.kit, check=True, capture_output=True)  # noqa: E731
        git("init", "-q")
        git("-c", "user.email=t@t", "-c", "user.name=t", "add", ".")
        git("-c", "user.email=t@t", "-c", "user.name=t", "commit", "-qm", "kit")
        (self.kit / "roles/plan/code.kt").write_text("println(rules * 2)\n")
        git("add", "roles/plan/code.kt")
        (self.kit / "roles/rules/code.kt").write_text("val rules = 99 // not staged\n")
        code, _ = quiet(fk.stage, self.kit, check=False)
        self.assertEqual(code, 0)
        staged = json.loads(subprocess.run(["git", "show", ":kit.json"], cwd=self.kit, capture_output=True, check=True).stdout)
        self.assertEqual(staged["roles"]["plan"]["properties"]["code"]["sha256"], sha("println(rules * 2)\n"))
        self.assertEqual(staged["roles"]["rules"]["properties"]["code"]["sha256"], sha("val rules = 1\n"))
        self.assertEqual(staged["revision"], fk.revision_of(staged))
        self.assertEqual(fk.load(self.kit)["revision"], manifest["revision"], "the working tree's kit.json is untouched")


class MigrateTest(KitTestCase):
    def test_a_kit_change_is_installed_relaunched_in_role_order_and_recorded_last(self) -> None:
        old = self.made()["revision"]
        new = self.edit_kit("roles/rules/code.kt", "val rules = 2\n")
        self.assertEqual(self.record()["revision"], old, "a newer kit changes nothing in the document by itself")
        self.assertEqual(self.field.table["b0"]["code"], "val rules = 1\n")
        code, out = quietly(fk.migrate(self.field, self.kit))
        self.assertEqual(code, 0, out)
        self.assertEqual(self.field.log, ["set Rules.code", "launch Rules", "launch Plan", "save"])
        self.assertEqual(self.field.table["b0"]["code"], "val rules = 2\n")
        self.assertEqual(self.record()["revision"], new)

    def test_the_documents_own_edit_is_refused_and_nothing_is_written(self) -> None:
        old = self.made()["revision"]
        self.edit_kit("roles/rules/code.kt", "val rules = 2\n")
        self.field.table["b0"]["code"] = "val rules = 7 // mine\n"
        code, out = quietly(fk.migrate(self.field, self.kit))
        self.assertEqual(code, 2)
        self.assertIn("Refusing to migrate", out)
        self.assertIn("rules · code was edited in this document", out)
        self.assertEqual(self.field.log, [])
        self.assertEqual(self.field.table["b0"]["code"], "val rules = 7 // mine\n")
        self.assertEqual(self.record()["revision"], old)

    def test_an_edited_asset_is_refused_too(self) -> None:
        self.made()
        self.edit_kit("files/palette.json", '["blue"]\n')
        (self.field.document_dir / "files/palette.json").write_text('["green"]\n')
        code, out = quietly(fk.migrate(self.field, self.kit))
        self.assertEqual(code, 2)
        self.assertIn("palette.json was edited in this document", out)
        self.assertEqual((self.field.document_dir / "files/palette.json").read_text(), '["green"]\n')

    def test_a_running_build_is_refused(self) -> None:
        old = self.made()["revision"]
        self.edit_kit("roles/plan/code.kt", "println(rules + 1)\n")
        self.field.builds = ["walls · Measure paths (running)"]
        code, out = quietly(fk.migrate(self.field, self.kit))
        self.assertEqual(code, 2)
        self.assertIn("a build is running (walls · Measure paths (running))", out)
        self.assertEqual(self.field.log, [])
        self.assertEqual(self.record()["revision"], old)

    def test_a_failed_launch_leaves_the_old_revision_recorded_and_a_rerun_finishes(self) -> None:
        old = self.made()["revision"]
        new = self.edit_kit("roles/plan/code.kt", "println(rules + 1)\n")
        self.field.fail_launch = "Plan"
        with self.assertRaises(fk.KitError) as caught:
            quietly(fk.migrate(self.field, self.kit))
        self.assertIn(f"The record still says {old[:8]}", str(caught.exception))
        self.assertEqual(self.record()["revision"], old)
        self.field.fail_launch = None
        self.field.log.clear()
        code, out = quietly(fk.migrate(self.field, self.kit))
        self.assertEqual(code, 0, out)
        self.assertEqual(self.field.log, ["launch Rules", "launch Plan", "save"], "the installed code is current, so nothing is set again")
        self.assertEqual(self.record()["revision"], new)

    def test_a_stale_frozen_copy_is_upgraded_by_migration_and_said_so(self) -> None:
        self.made()
        # An earlier provider wrote the audit copy and it went stale; the kit itself has not changed.
        self.field.table["b0"]["rulesAudit"] = '{"rooms": 2}\n'
        code, out = quietly(fk.migrate(self.field, self.kit, dry_run=True))
        self.assertEqual(code, 0)
        self.assertIn("frozen copies: rules · rulesAudit (replaced with the kit's)", out)
        self.assertEqual(self.field.table["b0"]["rulesAudit"], '{"rooms": 2}\n', "a dry run changes nothing")
        code, out = quietly(fk.migrate(self.field, self.kit))
        self.assertEqual(code, 0, out)
        self.assertEqual(self.field.table["b0"]["rulesAudit"], '{"rooms": 3}\n')
        self.assertEqual(self.field.log[0], "set Rules.rulesAudit")

    def test_a_kit_out_of_step_with_its_files_is_refused(self) -> None:
        self.made()
        (self.kit / "roles/rules/code.kt").write_text("val rules = 2\n")
        with self.assertRaises(fk.KitError) as caught:
            asyncio.run(fk.migrate(self.field, self.kit))
        self.assertIn("run rehash first", str(caught.exception))

    def test_a_new_role_gets_a_box(self) -> None:
        self.made()
        (self.kit / "roles/walls").mkdir()
        (self.kit / "roles/walls/code.kt").write_text("println(\"walls\")\n")
        manifest = fk.load(self.kit)
        manifest["roles"]["walls"] = {"name": "Walls", "properties": {"code": {"file": "roles/walls/code.kt", "sha256": ""}}}
        manifest["launchOrder"].append("walls")
        fk.write_manifest(self.kit / "kit.json", manifest)
        quiet(fk.rehash, self.kit, check=False)
        code, out = quietly(fk.migrate(self.field, self.kit))
        self.assertEqual(code, 0, out)
        self.assertEqual(self.field.log[:3], ["new Walls", "set Walls.kitRole", "set Walls.code"])
        self.assertEqual(self.field.log[-2], "launch Walls")


class ActivateAndPublishTest(KitTestCase):
    def test_install_fills_an_empty_document_and_records_the_kit(self) -> None:
        self.made()
        other = FakeField(self.temp / "second")
        code, out = quietly(fk.activate(other, self.kit, install_kit=True))
        self.assertEqual(code, 0, out)
        self.assertEqual([e for e in other.log if e.startswith(("new", "launch"))], ["new Rules", "new Plan", "launch Rules", "launch Plan"])
        self.assertEqual(json.loads((other.document_dir / fk.RECORD).read_text())["revision"], fk.load(self.kit)["revision"])
        self.assertEqual((other.document_dir / "files/palette.json").read_text(), '["red"]\n')

    def test_install_refuses_a_document_already_on_a_kit(self) -> None:
        self.made()
        with self.assertRaises(fk.KitError):
            asyncio.run(fk.activate(self.field, self.kit, install_kit=True))

    def test_publish_copies_the_saved_document_its_assets_and_record_only(self) -> None:
        self.made()
        doc = self.field.document_dir
        (doc / "metadata.edn").write_text("{:version 2 :boxes []}")
        (doc / "b0_code_.kt").write_text("val rules = 1\n")
        (doc / "b0_ai4_chat_history_.md").write_text("a chat")
        (doc / "backups").mkdir()
        (doc / "files/scratch.bin").write_text("not the kit's")
        into = self.temp / "templates"
        code, out = quietly(fk.publish(self.field, "rooms", into))
        self.assertEqual(code, 0, out)
        published = sorted(p.relative_to(into / "rooms").as_posix() for p in (into / "rooms").rglob("*") if p.is_file())
        self.assertEqual(published, ["b0_code_.kt", "files/kit.json", "files/palette.json", "metadata.edn"])
        self.assertEqual(self.field.log, ["save"])
        with self.assertRaises(fk.KitError):
            asyncio.run(fk.publish(self.field, "rooms", into))
        quietly(fk.publish(self.field, "rooms", into, replace=True))
        self.assertEqual(len(list((into / ".replaced").iterdir())), 1)

    def test_publish_refuses_a_document_that_differs_from_its_kit(self) -> None:
        self.made()
        self.field.table["b1"]["code"] = "println(\"edited\")\n"
        with self.assertRaises(fk.KitError) as caught:
            asyncio.run(fk.publish(self.field, "rooms", self.temp / "templates"))
        self.assertIn("Publish a document that matches its kit", str(caught.exception))


if __name__ == "__main__":
    unittest.main()
