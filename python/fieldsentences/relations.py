"""Relation vocabularies.

The 2011 diagrams were drawn from Stanford Dependencies (SD) in *collapsed*
form, and they print the long-form English description of each relation
("nominal subject") rather than its short code ("nsubj").  Modern parsers emit
Universal Dependencies (UD) instead, so we keep both vocabularies and a bridge
between them.
"""

# Classic Stanford Dependencies -> the long names printed in the diagrams.
# Taken from the Stanford Dependencies manual (the de-facto source for the
# strings that appear in the original PDFs).
SD_LONG = {
    "root": "root",
    "dep": "dependent",
    "aux": "auxiliary",
    "auxpass": "passive auxiliary",
    "cop": "copula",
    "arg": "argument",
    "agent": "agent",
    "comp": "complement",
    "acomp": "adjectival complement",
    "attr": "attributive",
    "ccomp": "clausal complement",
    "xcomp": "xclausal complement",
    "complm": "complementizer",
    "obj": "object",
    "dobj": "direct object",
    "iobj": "indirect object",
    "pobj": "object of a preposition",
    "mark": "marker",
    "rel": "relative",
    "acl": "clausal modifier of noun",
    "subj": "subject",
    "nsubj": "nominal subject",
    "nsubjpass": "passive nominal subject",
    "csubj": "clausal subject",
    "csubjpass": "passive clausal subject",
    "cc": "coordination",
    "conj": "conjunct",
    "expl": "expletive",
    "mod": "modifier",
    "amod": "adjectival modifier",
    "appos": "appositional modifier",
    "advcl": "adverbial clause modifier",
    "det": "determiner",
    "predet": "predeterminer",
    "preconj": "preconjunct",
    "infmod": "infinitival modifier",
    "mwe": "multi-word expression",
    "partmod": "participial modifier",
    "advmod": "adverbial modifier",
    "neg": "negation modifier",
    "rcmod": "relative clause modifier",
    "quantmod": "quantifier phrase modifier",
    "nn": "noun compound modifier",
    "npadvmod": "noun phrase as adverbial modifier",
    "tmod": "temporal modifier",
    "num": "numeric modifier",
    "number": "element of compound number",
    "prep": "prepositional modifier",
    "poss": "possession modifier",
    "possessive": "possessive modifier",
    "prt": "phrasal verb particle",
    "parataxis": "parataxis",
    "punct": "punctuation",
    "ref": "referent",
    "sdep": "semantic dependent",
    "xsubj": "controlling subject",
    "discourse": "discourse element",
    "goeswith": "goes with",
    "vmod": "reduced non-finite verbal modifier",
}

# Universal Dependencies -> long names, for when you want to render the UD
# analysis directly rather than the legacy SD one.
UD_LONG = {
    "acl": "clausal modifier of noun",
    "acl:relcl": "relative clause modifier",
    "advcl": "adverbial clause modifier",
    "advmod": "adverbial modifier",
    "amod": "adjectival modifier",
    "appos": "appositional modifier",
    "aux": "auxiliary",
    "aux:pass": "passive auxiliary",
    "case": "case marking",
    "cc": "coordination",
    "cc:preconj": "preconjunct",
    "ccomp": "clausal complement",
    "compound": "compound",
    "compound:prt": "phrasal verb particle",
    "conj": "conjunct",
    "cop": "copula",
    "csubj": "clausal subject",
    "csubj:pass": "passive clausal subject",
    "dep": "dependent",
    "det": "determiner",
    "det:predet": "predeterminer",
    "discourse": "discourse element",
    "dislocated": "dislocated",
    "expl": "expletive",
    "fixed": "fixed multiword expression",
    "flat": "flat multiword expression",
    "goeswith": "goes with",
    "iobj": "indirect object",
    "list": "list",
    "mark": "marker",
    "nmod": "nominal modifier",
    "nmod:poss": "possession modifier",
    "nmod:tmod": "temporal modifier",
    "nsubj": "nominal subject",
    "nsubj:pass": "passive nominal subject",
    "nummod": "numeric modifier",
    "obj": "direct object",
    "obl": "oblique nominal",
    "obl:agent": "agent",
    "obl:npmod": "noun phrase as adverbial modifier",
    "obl:tmod": "temporal modifier",
    "orphan": "orphan",
    "parataxis": "parataxis",
    "punct": "punctuation",
    "root": "root",
    "vocative": "vocative",
    "xcomp": "xclausal complement",
}

# UD relation -> nearest classic SD relation.  Where UD merged distinctions that
# SD kept (or vice versa) we pick the SD label the 2011 diagrams would have
# shown.  Entries needing token-level context (obj -> dobj vs pobj, mark ->
# complm) are refined in ``legacy.py``.
UD_TO_SD = {
    "acl": "partmod",
    "acl:relcl": "rcmod",
    "advcl": "advcl",
    "advmod": "advmod",
    "amod": "amod",
    "appos": "appos",
    "aux": "aux",
    "aux:pass": "auxpass",
    "case": "prep",
    "cc": "cc",
    "cc:preconj": "preconj",
    "ccomp": "ccomp",
    "compound": "nn",
    "compound:prt": "prt",
    "conj": "conj",
    "cop": "cop",
    "csubj": "csubj",
    "csubj:pass": "csubjpass",
    "dep": "dep",
    "det": "det",
    "det:predet": "predet",
    "discourse": "discourse",
    "expl": "expl",
    "fixed": "mwe",
    "flat": "nn",
    "goeswith": "goeswith",
    "iobj": "iobj",
    "mark": "mark",
    "nmod": "prep",
    "nmod:poss": "poss",
    "nmod:tmod": "tmod",
    "nsubj": "nsubj",
    "nsubj:pass": "nsubjpass",
    "nummod": "num",
    "obj": "dobj",
    "obl": "prep",
    "obl:agent": "agent",
    "obl:npmod": "npadvmod",
    "obl:tmod": "tmod",
    "parataxis": "parataxis",
    "punct": "punct",
    "root": "root",
    "xcomp": "xcomp",
}


def long_name(rel, scheme="sd"):
    """Long-form English description of ``rel``.

    Collapsed relations (``prep_of``, ``conj_and``) render as "prep collapsed"
    and "conj collapsed", which is what the original diagrams printed rather
    than spelling out the folded-in preposition.
    """
    table = SD_LONG if scheme == "sd" else UD_LONG
    if rel in table:
        return table[rel]
    base = rel.split("_", 1)[0].split(":", 1)[0]
    if "_" in rel and base in ("prep", "prepc", "conj", "agent"):
        return "%s collapsed" % base
    return table.get(base, rel)
