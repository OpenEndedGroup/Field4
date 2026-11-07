"""Parser backends.

``stanza`` is the default: pip-installable, no Java, and Stanford's own
successor to the parser these diagrams originally came from.  ``corenlp``
talks to a real CoreNLP server, which emits genuine Stanford Dependencies and
so reproduces the 2011 output without any UD-to-SD reconstruction.
"""


def get(name):
    if name == "stanza":
        from . import stanza_backend

        return stanza_backend
    if name == "corenlp":
        from . import corenlp_backend

        return corenlp_backend
    raise ValueError("unknown backend %r (expected 'stanza' or 'corenlp')" % name)


NAMES = ("stanza", "corenlp")
