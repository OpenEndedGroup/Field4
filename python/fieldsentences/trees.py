"""Penn-bracketed tree parsing.

CoreNLP hands back constituency trees as bracketed strings, and it is often
convenient to feed a hand-written tree in the same form, so keep one reader for
both.
"""


def _tokenize(s):
    buf = ""
    for ch in s:
        if ch in "()":
            if buf.strip():
                yield buf.strip()
            buf = ""
            yield ch
        elif ch.isspace():
            if buf.strip():
                yield buf.strip()
            buf = ""
        else:
            buf += ch
    if buf.strip():
        yield buf.strip()


def parse_bracketed(text):
    """Parse a bracketed tree into the same dict shape the backends produce.

    Leaves are folded into their preterminal, so ``(NN plant)`` becomes one node
    labelled ``NN`` carrying ``word`` and a 1-based ``token`` index.
    """
    tokens = list(_tokenize(text))
    if not tokens:
        raise ValueError("empty tree")

    pos = [0]
    counter = [0]
    ntokens = [0]

    def node():
        if tokens[pos[0]] != "(":
            raise ValueError("expected '(' at %d" % pos[0])
        pos[0] += 1

        label = ""
        if pos[0] < len(tokens) and tokens[pos[0]] not in "()":
            label = tokens[pos[0]]
            pos[0] += 1

        nid = counter[0]
        counter[0] += 1

        children = []
        word = None
        while pos[0] < len(tokens) and tokens[pos[0]] != ")":
            if tokens[pos[0]] == "(":
                children.append(node())
            else:
                word = tokens[pos[0]]
                pos[0] += 1

        if pos[0] >= len(tokens):
            raise ValueError("unbalanced tree: missing ')'")
        pos[0] += 1  # consume ')'

        if word is not None and not children:
            ntokens[0] += 1
            return {
                "id": nid,
                "label": label,
                "leaf": True,
                "word": word,
                "token": ntokens[0],
                "children": [],
            }

        return {
            "id": nid,
            "label": label,
            "leaf": False,
            "children": children,
        }

    tree = node()
    if pos[0] != len(tokens):
        raise ValueError("trailing content after tree")
    return tree


def leaves(node):
    """Leaf nodes, left to right."""
    if node.get("leaf"):
        return [node]
    out = []
    for c in node["children"]:
        out.extend(leaves(c))
    return out


def depth(node, d=0):
    """Annotate each node with its depth, in place, and return the max."""
    node["depth"] = d
    if not node["children"]:
        return d
    return max(depth(c, d + 1) for c in node["children"])
