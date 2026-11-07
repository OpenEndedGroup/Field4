"""Convert Universal Dependencies into classic collapsed Stanford Dependencies.

Modern parsers emit UD; the 2011 diagrams were drawn from Stanford Dependencies
in *collapsed* form.  Two steps get us back:

``to_sd_basic``
    Relabel into the SD vocabulary and, crucially, *rotate* prepositional and
    coordinating structures.  UD hangs the noun off the verb and the preposition
    off the noun (``obl(reflects, walls)``, ``case(walls, off)``); SD hangs the
    preposition off the verb and the noun off the preposition
    (``prep(reflects, off)``, ``pobj(off, walls)``).  Same for conjunctions: UD
    attaches ``cc`` to the following conjunct, SD to the head of the
    coordination.

``collapse``
    Delete those prepositions and conjunctions as graph nodes and fold them into
    the relation name, so ``prep(reflects, off) + pobj(off, walls)`` becomes one
    ``prep_off(reflects, walls)``.

The deletion is visible in the original PDFs: collapsed-away tokens are the ones
drawn without an underline, because they have no arcs left.
"""

from .relations import UD_TO_SD

_PREP_HOSTS = ("obl", "nmod")

_COMPLEMENTISERS = ("that", "whether", "if")

_NEGATIONS = ("not", "n't", "nt", "never", "no")

_POSSESSIVES = ("'s", "'", "s")


def _base(rel):
    return rel.split(":", 1)[0]


def _children(edges):
    out = {}
    for gov, dep, rel in edges:
        out.setdefault(gov, []).append((dep, rel))
    return out


def _tok(tokens, tid):
    return tokens[tid - 1]


def _lemma_of(tokens, tid):
    tok = _tok(tokens, tid)
    return (tok.get("lemma") or tok["word"]).lower().replace(" ", "_")


def _pos_of(tokens, tid):
    return _tok(tokens, tid).get("pos") or ""


def to_sd_basic(tokens, ud_edges):
    """Relabel and restructure UD edges into classic SD basic form."""
    kids = _children(ud_edges)
    incoming = {dep: (gov, rel) for gov, dep, rel in ud_edges}

    # One governing edge per dependent, keyed by dependent, so rotations can
    # overwrite what the plain relabelling would have produced.
    edge_for = {}
    rotated = set()

    for gov, dep, rel in ud_edges:
        if _base(rel) not in _PREP_HOSTS:
            continue
        cases = [
            d for d, r in kids.get(dep, [])
            if _base(r) == "case" and _lemma_of(tokens, d) not in _POSSESSIVES
        ]
        if not cases:
            continue

        # The leftmost case token heads the preposition; any others ("because
        # of", "out of") were a multi-word expression hanging off it in SD.
        head = min(cases)
        edge_for[head] = (gov, "prep")
        # A preposition governing a clause took pcomp rather than pobj.
        inner = "pcomp" if _pos_of(tokens, dep).startswith("VB") else "pobj"
        edge_for[dep] = (head, inner)
        for other in cases:
            if other != head:
                edge_for[other] = (head, "mwe")
        rotated.add(dep)
        rotated.update(cases)

    for gov, dep, rel in ud_edges:
        if _base(rel) == "cc":
            # UD attaches cc to the following conjunct; SD to the coordination
            # head, which is that conjunct's own governor.
            host_gov, host_rel = incoming.get(gov, (None, None))
            if host_rel and _base(host_rel) == "conj" and host_gov:
                edge_for[dep] = (host_gov, "cc")
                rotated.add(dep)

    for gov, dep, rel in ud_edges:
        if dep in rotated:
            continue

        sd = UD_TO_SD.get(rel) or UD_TO_SD.get(_base(rel)) or _base(rel)
        lemma = _lemma_of(tokens, dep)
        pos = _pos_of(tokens, dep)
        base = _base(rel)

        if base == "mark":
            if pos == "TO" or lemma == "to":
                # Infinitival "to" was an auxiliary in classic SD.
                sd = "aux"
            elif lemma in _COMPLEMENTISERS and _base(
                incoming.get(gov, (None, ""))[1] or ""
            ) in ("ccomp", "csubj"):
                sd = "complm"
        elif base == "advmod" and lemma in _NEGATIONS:
            sd = "neg"
        elif base == "case" and lemma in _POSSESSIVES:
            sd = "possessive"

        edge_for[dep] = (gov, sd)

    return [
        (edge_for[dep][0], dep, edge_for[dep][1])
        for dep in sorted(edge_for)
    ]


def collapse(tokens, sd_edges):
    """Fold prepositions and conjunctions into relation names.

    Returns ``(edges, absorbed)``: the collapsed dependency list, and the set of
    token ids deleted from the graph.

    Only tokens genuinely folded into a relation name are absorbed.  Anything
    else keeps an arc, so a token can never drop out of the graph just because it
    happened to carry a relation this function knows how to rewrite.
    """
    kids = _children(sd_edges)
    absorbed = set()
    collapsed = []
    # Where a preposition's other dependents should move once it disappears.
    reattach = {}

    for gov, dep, rel in sd_edges:
        if _base(rel) != "prep":
            continue
        prep = dep
        objects = [
            (d, r) for d, r in kids.get(prep, [])
            if _base(r) in ("pobj", "pcomp")
        ]
        if not objects:
            # Dangling preposition -- nothing to fold it into, so it stays.
            continue

        mwe = [d for d, r in kids.get(prep, []) if _base(r) == "mwe"]
        marker = "_".join(_lemma_of(tokens, t) for t in sorted([prep] + mwe))
        absorbed.add(prep)
        absorbed.update(mwe)
        reattach[prep] = objects[0][0]
        for obj, obj_rel in objects:
            stem = "prepc" if _base(obj_rel) == "pcomp" else "prep"
            collapsed.append((gov, obj, "%s_%s" % (stem, marker)))

    for gov, dep, rel in sd_edges:
        if _base(rel) != "conj":
            continue
        ccs = [d for d, r in kids.get(gov, []) if _base(r) == "cc"]
        between = [c for c in ccs if gov < c < dep]
        cc = max(between) if between else (min(ccs) if ccs else None)
        if cc is None:
            continue
        absorbed.add(cc)
        collapsed.append((gov, dep, "conj_%s" % _lemma_of(tokens, cc)))

    out = list(collapsed)
    rewritten = {d for _g, d, _r in collapsed}

    for gov, dep, rel in sd_edges:
        if dep in absorbed or dep in rewritten:
            continue
        # If this token hung off a preposition that has just been folded away,
        # move it onto that preposition's object rather than dropping it.
        while gov in absorbed and gov in reattach:
            gov = reattach[gov]
        if gov in absorbed:
            continue
        out.append((gov, dep, rel))

    return sorted(out, key=lambda e: e[1]), absorbed
