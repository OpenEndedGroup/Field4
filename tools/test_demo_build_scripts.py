#!/usr/bin/env python3
"""Hold each demo's `build.yaml` to the document it claims to have built.

Nine of the twenty-one documents under `dev_docs/agents/demos/` ship an agent-authored
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

The corpus count moved 19 -> 20 on 2026-09-19 when `skinned_characters` landed from a sibling
branch on the same night. That is what this assertion is for and it did its job: the two branches
touched no file in common, so `git merge-tree` and a file-set intersection were both clean, and
this equality was the only thing that noticed. `skinned_characters` ships no `build.yaml`, so the
count of nine is unchanged. Since 2026-09-20 the corpus is held as a *named set* (`CORPUS`) rather
than a count, so the next time this fires it says which document arrived; and what it noticed about
`skinned_characters` — that it exists — is now separate from whether anything verifies it, which is
`AgentDemoRegistrationTest`'s job and was nobody's.

It fired again on 2026-09-20, exactly as designed and for the second night running: `bc71ed216`
added `marf_demo`, the twenty-first, and this suite said so **by name** with the three remedies
attached — against the previous night's useless `20 != 19`. The part worth carrying forward is not
that the guard worked. It is that `bc71ed216`'s own commit message records its author checking this
family by hand (*"`checkAgentTranscriptLayouts` scans demo documents and is not in the pre-commit
guard"*) and re-running the guard they had noticed, while this one — the one wired into `check` via
`checkPythonTools` — went unrun, because nothing runs `check` on commit. `main` then sat red for
hours. A guard that only fires in a place nobody looks is a slower version of no guard.

What this does NOT check: that re-running the script produces the same frames, names, graph
topology or property set. Only the Kotlin, which is where every drift found so far has been, and
which is the part that stops compiling. A full replay-and-compare is `checkAgentTranscripts`'
job and costs a JVM; this costs a file read.

Run directly with:  uv run --project . --group backdoor python -m unittest tools.test_demo_build_scripts
"""

from __future__ import annotations

import subprocess
import unittest
from pathlib import Path

import yaml

TOOLS_ROOT = Path(__file__).resolve().parent
REPO_ROOT = TOOLS_ROOT.parent
DEMOS_ROOT = REPO_ROOT / "dev_docs" / "agents" / "demos"

# The demo corpus, by name. Measured 2026-09-23: `ls -1d dev_docs/agents/demos/*/` — 25.
#
# Twenty-five *directories*, twenty-four *documents*: `flow_grain` arrived on 2026-09-22 with no
# `metadata.edn`, and is the only entry here that is not a saved document at all. This set is the
# directory listing, which is the population a sibling branch grows, so it holds all 25. Anything
# that asks "is this document verified?" has to filter on `metadata.edn` first — see the note on
# the entry itself, and `AgentDemoRegistrationTest.demoDocuments`.
#
# This was a bare `assertEqual(20, len(demos))` until 2026-09-20. The count was not wrong — it is
# the assertion that caught `skinned_characters` arriving from a sibling branch the night before,
# and its docstring above says so. It failed with `20 != 19`, which is true and tells you nothing:
# not which document, not whether it is verified, not what to do. Two branches adding a demo on one
# night touch no file in common, so `git merge-tree` is clean by construction and this is the only
# thing that speaks — which is exactly why it should speak in names.
CORPUS = {
    "alife_evolution",
    "alife_script",
    "alife_walls",
    # 2026-09-23, interval-arithmetic envelopes for Fn. Ships a `build.yaml`, so the count below
    # moved 9 -> 10; its four boxes were measured equal to the document's sidecars on arrival.
    "certified_envelope",
    "chart_clip",
    "chart_primitive",
    "completion_rank_at_scale",
    "confluence_grid",
    # Lighting/neural examples use transcript sources and their separate GPU verification.
    # They ship no build.yaml; the measured build-script count below is unchanged.
    "direct_lighting",
    "learned_from_module",
    "learned_shader",
    "neural_reconstruction",
    "optical_flow",
    "optical_flow_pingpong",
    "ray_reconstruction",
    "path_traced_reconstruction",
    "computed_ray_geometry",
    "gpu_shader_learning",
    "reconstruction_plumbing",
    "rr_plumbing",
    # Landed 2026-09-22 in `b5b9ad9e0`, the twenty-second directory and the only one that is not a
    # saved document: no `metadata.edn`, no `build.yaml` (so the nine below is unchanged), and no
    # `_code_.kt` sidecars. It is the *source* of a transcript — `tools/demos/build_flow_grain.py`
    # reads these `.kt`/`.comp`/`.frag`/`.glsl` files and emits
    # `dev_docs/agents/transcripts/flow_grain.yaml`, which is registered `COMPILE_ONLY`. So it is
    # verified, and by the harness this family cares about; it just is not verified *as a document*.
    "flow_grain",
    "frame_history_reprojection",
    # Both landed 2026-09-23, the twenty-fourth and twenty-fifth directories, in `948602d5a` and
    # `7fc49b2ed`: the blank starter and the worked example of the same AI4 function conversation.
    # Neither ships a `build.yaml`, so the nine below is unchanged. They arrived unregistered in
    # every name-list that tracks this corpus, and left three guards red on `main` at once — this
    # one, `test_guard_corpora`, and `checkAgentTranscriptLayouts`. Same gap `marf_demo` left, for
    # the same reason: none of the three runs on commit.
    "function_conversation",
    "function_conversation_example",
    # Promoted 2026-09-22 out of `dev_docs/agents/artifacts/`, the twenty-third directory. Ships no
    # `build.yaml` (so the nine below is unchanged); its transcript
    # `dev_docs/agents/transcripts/gpu_probe_bench.yaml` is registered `COMPILE_ONLY`, because box 1
    # opens a Window. It was parked under `artifacts/` for one day to avoid editing a file a
    # concurrent session held, and nothing under `artifacts/` is named by any registry in the tree.
    "gpu_probe_bench",
    "interaction_nets_lambda",
    "keyframe_key_discovery",
    "logging_layer",
    # Landed 2026-09-20 in `bc71ed216`, the twenty-first. Ships no `build.yaml`, so the nine below
    # is unchanged. Its author ran `checkAgentTranscriptLayouts` by hand and missed this suite,
    # which is wired into `check` — see the module docstring for why that is the interesting part.
    "marf_demo",
    "midi_clip",
    "path_tracer_instrument",
    "path_tracing_emitters",
    "path_tracing_medium",
    "pattern_condense",
    "python_cells",
    "read_only_session",
    "skinned_characters",
    "solidsl_inverse",
    # Landed 2026-09-26: the StackSL conversation's blank starter and its three recorded sessions.
    # None ships a `build.yaml`, so the nine below is unchanged; the metadata is generated by
    # `tools/stacksl_conversation_documents.py`, whose --check holds it equal to the sidecars.
    "stacksl_conversation",
    "stacksl_conversation_aurora",
    "stacksl_conversation_ink",
    "stacksl_conversation_tiles",
    # Landed 2026-09-27: the RectSL conversation's blank starter and its three recorded sessions,
    # generated by `tools/rectsl_conversation_documents.py` in the same way.
    "rectsl_conversation",
    "rectsl_conversation_lanterns",
    "rectsl_conversation_score",
    "rectsl_conversation_tide",
    # Landed 2026-09-28: a recorded stack.auto conversation. Field wrote its metadata; no build.yaml.
    "stackauto_conversation_polytope",
    "stackauto_conversation",
}

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


def committed_demo_directories() -> set[str]:
    """The demo directories git is tracking, not the ones that happen to be on disk.

    `DEMOS_ROOT.iterdir()` was the derivation until 2026-09-25 and it cannot tell a demo from a
    scratch folder: an ignored `.agent-scratch/` beside the corpus read as an undeclared demo and
    failed this guard in a working tree whose committed corpus was entirely correct. A ledger is a
    statement about what is committed, so it is asked of git.

    Falls back to the directory listing when git cannot answer — a source tarball with no `.git`
    should still be checkable, and there the two are the same thing anyway.
    """
    try:
        out = subprocess.run(
            ["git", "ls-files", "-z", "--", str(DEMOS_ROOT)],
            cwd=REPO_ROOT, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, check=True,
        ).stdout.decode()
    except (OSError, subprocess.SubprocessError):
        return {p.name for p in DEMOS_ROOT.iterdir() if p.is_dir()}
    root = DEMOS_ROOT.relative_to(REPO_ROOT).as_posix() + "/"
    names = set()
    for entry in out.split("\0"):
        if entry.startswith(root):
            rest = entry[len(root):]
            if "/" in rest:
                names.add(rest.split("/", 1)[0])
    return names


class DemoBuildScriptTest(unittest.TestCase):
    def setUp(self) -> None:
        self.scripts = sorted(DEMOS_ROOT.glob("*/build.yaml"))

    def test_the_corpus_is_where_it_was_measured(self) -> None:
        """A demo that gains or loses a build script should make someone look at this file."""
        self.assertTrue(DEMOS_ROOT.is_dir(), f"no demo corpus at {DEMOS_ROOT}")
        demos = committed_demo_directories()
        self.assertEqual(
            set(),
            demos - CORPUS,
            f"new demo document(s) {sorted(demos - CORPUS)}. Add each name to CORPUS above, check "
            "whether it ships a build.yaml (and move the count below if so), and register it for "
            "verification — see AgentDemoRegistrationTest.",
        )
        self.assertEqual(
            set(),
            CORPUS - demos,
            f"demo document(s) {sorted(CORPUS - demos)} are named in CORPUS but are not on disk; "
            "if they were removed or renamed, update CORPUS, KNOWN_STALE and "
            "dev_docs/DEMO_CORPUS_AUDIT.md together",
        )
        self.assertEqual(
            10,
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
