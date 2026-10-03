"""Short clips from public-domain films, for trying detectors without a camera.

    cv4.sample("swing")       # a Video source, looping

Each clip is a silent excerpt, re-encoded small. The films and their sources:

* ``swing``, ``machines``, ``kiki`` — *Ballet Mécanique*, Fernand Léger and Dudley Murphy, 1924
  (public domain in the US since 2020, published 1924). Source copy:
  https://archive.org/details/BalletMcanique, a 2005 edition whose page states no licence of its
  own. The excerpts are the 1924 film only: none of the edition's title cards or credits, and no
  sound (the edition's score is its own). ``swing`` is 0:30–0:44 (Katherine Murphy on a garden
  swing), ``machines`` is 2:40–2:56 (turning gears and discs), ``kiki`` is 10:25–10:40 (Kiki de
  Montparnasse).
* ``serpentine`` — *Annabelle Serpentine Dance*, W. K. L. Dickson and William Heise for Edison,
  1895, hand-tinted; 0:22–0:44. Public domain:
  https://commons.wikimedia.org/wiki/File:Serpentine_Dance_(1895)_-_yt.webm
* ``muybridge`` — Eadweard Muybridge, *Animal Locomotion* plate 187, "Woman Dancing (fancy)",
  1887, the plate's frames as a 12 fps loop. Public domain:
  https://commons.wikimedia.org/wiki/File:Eadweard_Muybridge,_Animal_Locomotion,_Woman_Dancing_(fancy),_1887,_plate_187.gif
"""

from __future__ import annotations

from pathlib import Path

from .sources import Video

_DIR = Path(__file__).resolve().parent / "samples"

SAMPLES: dict[str, str] = {
    "swing": "ballet_mecanique_swing.mp4",
    "machines": "ballet_mecanique_machines.mp4",
    "kiki": "ballet_mecanique_kiki.mp4",
    "serpentine": "annabelle_serpentine_dance.mp4",
    "muybridge": "muybridge_woman_dancing.mp4",
}


def sample_path(name: str) -> str:
    try:
        return str(_DIR / SAMPLES[name])
    except KeyError:
        raise KeyError(f"no sample called {name!r}; there are {', '.join(SAMPLES)}") from None


def sample(name: str, loop: bool = True, **kwargs) -> Video:
    """The sample clip ``name`` as a :class:`~fieldcv.sources.Video` (looping by default)."""
    return Video(sample_path(name), loop=loop, **kwargs)
