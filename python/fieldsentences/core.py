"""Assemble the diagram JSON.

One sentence in, one dict out, carrying both halves of the picture: the
constituency tree that hangs above the words and the dependency arcs that hang
below them, sharing one token list so a drawing program can line them up.
"""

from . import backends, trees
from .legacy import collapse, to_sd_basic
from .relations import long_name

SCHEMA_VERSION = 2


def _edge(gov, dep, rel, scheme):
    base = rel.split("_", 1)[0].split(":", 1)[0]
    out = {
        "gov": gov,
        "dep": dep,
        "rel": rel,
        "base": base,
        "label": long_name(rel, scheme),
        # Arcs run left-to-right or right-to-left under the words; the drawing
        # code usually wants to know which without recomputing it.
        "direction": "right" if dep > gov else "left",
        "distance": abs(dep - gov),
    }
    if "_" in rel and base in ("prep", "prepc", "conj", "agent"):
        out["marker"] = rel.split("_", 1)[1]
    return out


def analyse(text, backend="stanza", **backend_kwargs):
    """Parse *text* and return one diagram dict per sentence."""
    module = backends.get(backend)
    raws = module.parse(text, **backend_kwargs)

    out = []
    for raw in raws:
        tokens = raw["tokens"]

        if "sd_edges" in raw:
            sd_basic = raw["sd_edges"]
            ud_edges = raw.get("ud_edges") or []
        else:
            ud_edges = raw["ud_edges"]
            sd_basic = to_sd_basic(tokens, ud_edges)

        sd_collapsed, absorbed = collapse(tokens, sd_basic)

        tree = raw["constituency"]
        max_depth = trees.depth(tree)

        # A token is drawn with an underline when it survives collapsing as a
        # node in the dependency graph -- which is what distinguishes "walls"
        # from the "of" that got folded into prep_of.
        in_graph = set()
        for gov, dep, _rel in sd_collapsed:
            if gov:
                in_graph.add(gov)
            in_graph.add(dep)

        token_out = []
        for tok in tokens:
            entry = dict(tok)
            entry["in_graph"] = tok["id"] in in_graph
            entry["absorbed"] = tok["id"] in absorbed
            token_out.append(entry)

        out.append({
            "schema": SCHEMA_VERSION,
            "backend": module.NAME,
            "text": raw["text"],
            "tokens": token_out,
            "constituency": tree,
            "constituency_bracketed": raw["constituency_bracketed"],
            "constituency_depth": max_depth,
            "dependencies": {
                "ud": [_edge(g, d, r, "ud") for g, d, r in ud_edges],
                "sd_basic": [_edge(g, d, r, "sd") for g, d, r in sd_basic],
                # The one the 2011 diagrams drew.
                "sd_collapsed": [_edge(g, d, r, "sd") for g, d, r in sd_collapsed],
            },
        })
    return out
