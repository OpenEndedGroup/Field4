#!/usr/bin/env python3
"""Hold each demo's `build.yaml` to the document it claims to have built.

Nine of the nineteen documents under `dev_docs/agents/demos/` ship an agent-authored
`build.yaml` beside the saved `metadata.edn`, and each one says, in its own description, some
version of "this transcript is what built this document; re-running it rebuilds it". Nothing
checked that. On 2026-09-18 the promotion sweep (`ad06001ac`) discovered by hand that two of the
nine had stopped being true: the document had been edited in Field and re-saved, and the build
script was left behind. `logging_layer`'s older code had lost an import and no longer compiled;
`frame_history_reprojection`'s `rp_chain` was 42 lines in the build script against 108 in the
document.

Both were found by a person reading the files. This is that reading, as a test, so the *next*
one is found by CI: for every `create` op that sets a `code` property, the script's text must be
byte-for-byte the box's committed `<box>_code_.kt` sidecar.

Two known-stale demos are listed in `KNOWN_STALE` with the boxes that have drifted. The list is
exact rather than per-demo — a *new* drift in `logging_layer` still fails — and the test also
fails when a listed box has been repaired, so an exception cannot outlive its reason.

What this does NOT check: that re-running the script produces the same frames, names, graph
topology or property set. Only the Kotlin, which is where every drift found so far has been, and
which is the part that stops compiling. A full replay-and-compare is `checkAgentTranscripts`'
job and costs a JVM; this costs a file read.

Run directly with:  uv run --project . --group backdoor python -m unittest tools.test_demo_build_scripts
"""

from __future__ import annotations

import unittest
from pathlib import Path

import yaml

TOOLS_ROOT = Path(__file__).resolve().parent
REPO_ROOT = TOOLS_ROOT.parent
DEMOS_ROOT = REPO_ROOT / "dev_docs" / "agents" / "demos"

# demo -> {box: why this one is allowed to differ}. Measured 2026-09-19 on the tree at
# 9d8bc0248; each of these is a document edited in Field after its build script was written.
KNOWN_STALE: dict[str, dict[str, str]] = {
    "frame_history_reprojection": {
        "rp_bundle": "Build script has 40 lines against the document's 52; found by ad06001ac.",
        "rp_chain": "Build script has 42 lines against the document's 108; found by ad06001ac.",
    },
    "logging_layer": {
        "log_channels": "Build script has 23 lines against the document's 27; the older code "
        "lost an import and does not compile (ad06001ac).",
        "log_mute_is_console_only": "Build script has 26 lines against the document's 27.",
        "log_blocks": "Build script has 47 lines against the document's 48.",
    },
}


def _coded_boxes(build_yaml: Path) -> dict[str, str]:
    """Every box the script creates with a `code` property, in the order it creates them."""
    document = yaml.safe_load(build_yaml.read_text()) or {}
    out: dict[str, str] = {}
    for op in document.get("operations") or []:
        create = op.get("create") if isinstance(op, dict) else None
        if not create:
            continue
        code = (create.get("properties") or {}).get("code")
        if code is not None:
            out[create["box"]] = code
    return out


class DemoBuildScriptTest(unittest.TestCase):
    def setUp(self) -> None:
        self.scripts = sorted(DEMOS_ROOT.glob("*/build.yaml"))

    def test_the_corpus_is_where_it_was_measured(self) -> None:
        """A demo that gains or loses a build script should make someone look at this file."""
        self.assertTrue(DEMOS_ROOT.is_dir(), f"no demo corpus at {DEMOS_ROOT}")
        demos = sorted(p.name for p in DEMOS_ROOT.iterdir() if p.is_dir())
        self.assertEqual(
            19,
            len(demos),
            f"the demo corpus is now {len(demos)} documents ({', '.join(demos)}); "
            "re-measure how many ship a build.yaml and update this test's docstring",
        )
        self.assertEqual(
            9,
            len(self.scripts),
            "the number of demos shipping a build.yaml has changed; a new one is unchecked "
            "until it is measured",
        )

    def test_every_created_box_has_a_sidecar(self) -> None:
        missing = []
        for script in self.scripts:
            for box in _coded_boxes(script):
                if not (script.parent / f"{box}_code_.kt").exists():
                    missing.append(f"{script.parent.name}/{box}")
        self.assertEqual(
            [],
            missing,
            "a build script creates a box with code that the saved document has no sidecar "
            f"for, so the two cannot be compared at all: {missing}",
        )

    def test_build_scripts_still_hold_the_documents_code(self) -> None:
        unexpected: list[str] = []
        repaired: list[str] = []
        for script in self.scripts:
            demo = script.parent.name
            allowed = KNOWN_STALE.get(demo, {})
            for box, code in _coded_boxes(script).items():
                sidecar = script.parent / f"{box}_code_.kt"
                if not sidecar.exists():
                    continue  # reported by test_every_created_box_has_a_sidecar
                drifted = code.rstrip("\n") != sidecar.read_text().rstrip("\n")
                if drifted and box not in allowed:
                    unexpected.append(
                        f"{demo}/{box}: build.yaml has {len(code.rstrip(chr(10)).splitlines())} "
                        f"lines, the document has "
                        f"{len(sidecar.read_text().rstrip(chr(10)).splitlines())}"
                    )
                if not drifted and box in allowed:
                    repaired.append(f"{demo}/{box}")
        self.assertEqual(
            [],
            unexpected,
            "a demo's build.yaml no longer holds the code its document does — the document was "
            "edited in Field and the build script left behind. Either re-run the script to "
            "rebuild the document, or copy the box back into the script: " + str(unexpected),
        )
        self.assertEqual(
            [],
            repaired,
            "these boxes are listed in KNOWN_STALE but now match their document; remove the "
            "entry so the exception does not outlive its reason: " + str(repaired),
        )


if __name__ == "__main__":
    unittest.main()
