"""Rank the docs site's written pages above its generated ones in search.

Of the 17,219 entries in the built `search.json`, 12,782 are `reference/` pages: one per Kotlin
symbol and source file, generated from KDoc. Zensical's search worker ranks a hit first by the
field it matched in (title 3, nav path 2, text 1), then by how well the terms match, and leaves
every remaining tie in index order. So any symbol whose name contains the query (`SliderTest.kt`,
`FLineHitKind`) sat level with, or above, the written page that teaches the thing, and there are
so many symbols that they filled the first screen of results for most words.

Zensical has no per-page boost. Material for MkDocs had one (`search: boost:` in front matter),
but neither 0.0.47 nor 0.1.3 carries it into `search.json` or reads it in the worker. So the build
does both itself, after Zensical has finished:

- every entry from a written page gets `"boost": 2` in `search.json`. A page is generated when its
  first line is the marker `loc_history.GENERATED_PAGE` matches, the same test the docs line count
  uses; every `reference/` page carries it, and so do `linear_algebra_api.md` and
  `field_audio_service_reference.md`.
- the worker multiplies a hit's field weight by its entry's boost before comparing two hits. A
  written page's title (6) now beats a symbol's title (3), and a symbol's title still beats a
  written page that only mentions the word in its body (2), so searching for `AnimationClock`
  still opens on its reference entry.

The worker patch rewrites three exact strings in Zensical's minified worker and refuses to guess:
if any of them is not there exactly once, `patch_worker_source` raises, so a Zensical upgrade that
renames them fails `tools/test_docs_search_boost.py` instead of quietly ranking as before. The
patched worker gets a new content-hashed name, and every page's `__config` is pointed at it, because
the site is served through CloudFront and a changed file under an old name can be served stale.
"""

from __future__ import annotations

import hashlib
import json
import re
from dataclasses import dataclass
from pathlib import Path

from loc_history import GENERATED_PAGE

WRITTEN_PAGE_BOOST = 2

# Each pair is (Zensical 0.0.47's minified source, its replacement). In order:
# 1. the field-weight comparator takes each hit's boost and multiplies it in;
# 2. the document comparator passes each document's boost, looked up by its id, which is its index
#    in `search.json`'s items;
# 3. loading the index records every item's boost, defaulting to 1.
WORKER_PATCHES: tuple[tuple[str, str], ...] = (
    (
        "return(a,s)=>r*(l.get(a.field)-l.get(s.field))",
        "return(a,s,x=1,y=1)=>r*(l.get(a.field)*x-l.get(s.field)*y)",
    ),
    (
        "if(f=o(s.matches[d],u.matches[d]),",
        "if(f=o(s.matches[d],u.matches[d],self.__fieldBoost[s.id],self.__fieldBoost[u.id]),",
    ),
    (
        "case 0:Z=yield se(e.data.items,",
        "case 0:self.__fieldBoost=e.data.items.map(x=>x.boost||1),Z=yield se(e.data.items,",
    ),
)

WORKER_DIR = Path("assets/javascripts/workers")
WORKER_NAME = re.compile(r"^search\.[0-9a-f]{8,}\.min\.js$")


class WorkerPatchError(RuntimeError):
    """Zensical's search worker no longer has the code the boost patch rewrites."""


@dataclass(frozen=True)
class BoostReport:
    boosted_items: int
    total_items: int
    generated_pages: int
    worker: str
    pages_repointed: int


def page_location(relative: Path) -> str:
    """The `search.json` location of the page built from [relative] (`use_directory_urls`)."""
    folder = relative.parent if relative.stem in ("index", "README") else relative.parent / relative.stem
    return "" if folder == Path(".") else f"{folder.as_posix()}/"


def generated_page_locations(docs_dir: Path) -> frozenset[str]:
    """Locations of the pages under [docs_dir] whose first line marks them as generated."""
    locations = set()
    for page in docs_dir.rglob("*.md"):
        with page.open("rb") as handle:
            if GENERATED_PAGE.match(handle.readline()):
                locations.add(page_location(page.relative_to(docs_dir)))
    return frozenset(locations)


def boost_search_index(index: dict, generated: frozenset[str]) -> int:
    """Give every item of [index] that is not on a generated page the written-page boost."""
    boosted = 0
    for item in index["items"]:
        if item["location"].split("#", 1)[0] not in generated:
            item["boost"] = WRITTEN_PAGE_BOOST
            boosted += 1
    return boosted


def patch_worker_source(source: str) -> str:
    """[source], Zensical's search worker, comparing hits by field weight times boost."""
    for original, replacement in WORKER_PATCHES:
        count = source.count(original)
        if count != 1:
            raise WorkerPatchError(
                f"expected `{original}` once in Zensical's search worker, found it {count} times; "
                "update WORKER_PATCHES in tools/docs_search_boost.py for this Zensical version"
            )
        source = source.replace(original, replacement)
    return source


def patch_worker(site_dir: Path) -> tuple[str, str]:
    """Replace the site's search worker with a boosting copy under a new hashed name.

    Returns (old name, new name).
    """
    workers = [path for path in (site_dir / WORKER_DIR).iterdir() if WORKER_NAME.match(path.name)]
    if len(workers) != 1:
        raise WorkerPatchError(f"expected one search worker in {site_dir / WORKER_DIR}, found {len(workers)}")
    worker = workers[0]
    patched = patch_worker_source(worker.read_text(encoding="utf-8"))
    digest = hashlib.sha256(patched.encode("utf-8")).hexdigest()[:8]
    renamed = worker.with_name(f"search.{digest}.min.js")
    renamed.write_text(patched, encoding="utf-8")
    worker.unlink()
    return worker.name, renamed.name


def repoint_pages(site_dir: Path, old_name: str, new_name: str) -> int:
    """Point every page's `__config` search worker at [new_name]; returns pages rewritten."""
    old_ref, new_ref = f"workers/{old_name}", f"workers/{new_name}"
    pages = 0
    for page in site_dir.rglob("*.html"):
        text = page.read_text(encoding="utf-8")
        if old_ref in text:
            page.write_text(text.replace(old_ref, new_ref), encoding="utf-8")
            pages += 1
    return pages


def apply(*, site_dir: Path, docs_dir: Path) -> BoostReport:
    """Boost written pages in [site_dir]'s search index and install the worker that reads it."""
    generated = generated_page_locations(docs_dir)
    index_path = site_dir / "search.json"
    index = json.loads(index_path.read_text(encoding="utf-8"))
    boosted = boost_search_index(index, generated)
    old_name, new_name = patch_worker(site_dir)
    index_path.write_text(json.dumps(index, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
    return BoostReport(
        boosted_items=boosted,
        total_items=len(index["items"]),
        generated_pages=len(generated),
        worker=new_name,
        pages_repointed=repoint_pages(site_dir, old_name, new_name),
    )
