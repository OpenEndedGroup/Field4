"""Tests.

The pure conversion machinery is tested against hand-written dependency graphs
so it runs without a model.  The parser integration is tested separately and
skips itself if the stanza models are not downloaded yet.

    .venv/bin/python -m unittest discover -s tests -v
"""

import json
import os
import subprocess
import sys
import unittest

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(ROOT, "python"))

from fieldsentences.legacy import collapse, to_sd_basic
from fieldsentences.relations import SD_LONG, long_name
from fieldsentences.trees import depth, leaves, parse_bracketed


def toks(*specs):
    """Build a token list from ``word/POS`` or ``word/POS/lemma`` strings."""
    out = []
    for i, spec in enumerate(specs, 1):
        parts = spec.split("/")
        word, pos = parts[0], parts[1]
        lemma = parts[2] if len(parts) > 2 else word.lower()
        out.append({"id": i, "word": word, "pos": pos, "lemma": lemma})
    return out


class TestLongNames(unittest.TestCase):
    def test_names_seen_in_the_original_pdfs(self):
        # Every label legible in the 2011 diagrams, and the relation it came from.
        expected = {
            "nsubj": "nominal subject",
            "dobj": "direct object",
            "ccomp": "clausal complement",
            "xcomp": "xclausal complement",
            "advcl": "adverbial clause modifier",
            "advmod": "adverbial modifier",
            "amod": "adjectival modifier",
            "det": "determiner",
            "mark": "marker",
            "complm": "complementizer",
            "prt": "phrasal verb particle",
            "aux": "auxiliary",
            "poss": "possession modifier",
            "prep": "prepositional modifier",
        }
        for rel, name in expected.items():
            self.assertEqual(long_name(rel), name, rel)

    def test_collapsed_relations_render_as_collapsed(self):
        self.assertEqual(long_name("prep_of"), "prep collapsed")
        self.assertEqual(long_name("prep_off"), "prep collapsed")
        self.assertEqual(long_name("conj_and"), "conj collapsed")
        self.assertEqual(long_name("conj_or"), "conj collapsed")
        self.assertEqual(long_name("prepc_after"), "prepc collapsed")

    def test_unknown_relation_falls_back_to_itself(self):
        self.assertEqual(long_name("nonesuch"), "nonesuch")

    def test_ud_scheme_has_its_own_names(self):
        self.assertEqual(long_name("obj", "ud"), "direct object")
        self.assertEqual(long_name("obl", "ud"), "oblique nominal")

    def test_every_sd_relation_has_a_name(self):
        for rel, name in SD_LONG.items():
            self.assertTrue(name and isinstance(name, str), rel)


class TestPrepositionRotation(unittest.TestCase):
    """UD hangs the noun off the verb; SD hangs the preposition off the verb."""

    def test_rotation_then_collapse(self):
        # "reflects off the walls": obl(reflects, walls) + case(walls, off)
        t = toks("reflects/VBZ/reflect", "off/IN", "the/DT", "walls/NNS/wall")
        ud = [(0, 1, "root"), (4, 2, "case"), (4, 3, "det"), (1, 4, "obl")]

        basic = to_sd_basic(t, ud)
        self.assertIn((1, 2, "prep"), basic)   # prep hangs off the verb
        self.assertIn((2, 4, "pobj"), basic)   # noun hangs off the prep
        self.assertIn((4, 3, "det"), basic)

        edges, absorbed = collapse(t, basic)
        self.assertEqual(absorbed, {2})        # "off" is folded away
        self.assertIn((1, 4, "prep_off"), edges)
        self.assertNotIn(2, [d for _g, d, _r in edges])

    def test_clausal_preposition_becomes_prepc(self):
        # "after leaving": the preposition governs a verb, so SD said prepc.
        t = toks("stopped/VBD/stop", "after/IN", "leaving/VBG/leave")
        ud = [(0, 1, "root"), (3, 2, "case"), (1, 3, "obl")]
        edges, absorbed = collapse(t, to_sd_basic(t, ud))
        self.assertIn((1, 3, "prepc_after"), edges)
        self.assertEqual(long_name("prepc_after"), "prepc collapsed")

    def test_multiword_preposition_joins_its_parts(self):
        # "because of rain" -> one prep_because_of edge, both words absorbed.
        t = toks("cancelled/VBD/cancel", "because/IN", "of/IN", "rain/NN")
        ud = [(0, 1, "root"), (4, 2, "case"), (2, 3, "fixed"), (1, 4, "obl")]
        edges, absorbed = collapse(t, to_sd_basic(t, ud))
        self.assertIn((1, 4, "prep_because_of"), edges)
        self.assertEqual(absorbed, {2, 3})

    def test_possessive_marker_is_not_rotated(self):
        # "the plant's walls": 's is a case token but not a preposition.
        t = toks("plant/NN", "'s/POS", "walls/NNS/wall")
        ud = [(3, 1, "nmod:poss"), (1, 2, "case"), (0, 3, "root")]
        basic = to_sd_basic(t, ud)
        self.assertIn((1, 2, "possessive"), basic)
        edges, absorbed = collapse(t, basic)
        self.assertEqual(absorbed, set())

    def test_dangling_preposition_keeps_its_arc(self):
        # A prep with no object cannot be folded into anything.
        t = toks("go/VBP", "to/IN")
        basic = [(0, 1, "root"), (1, 2, "prep")]
        edges, absorbed = collapse(t, basic)
        self.assertEqual(absorbed, set())
        self.assertIn((1, 2, "prep"), edges)


class TestConjunctionCollapse(unittest.TestCase):
    def test_cc_moves_to_the_coordination_head_then_collapses(self):
        # "rubble and wreckage": UD hangs "and" off "wreckage", SD off "rubble".
        t = toks("rubble/NN", "and/CC", "wreckage/NN", "fell/VBD/fall")
        ud = [(4, 1, "nsubj"), (3, 2, "cc"), (1, 3, "conj"), (0, 4, "root")]

        basic = to_sd_basic(t, ud)
        self.assertIn((1, 2, "cc"), basic)

        edges, absorbed = collapse(t, basic)
        self.assertEqual(absorbed, {2})
        self.assertIn((1, 3, "conj_and"), edges)

    def test_or_coordination(self):
        t = toks("anything/NN", "or/CC", "anyone/NN")
        ud = [(0, 1, "root"), (3, 2, "cc"), (1, 3, "conj")]
        edges, _ = collapse(t, to_sd_basic(t, ud))
        self.assertIn((1, 3, "conj_or"), edges)

    def test_conj_without_a_conjunction_survives(self):
        t = toks("a/NN", "b/NN")
        edges, absorbed = collapse(t, [(0, 1, "root"), (1, 2, "conj")])
        self.assertEqual(absorbed, set())
        self.assertIn((1, 2, "conj"), edges)


class TestLabelRefinements(unittest.TestCase):
    def test_infinitival_to_is_an_auxiliary(self):
        # UD calls it mark; classic SD called it aux, as the PDFs show.
        t = toks("try/VB", "to/TO", "step/VB")
        ud = [(0, 1, "root"), (3, 2, "mark"), (1, 3, "xcomp")]
        basic = to_sd_basic(t, ud)
        self.assertIn((3, 2, "aux"), basic)

    def test_that_heading_a_complement_clause_is_a_complementizer(self):
        t = toks("said/VBD/say", "that/IN", "you/PRP", "go/VBP")
        ud = [(0, 1, "root"), (4, 2, "mark"), (4, 3, "nsubj"), (1, 4, "ccomp")]
        basic = to_sd_basic(t, ud)
        self.assertIn((4, 2, "complm"), basic)

    def test_if_heading_an_adverbial_clause_stays_a_marker(self):
        t = toks("listen/VB", "if/IN", "you/PRP", "will/MD")
        ud = [(0, 1, "root"), (4, 2, "mark"), (4, 3, "nsubj"), (1, 4, "advcl")]
        basic = to_sd_basic(t, ud)
        self.assertIn((4, 2, "mark"), basic)

    def test_negation_gets_its_own_relation(self):
        t = toks("give/VB", "n't/RB/not")
        ud = [(0, 1, "root"), (1, 2, "advmod")]
        self.assertIn((1, 2, "neg"), to_sd_basic(t, ud))


class TestNoTokenIsLost(unittest.TestCase):
    """A token may be absorbed on purpose, but never dropped by accident."""

    def _check(self, t, ud):
        edges, absorbed = collapse(t, to_sd_basic(t, ud))
        in_graph = set()
        for gov, dep, _rel in edges:
            if gov:
                in_graph.add(gov)
            in_graph.add(dep)
        for tok in t:
            self.assertTrue(
                tok["id"] in in_graph or tok["id"] in absorbed,
                "token %r has no arc and was not absorbed" % tok["word"],
            )
        return edges, absorbed

    def test_fixed_multiword_keeps_its_arc(self):
        # The regression that started this: "off course" is advmod + fixed, and
        # "course" must not vanish just because "fixed" maps onto SD's mwe.
        t = toks("push/VB", "off/IN", "course/NN")
        ud = [(0, 1, "root"), (1, 2, "advmod"), (2, 3, "fixed")]
        edges, absorbed = self._check(t, ud)
        self.assertEqual(absorbed, set())
        self.assertIn((2, 3, "mwe"), edges)

    def test_dependent_of_an_absorbed_preposition_is_reattached(self):
        # "right off the walls": advmod on the preposition, which then vanishes.
        t = toks("reflects/VBZ", "right/RB", "off/IN", "walls/NNS")
        basic = [(0, 1, "root"), (3, 2, "advmod"), (1, 3, "prep"), (3, 4, "pobj")]
        edges, absorbed = collapse(t, basic)
        self.assertEqual(absorbed, {3})
        # "right" moves onto the preposition's object rather than disappearing.
        self.assertIn((4, 2, "advmod"), edges)

    def test_stray_cc_survives(self):
        t = toks("watch/VB", "but/CC")
        edges, absorbed = collapse(t, [(0, 1, "root"), (1, 2, "cc")])
        self.assertEqual(absorbed, set())
        self.assertIn((1, 2, "cc"), edges)


class TestBracketedTrees(unittest.TestCase):
    SAMPLE = "(ROOT (S (NP (NN birdsong)) (VP (VBZ reflects) (PRT (RP off)))))"

    def test_leaves_are_numbered_left_to_right(self):
        tree = parse_bracketed(self.SAMPLE)
        got = [(l["label"], l["word"], l["token"]) for l in leaves(tree)]
        self.assertEqual(got, [
            ("NN", "birdsong", 1),
            ("VBZ", "reflects", 2),
            ("RP", "off", 3),
        ])

    def test_preterminals_fold_into_one_node(self):
        # The POS tag and its word become a single node; the phrase above it does
        # not fold, so (NP (NN plant)) keeps NP as an ordinary internal node.
        tree = parse_bracketed("(NP (NN plant))")
        self.assertFalse(tree["leaf"])
        self.assertEqual(tree["label"], "NP")

        leaf = tree["children"][0]
        self.assertTrue(leaf["leaf"])
        self.assertEqual(leaf["label"], "NN")
        self.assertEqual(leaf["word"], "plant")
        self.assertEqual(leaf["children"], [])

    def test_depth(self):
        # ROOT > S > VP > PRT > RP
        tree = parse_bracketed(self.SAMPLE)
        self.assertEqual(depth(tree), 4)
        self.assertEqual(tree["depth"], 0)

    def test_node_ids_are_unique(self):
        tree = parse_bracketed(self.SAMPLE)

        def ids(n):
            out = [n["id"]]
            for c in n["children"]:
                out.extend(ids(c))
            return out

        got = ids(tree)
        self.assertEqual(len(got), len(set(got)))

    def test_malformed_trees_are_rejected(self):
        for bad in ["(ROOT (S", "", "ROOT)", "(A) (B)"]:
            with self.assertRaises(ValueError, msg=bad):
                parse_bracketed(bad)


def _models_available():
    try:
        from stanza.resources.common import DEFAULT_MODEL_DIR
    except ImportError:
        return False
    # Location moved between stanza releases, so ask the library rather than
    # guessing at ~/stanza_resources.
    return os.path.isdir(os.path.join(DEFAULT_MODEL_DIR, "en"))


@unittest.skipUnless(_models_available(), "stanza models not downloaded")
class TestEndToEnd(unittest.TestCase):
    SENTENCE = "Listen if you will birdsong reflects off the tagged walls of the plant"

    @classmethod
    def setUpClass(cls):
        from fieldsentences import analyse

        cls.diagrams = analyse(cls.SENTENCE)
        cls.d = cls.diagrams[0]

    def test_one_sentence(self):
        self.assertEqual(len(self.diagrams), 1)

    def test_constituency_leaves_align_with_tokens(self):
        ls = leaves(self.d["constituency"])
        self.assertEqual(len(ls), len(self.d["tokens"]))
        for leaf, tok in zip(ls, self.d["tokens"]):
            self.assertEqual(leaf["token"], tok["id"])
            self.assertEqual(leaf["word"], tok["word"])
            # The leaf's label is the token's POS tag -- that is what makes the
            # top tree and the bottom arcs line up on one row of words.
            self.assertEqual(leaf["label"], tok["pos"])

    def test_root_is_root(self):
        self.assertEqual(self.d["constituency"]["label"], "ROOT")

    def test_every_token_has_an_arc_or_was_absorbed(self):
        for tok in self.d["tokens"]:
            self.assertTrue(tok["in_graph"] or tok["absorbed"], tok["word"])

    def test_prepositions_are_the_absorbed_ones(self):
        absorbed = {t["word"] for t in self.d["tokens"] if t["absorbed"]}
        self.assertEqual(absorbed, {"off", "of"})

    def test_collapsed_graph_covers_every_surviving_token(self):
        deps = [e["dep"] for e in self.d["dependencies"]["sd_collapsed"]]
        self.assertEqual(len(deps), len(set(deps)), "a token has two governors")
        survivors = {t["id"] for t in self.d["tokens"] if not t["absorbed"]}
        self.assertEqual(set(deps), survivors)

    def test_exactly_one_root(self):
        roots = [e for e in self.d["dependencies"]["sd_collapsed"] if e["gov"] == 0]
        self.assertEqual(len(roots), 1)
        self.assertEqual(roots[0]["rel"], "root")

    def test_all_three_schemes_present(self):
        for scheme in ("ud", "sd_basic", "sd_collapsed"):
            self.assertTrue(self.d["dependencies"][scheme], scheme)

    def test_collapsed_has_a_prep_edge_with_a_marker(self):
        preps = [
            e for e in self.d["dependencies"]["sd_collapsed"]
            if e["base"] == "prep" and "marker" in e
        ]
        self.assertTrue(preps)
        self.assertEqual(preps[0]["label"], "prep collapsed")

    def test_json_serialisable(self):
        json.dumps(self.diagrams)

    def test_multiple_sentences_split(self):
        from fieldsentences import analyse

        got = analyse("Watch your step. Listen to the plant.")
        self.assertEqual(len(got), 2)


@unittest.skipUnless(_models_available(), "stanza models not downloaded")
class TestCommandLine(unittest.TestCase):
    def _run(self, *args):
        proc = subprocess.run(
            [sys.executable, os.path.join(ROOT, "python", "fieldsentences")] + list(args),
            capture_output=True, text=True, cwd=ROOT,
        )
        self.assertEqual(proc.returncode, 0, proc.stderr)
        return proc

    def test_sentence_as_argument(self):
        got = json.loads(self._run("Listen to the plant").stdout)
        self.assertEqual(len(got), 1)
        self.assertEqual(got[0]["backend"], "stanza")

    def test_compact_output_is_one_line(self):
        out = self._run("Listen to the plant", "--indent", "0").stdout.strip()
        self.assertEqual(len(out.splitlines()), 1)

    def test_stdin(self):
        proc = subprocess.run(
            [sys.executable, os.path.join(ROOT, "python", "fieldsentences"), "-"],
            input="Listen to the plant", capture_output=True, text=True, cwd=ROOT,
        )
        self.assertEqual(proc.returncode, 0, proc.stderr)
        self.assertEqual(len(json.loads(proc.stdout)), 1)

    def test_empty_input_is_an_empty_list(self):
        proc = subprocess.run(
            [sys.executable, os.path.join(ROOT, "python", "fieldsentences"), "-"],
            input="   ", capture_output=True, text=True, cwd=ROOT,
        )
        self.assertEqual(json.loads(proc.stdout), [])

    def test_output_file(self):
        import tempfile

        with tempfile.TemporaryDirectory() as tmp:
            path = os.path.join(tmp, "out.json")
            self._run("Listen to the plant", "-o", path)
            with open(path) as fh:
                self.assertEqual(len(json.load(fh)), 1)


class TestCoreNLPBackend(unittest.TestCase):
    def test_missing_server_explains_how_to_start_one(self):
        from fieldsentences.backends import corenlp_backend

        with self.assertRaises(RuntimeError) as ctx:
            corenlp_backend.parse("Listen.", url="http://localhost:9", timeout=2)
        self.assertIn("StanfordCoreNLPServer", str(ctx.exception))

    def test_unknown_backend_is_rejected(self):
        from fieldsentences import backends

        with self.assertRaises(ValueError):
            backends.get("nope")


if __name__ == "__main__":
    unittest.main()
