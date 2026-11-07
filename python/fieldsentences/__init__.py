"""Sentence diagrams: constituency tree above, dependency arcs below.

    >>> from fieldsentences import analyse
    >>> [d] = analyse("Listen if you will birdsong reflects off the walls")
    >>> d["dependencies"]["sd_collapsed"][0]["label"]
    'root'
"""

from .core import SCHEMA_VERSION, analyse

__all__ = ["analyse", "SCHEMA_VERSION"]
__version__ = "0.1.0"
