"""Stdlib HTTP client for Field's separately running sentence parser.

Field appends its shipped python/ tree to Python-box imports:
    from fieldsentences.client import parse
    diagram = parse("The quiet water carries the light")

Prepare with ./gradlew prepareSentenceParser, then start the optional environment's
Python on python/fieldsentences --serve 9500. This client never loads Torch in Field.
"""

import json
import urllib.error
import urllib.request

DEFAULT_URL = "http://127.0.0.1:9500"


class ParserUnavailable(RuntimeError):
    pass


def parse_all(text, url=DEFAULT_URL, timeout=30):
    """Parse *text* and return one diagram dict per sentence."""
    request = urllib.request.Request(
        url.rstrip("/") + "/",
        data=text.encode("utf-8"),
        method="POST",
    )
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            payload = json.loads(response.read().decode("utf-8"))
    except urllib.error.URLError as exc:
        raise ParserUnavailable(
            "no parser at %s (%s) -- start it with:\n"
            "    python python/fieldsentences --serve 9500" % (url, exc)
        ) from exc

    if isinstance(payload, dict) and "error" in payload:
        raise RuntimeError(payload["error"])
    return payload


def parse(text, url=DEFAULT_URL, timeout=30):
    """Parse a single sentence, returning its diagram dict.

    Raises if *text* turned out to hold no sentence at all; use ``parse_all``
    when the input may span several.
    """
    got = parse_all(text, url=url, timeout=timeout)
    if not got:
        raise ValueError("no sentence found in %r" % text)
    return got[0]


def words(diagram):
    """The token words, in order -- the row the diagram is built around."""
    return [t["word"] for t in diagram["tokens"]]


def arcs(diagram, scheme="sd_collapsed"):
    """Dependency arcs as ``(gov_word, dep_word, label)`` triples.

    The governor is ``None`` for the root arc.
    """
    by_id = {t["id"]: t for t in diagram["tokens"]}
    out = []
    for edge in diagram["dependencies"][scheme]:
        gov = by_id[edge["gov"]]["word"] if edge["gov"] else None
        out.append((gov, by_id[edge["dep"]]["word"], edge["label"]))
    return out


def walk(node, depth=0):
    """Yield ``(node, depth)`` for the constituency tree, parents before children."""
    yield node, depth
    for child in node["children"]:
        for item in walk(child, depth + 1):
            yield item


if __name__ == "__main__":
    import sys

    text = " ".join(sys.argv[1:]) or "Listen if you will birdsong reflects off the walls"
    d = parse(text)
    print(" ".join(words(d)))
    for gov, dep, label in arcs(d):
        print("  %-26s %s -> %s" % (label, gov or "ROOT", dep))
