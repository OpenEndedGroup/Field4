"""CoreNLP backend -- the real 2011 lineage.

Talks to a running CoreNLP server with ``parse.originalDependencies=true``, so
the dependency relations come back as genuine Stanford Dependencies rather than
Universal Dependencies.  Nothing has to be reconstructed: the relation names are
the ones the original diagrams printed.

Needs Java and the CoreNLP distribution.  See README for the one-liner that
starts the server.
"""

import json
import urllib.error
import urllib.parse
import urllib.request

from ..trees import parse_bracketed

NAME = "corenlp"

DEFAULT_URL = "http://localhost:9000"

_PROPERTIES = {
    "annotators": "tokenize,ssplit,pos,lemma,parse",
    # The switch that gets us Stanford Dependencies instead of UD.
    "parse.originalDependencies": "true",
    "outputFormat": "json",
}


def parse(text, url=DEFAULT_URL, extra_properties=None, timeout=60):
    props = dict(_PROPERTIES)
    if extra_properties:
        props.update(extra_properties)

    query = urllib.parse.urlencode({"properties": json.dumps(props)})
    request = urllib.request.Request(
        "%s/?%s" % (url.rstrip("/"), query),
        data=text.encode("utf-8"),
        method="POST",
    )
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            payload = json.loads(response.read().decode("utf-8"))
    except urllib.error.URLError as exc:
        raise RuntimeError(
            "could not reach CoreNLP at %s (%s).\n"
            "Start it with:\n"
            "  java -mx4g -cp 'stanford-corenlp-*/*' "
            "edu.stanford.nlp.pipeline.StanfordCoreNLPServer -port 9000" % (url, exc)
        ) from exc

    out = []
    for sent in payload["sentences"]:
        tokens = []
        for tok in sent["tokens"]:
            tokens.append({
                "id": tok["index"],
                "word": tok["word"],
                "lemma": tok.get("lemma") or tok["word"],
                "pos": tok.get("pos"),
                "upos": None,
                "feats": None,
                "start": tok.get("characterOffsetBegin"),
                "end": tok.get("characterOffsetEnd"),
            })

        deps = sent.get("basicDependencies") or sent.get("enhancedDependencies") or []
        edges = [(d["governor"], d["dependent"], d["dep"]) for d in deps]
        # CoreNLP names the root edge "ROOT"; everything downstream expects the
        # lowercase SD spelling.
        edges = [
            (g, d, "root" if r.upper() == "ROOT" else r) for g, d, r in edges
        ]

        tree = parse_bracketed(sent["parse"])

        out.append({
            "text": " ".join(t["word"] for t in tokens),
            "tokens": tokens,
            # Already Stanford Dependencies -- flag it so the core layer skips
            # the UD-to-SD relabelling step.
            "sd_edges": edges,
            "constituency": tree,
            "constituency_bracketed": sent["parse"],
        })
    return out
