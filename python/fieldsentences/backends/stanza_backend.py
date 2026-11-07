"""Stanza backend -- Stanford's current Python parser.

Pure pip install, no Java.  Gives us a constituency tree and a Universal
Dependencies graph over the same tokenisation, which is exactly the pair the
diagrams need.
"""

import functools

_PROCESSORS = "tokenize,pos,lemma,depparse,constituency"

NAME = "stanza"


@functools.lru_cache(maxsize=4)
def _pipeline(lang, package, device="cpu"):
    import stanza

    kwargs = dict(lang=lang, processors=_PROCESSORS, verbose=False,
                  tokenize_no_ssplit=False, use_gpu=device == "cuda")
    if package:
        kwargs["package"] = package
    return stanza.Pipeline(**kwargs)


def download(lang="en", package=None):
    import stanza

    stanza.download(lang, processors=_PROCESSORS, package=package or "default")


def _tree_to_dict(node, counter, token_ids):
    """Convert a stanza constituency node into plain dicts.

    Stanza represents a preterminal as a node whose single child is a bare leaf
    carrying the surface word, so we fold that pair into one node holding both
    the POS label and the token reference.
    """
    nid = counter[0]
    counter[0] += 1

    children = list(node.children)
    is_preterminal = len(children) == 1 and not children[0].children

    if is_preterminal:
        tid = len(token_ids) + 1
        token_ids.append(tid)
        return {
            "id": nid,
            "label": node.label,
            "leaf": True,
            "word": children[0].label,
            "token": tid,
            "children": [],
        }

    return {
        "id": nid,
        "label": node.label,
        "leaf": False,
        "children": [_tree_to_dict(c, counter, token_ids) for c in children],
    }


def parse(text, lang="en", package=None, device="cpu"):
    """Parse *text*, yielding one raw analysis dict per sentence."""
    doc = _pipeline(lang, package, device)(text)
    out = []
    for sent in doc.sentences:
        tokens = []
        for word in sent.words:
            # Multi-word tokens share a parent token; fall back to its span.
            parent = word.parent
            tokens.append({
                "id": word.id,
                "word": word.text,
                "lemma": word.lemma or word.text,
                "pos": word.xpos or word.upos,
                "upos": word.upos,
                "feats": word.feats,
                "start": getattr(parent, "start_char", None),
                "end": getattr(parent, "end_char", None),
            })

        ud_edges = [(w.head, w.id, w.deprel) for w in sent.words]

        counter = [0]
        token_ids = []
        tree = _tree_to_dict(sent.constituency, counter, token_ids)
        if len(token_ids) != len(tokens):
            # Should not happen -- both views come off one tokenisation -- but a
            # silent misalignment would corrupt every leaf reference downstream.
            raise RuntimeError(
                "constituency leaves (%d) do not match tokens (%d)"
                % (len(token_ids), len(tokens))
            )

        out.append({
            "text": sent.text,
            "tokens": tokens,
            "ud_edges": ud_edges,
            "constituency": tree,
            "constituency_bracketed": str(sent.constituency),
        })
    return out
