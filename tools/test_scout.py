"""tools/scout.py: the idea scout's ledger, sources, page text, watch pool, cards, digest and verdicts.

Nothing here touches the network or the checkout: every test works in a temporary journal with a
fake fetcher that serves canned responses, and `SCOUT_NOW` pins the clock.
"""

from __future__ import annotations

import datetime as dt
import json
import os
import shutil
import sys
import tempfile
import textwrap
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent))
import scout  # noqa: E402

DAY = 86400
T0 = dt.datetime(2026, 11, 6, 12, 0).timestamp()


class FakeFetcher:
    """Serves {url-prefix: (status, content_type, body)}; records what was asked for."""

    def __init__(self, routes: dict[str, tuple[int, str, object]]):
        self.routes = routes
        self.asked: list[str] = []

    def _route(self, url: str):
        self.asked.append(url)
        for prefix in sorted(self.routes, key=len, reverse=True):
            if url.startswith(prefix):
                return self.routes[prefix]
        return 404, "text/plain", b""

    def get(self, url, accept="*/*", max_bytes=None):
        status, ctype, body = self._route(url)
        if not isinstance(body, bytes):
            body = (json.dumps(body) if not isinstance(body, str) else body).encode()
        return scout.Response(status, ctype, body, url)

    def json(self, url):
        r = self.get(url)
        if r.status != 200:
            raise OSError(f"HTTP {r.status}")
        return json.loads(r.body)


class Journal(unittest.TestCase):
    def setUp(self) -> None:
        self.dir = Path(tempfile.mkdtemp(prefix="scout-test-"))
        self.addCleanup(shutil.rmtree, self.dir, True)
        self.clock(T0)
        self.ledger = scout.Ledger(self.dir)

    def clock(self, t: float) -> None:
        patcher = mock.patch.dict(os.environ, {"SCOUT_NOW": str(t)})
        patcher.start()
        self.addCleanup(patcher.stop)

    def add(self, title="A post", url="https://example.com/post", hn="1", points=100, topic=None):
        item, _ = self.ledger.add(title, url, {"src": "hn", "hn": hn, "points": points}, topic)
        return item


class Urls(unittest.TestCase):
    def test_normalize_drops_www_fragment_tracking_and_trailing_slash(self) -> None:
        self.assertEqual(scout.normalize_url("http://www.Example.com/a/b/?utm_source=hn&x=1#top"),
                         "https://example.com/a/b?x=1")

    def test_one_key_per_page(self) -> None:
        self.assertEqual(scout.key_for("https://example.com/a/"), scout.key_for("http://www.example.com/a?ref=hn"))
        self.assertEqual(scout.key_for(None, "123"), "hn-123")

    def test_github_repo(self) -> None:
        self.assertEqual(scout.github_repo("https://github.com/aatishb/patterncollider/"), ("aatishb", "patterncollider"))
        self.assertIsNone(scout.github_repo("https://github.com/aatishb/patterncollider/blob/main/x.js"))


class TheLedger(Journal):
    def test_a_repost_adds_a_sighting_not_a_link(self) -> None:
        self.add(hn="1", points=80)
        item = self.add(hn="2", points=300)
        self.assertEqual(len(self.ledger.items), 1)
        self.assertEqual(item.hn_ids, ["1", "2"])
        self.assertEqual(item.points, 300)

    def test_a_repost_revives_a_title_drop_but_not_a_read_page(self) -> None:
        item = self.add(hn="1")
        item.move("dropped", "off-domain title")
        self.add(hn="2")
        self.assertEqual(item.status, "new")
        item.page = "pages/x.txt"
        item.move("dropped", "read and dropped")
        self.add(hn="3")
        self.assertEqual(item.status, "dropped")

    def test_round_trip(self) -> None:
        self.add(topic="lenia")
        self.ledger.save()
        again = scout.Ledger(self.dir)
        (item,) = again.items.values()
        self.assertEqual(item.topics, ["lenia"])
        self.assertEqual(item.status, "new")


class Sources(Journal):
    def test_harvest_reads_hn_topics_lobsters_and_feeds_and_survives_a_failure(self) -> None:
        rss = textwrap.dedent("""\
            <?xml version="1.0"?><rss version="2.0"><channel>
            <item><title>Fresh &amp; new</title><link>https://blog.example/new</link><pubDate>Wed, 04 Nov 2026 10:00:00 GMT</pubDate></item>
            <item><title>Old post</title><link>https://blog.example/old</link><pubDate>Mon, 01 Jan 2024 10:00:00 GMT</pubDate></item>
            </channel></rss>""")
        atom = textwrap.dedent("""\
            <feed xmlns="http://www.w3.org/2005/Atom"><entry><title>An atom entry</title>
            <link rel="alternate" href="https://atom.example/e1"/><updated>2026-11-05T00:00:00Z</updated></entry></feed>""")
        fetcher = FakeFetcher({
            "https://hn.algolia.com/api/v1/search_by_date?tags=story&hitsPerPage=1000": (200, "application/json", {"hits": [
                {"objectID": "10", "title": "Front page story", "url": "https://a.example/x", "points": 120, "created_at": "2026-11-06T01:00:00Z"}]}),
            "https://hn.algolia.com/api/v1/search_by_date?query=lenia": (200, "application/json", {"hits": [
                {"objectID": "11", "title": "Lenia in 3D", "url": "https://b.example/lenia", "points": 3, "created_at": "2026-11-06T02:00:00Z"}]}),
            "https://lobste.rs/t/graphics.json": (200, "application/json", [
                {"title": "A lobsters story", "url": "https://c.example/l", "score": 20, "created_at": "2026-11-05T00:00:00-05:00"}]),
            "https://blog.example/rss": (200, "application/rss+xml", rss),
            "https://atom.example/atom": (200, "application/atom+xml", atom),
        })
        sources = {"hn": {"min_points": 50}, "hn_topics": {"queries": ["lenia", "faust"]},
                   "lobsters": {"tags": ["graphics"]},
                   "feeds": [{"name": "blog", "url": "https://blog.example/rss"}, {"name": "atom", "url": "https://atom.example/atom"},
                             {"name": "gone", "url": "https://gone.example/feed"}]}
        result = scout.harvest(self.ledger, fetcher, sources, log=lambda _: None)
        titles = {i.title for i in self.ledger.items.values()}
        self.assertEqual(titles, {"Front page story", "Lenia in 3D", "A lobsters story", "Fresh & new", "An atom entry"})
        self.assertEqual(result["failures"], ["hn-topic:faust: HTTP 404", "feed:gone: HTTP 404"])
        lenia = next(i for i in self.ledger.items.values() if i.title == "Lenia in 3D")
        self.assertEqual(lenia.topics, ["lenia"])
        self.assertTrue((self.dir / "ledger.jsonl").exists())

    def test_a_link_list_feed_is_expanded_once(self) -> None:
        rss = ('<rss><channel><item><title>Weekly 460</title><link>https://weekly.example/460</link>'
               '<pubDate>Thu, 05 Nov 2026 00:00:00 GMT</pubDate></item></channel></rss>')
        issue = ('<html><body><a href="https://weekly.example/about">About this newsletter</a>'
                 '<a href="https://paint.example/squish">Paint That Squishes</a>'
                 '<a href="https://web.archive.org/web/x">wayback-archive</a>'
                 '<a href="https://gpu.example/driven">GPU-Driven Anything</a></body></html>')
        fetcher = FakeFetcher({"https://weekly.example/rss": (200, "application/rss+xml", rss),
                               "https://weekly.example/460": (200, "text/html", issue)})
        sources = {"feeds": [{"name": "weekly", "url": "https://weekly.example/rss", "expand": True}]}
        scout.harvest(self.ledger, fetcher, sources, log=lambda _: None)
        new = sorted(i.title for i in self.ledger.with_status("new"))
        self.assertEqual(new, ["GPU-Driven Anything", "Paint That Squishes"])
        scout.harvest(self.ledger, fetcher, sources, log=lambda _: None)
        self.assertEqual(fetcher.asked.count("https://weekly.example/460"), 1)


class TitlePass(Journal):
    def test_drops_listed_keys_queues_the_rest_and_never_drops_a_topic_hit(self) -> None:
        off = self.add("Funding round", "https://biz.example/a", hn="1")
        keep = self.add("Shader explainer", "https://gfx.example/b", hn="2")
        topic = self.add("Faust 3 released", "https://faust.example/c", hn="3", topic="faust dsp")
        dropped, queued = scout.triage(self.ledger, {off.key, topic.key})
        self.assertEqual((dropped, queued), (1, 2))
        self.assertEqual((off.status, keep.status, topic.status), ("dropped", "queued", "queued"))


class Pages(Journal):
    def test_html_to_text_keeps_what_a_card_needs(self) -> None:
        page = ('<html><head><title>Sky</title><style>x{}</style></head><body><nav>menu</nav>'
                '<h1>Rendering the sky</h1><p>Rayleigh &amp; Mie.</p><img alt="Sunset at 45 degrees">'
                '<canvas></canvas><input type="range" aria-label="Sun angle"><script>evil()</script>'
                '<a href="https://github.com/o/r">repo</a><a href="https://elsewhere.example">x</a></body></html>')
        title, text, links = scout.html_to_text(page)
        self.assertEqual(title, "Sky")
        for wanted in ("# Rendering the sky", "Rayleigh & Mie.", "[figure: Sunset at 45 degrees]",
                       "[interactive canvas]", "[slider: Sun angle]"):
            self.assertIn(wanted, text)
        self.assertNotIn("evil", text)
        self.assertNotIn("menu", text)
        self.assertEqual(links, ["https://github.com/o/r"])

    def test_fetch_writes_an_untrusted_page_and_github_comes_back_as_readme_and_license(self) -> None:
        article = self.add("An explainer", "https://gfx.example/post", hn="1")
        repo = self.add("A tool", "https://github.com/o/tool", hn="2")
        for item in (article, repo):
            item.move("queued")
        body = "<html><body><h1>Explainer</h1><p>" + "words " * 400 + "</p></body></html>"
        fetcher = FakeFetcher({
            "https://gfx.example/post": (200, "text/html; charset=utf-8", body),
            "https://api.github.com/repos/o/tool/releases/latest": (200, "application/json", {"tag_name": "v1.2"}),
            "https://api.github.com/repos/o/tool": (200, "application/json", {"license": {"spdx_id": "MIT"}, "stargazers_count": 9,
                                                                             "pushed_at": "2026-11-01", "description": "d"}),
            "https://raw.githubusercontent.com/o/tool/HEAD/README.md": (200, "text/plain", "# Tool\n\nIt does things."),
        })
        done = scout.fetch(self.ledger, fetcher, limit=10, log=lambda _: None)
        self.assertEqual(done["fetched"], 2)
        text = (self.dir / article.page).read_text()
        self.assertTrue(text.startswith(scout.UNTRUSTED))
        self.assertIn("# Explainer", text)
        self.assertEqual((repo.meta["license"], repo.meta["release"]), ("MIT", "v1.2"))
        self.assertIn("It does things.", (self.dir / repo.page).read_text())
        self.assertEqual({article.status, repo.status}, {"fetched"})

    def test_a_thin_page_gets_its_hn_discussion(self) -> None:
        item = self.add("A WebGPU demo", "https://app.example/demo", hn="77")
        item.move("queued")
        fetcher = FakeFetcher({
            "https://app.example/demo": (200, "text/html", "<html><body><div id=root></div></body></html>"),
            "https://hn.algolia.com/api/v1/items/77": (200, "application/json", {
                "text": None, "children": [{"author": "ann", "text": "<p>It simulates fire spread on a grid.</p>"}]}),
        })
        scout.fetch(self.ledger, fetcher, log=lambda _: None)
        text = (self.dir / item.page).read_text()
        self.assertIn("HN discussion follows", text)
        self.assertIn("[ann] It simulates fire spread", text)

    def test_a_refused_page_is_read_from_its_hn_discussion(self) -> None:
        item = self.add("A long explainer", "https://patreon.example/post", hn="88")
        item.move("queued")
        fetcher = FakeFetcher({
            "https://patreon.example/post": (403, "text/html", "blocked"),
            "https://hn.algolia.com/api/v1/items/88": (200, "application/json", {
                "text": None, "children": [{"author": "bo", "text": "<p>The figures solve Navier-Stokes live.</p>"}]}),
        })
        done = scout.fetch(self.ledger, fetcher, log=lambda _: None)
        self.assertEqual((done["fetched"], item.status), (1, "fetched"))
        text = (self.dir / item.page).read_text()
        self.assertIn("refused the fetch", text)
        self.assertIn("[bo] The figures solve", text)

    def test_a_refused_page_without_hn_discussion_still_counts_a_failure(self) -> None:
        item = self.add(url="https://journal.example/paper")
        item.move("queued")
        done = scout.fetch(self.ledger, FakeFetcher({"https://journal.example/paper": (403, "text/html", "")}), log=lambda _: None)
        self.assertEqual((done["failed"], item.status), (1, "queued"))

    def test_a_saved_link_survives_the_title_pass_and_is_marked_on_page_and_digest(self) -> None:
        export = self.dir / "raindrop.csv"
        export.write_text("id,title,note,excerpt,url,folder,tags,created,cover,highlights,favorite\n"
                          "1,Life Universe,,,https://oimo.io/works/life/,Unsorted,,2024-03-23T10:00:00Z,,,false\n"
                          "2,Later copy,,,https://oimo.io/works/life,Unsorted,,2025-01-01T10:00:00Z,,,false\n", encoding="utf-8")
        self.assertEqual(scout.import_saves(self.dir, export), (2, 1))
        ledger = scout.Ledger(self.dir)
        self.assertEqual(next(iter(ledger.saves.values()))["saved"], "2024-03-23")
        item, _ = ledger.add("Life Universe", "https://oimo.io/works/life/", {"src": "hn", "hn": "5", "points": 3})
        other, _ = ledger.add("Unrelated", "https://else.example/x", {"src": "hn", "hn": "6", "points": 3})
        self.assertIn("[saved by Marc 2024-03-23]", "\n".join(scout.triage_list(ledger)))
        scout.triage(ledger, {item.key, other.key})
        self.assertEqual((item.status, other.status), ("queued", "dropped"))
        fetcher = FakeFetcher({"https://oimo.io/works/life": (200, "text/html", "<p>" + "cells " * 400 + "</p>")})
        scout.fetch(ledger, fetcher, log=lambda _: None)
        self.assertIn("saved by Marc 2024-03-23", (self.dir / item.page).read_text())
        (self.dir / "cards").mkdir(exist_ok=True)
        (self.dir / "cards" / f"{item.key}.md").write_text(
            "---\nverdict: card\nkind: new\ntype: reimplement\nnearest: none (searched: life)\nlicense: unstated\n---\n"
            "**What it is.** x\n\n**Method.** x\n\n**What Field adds.** x\n\n**Figures.** x\n\n**License.** x\n\n**Size.** A night.\n",
            encoding="utf-8")
        counts, faults = scout.ingest_cards(ledger)
        self.assertEqual(faults, [])
        path = scout.digest(ledger, force=True)
        self.assertIn("**saved by Marc 2024-03-23**", path.read_text())

    def test_three_failures_give_up(self) -> None:
        item = self.add(url="https://down.example/x")
        item.move("queued")
        fetcher = FakeFetcher({})
        for _ in range(3):
            scout.fetch(self.ledger, fetcher, log=lambda _: None)
        self.assertEqual(item.status, "fetch-failed")

    def test_changed_ignores_a_date_but_sees_a_new_section(self) -> None:
        base = " ".join(f"word{i}" for i in range(400))
        self.assertFalse(scout.changed(base + " updated 2026-11-01", base + " updated 2026-11-08"))
        self.assertTrue(scout.changed(base, base + " " + " ".join(f"part two {i}" for i in range(200))))


class WatchPool(Journal):
    CFG = {"first_days": 7, "factor": 2, "max_checks": 5, "pool": 3}

    def watched(self, n: int) -> scout.Item:
        item = self.add(f"Watched {n}", f"https://w.example/{n}", hn=str(n))
        item.page = scout.write_page(self.dir, item, "the same text " * 50, {})
        scout.start_watch(self.ledger, item, "part two promised", self.CFG)
        return item

    def test_backoff_doubles_then_drops_after_max_checks(self) -> None:
        item = self.watched(1)
        intervals = [item.watch["interval"]]
        for _ in range(4):
            self.assertFalse(scout.backoff(item, self.CFG))
            intervals.append(item.watch["interval"])
        self.assertEqual(intervals, [7, 14, 28, 56, 112])
        self.assertTrue(scout.backoff(item, self.CFG))
        self.assertEqual(item.status, "dropped")

    def test_a_full_pool_evicts_the_one_longest_without_news(self) -> None:
        old = self.watched(1)
        scout.backoff(old, self.CFG)
        scout.backoff(old, self.CFG)  # interval 28
        self.watched(2)
        self.watched(3)
        evicted = scout.start_watch(self.ledger, self.add("Fourth", "https://w.example/4", hn="4"), "r", self.CFG)
        self.assertIs(evicted, old)
        self.assertEqual(old.status, "dropped")
        self.assertEqual(len(self.ledger.with_status("watch")), 3)

    def test_a_due_check_with_no_news_backs_off_and_news_brings_it_back(self) -> None:
        item = self.watched(1)
        fetcher = FakeFetcher({"https://w.example/1": (200, "text/plain", "the same text " * 50)})
        self.assertEqual(scout.fetch(self.ledger, fetcher, watch_cfg=self.CFG, log=lambda _: None)["watch-checked"], 0)
        self.clock(T0 + 8 * DAY)
        scout.fetch(self.ledger, fetcher, watch_cfg=self.CFG, log=lambda _: None)
        self.assertEqual((item.status, item.watch["interval"]), ("watch", 14))
        self.clock(T0 + 30 * DAY)
        fetcher.routes["https://w.example/1"] = (200, "text/plain", "part two is here with a whole new method " * 40)
        done = scout.fetch(self.ledger, fetcher, watch_cfg=self.CFG, log=lambda _: None)
        self.assertEqual(done["watch-news"], 1)
        self.assertEqual((item.status, item.watch["interval"]), ("fetched", 7))
        self.assertIn("[watch: page changed]", "\n".join(scout.reading_list(self.ledger)))


CARD = """---
verdict: card
kind: {kind}
type: reimplement
nearest: {nearest}
license: MIT
---
**What it is.** A thing.

**Method.** A method.

**What Field adds.** Liveness.

**Figures.** Figure 1; figure 2; figure 3.

**License.** MIT: copy with attribution.

**Size.** A night.
"""


class Cards(Journal):
    def fetched(self, n: int, points: int = 100) -> scout.Item:
        item = self.add(f"Post {n}", f"https://p.example/{n}", hn=str(n), points=points)
        item.page = scout.write_page(self.dir, item, "text", {})
        item.move("fetched")
        return item

    def card(self, item: scout.Item, text: str) -> None:
        (self.dir / "cards").mkdir(exist_ok=True)
        (self.dir / "cards" / f"{item.key}.md").write_text(text)

    def test_valid_cards_drops_and_watches_are_recorded(self) -> None:
        a, b, c = self.fetched(1), self.fetched(2), self.fetched(3)
        self.card(a, CARD.format(kind="new", nearest="none (searched: x)"))
        self.card(b, "---\nverdict: drop\n---\nGeneric web framework.\n")
        self.card(c, "---\nverdict: watch\nwatch-for: the code release\nkind: new\ntype: paper\nnearest: none\nlicense: unstated\n---\nCode soon.\n")
        counts, faults = scout.ingest_cards(self.ledger)
        self.assertEqual(faults, [])
        self.assertEqual(counts, {"card": 1, "drop": 1, "watch": 1})
        self.assertEqual((a.status, b.status, c.status), ("carded", "dropped", "watch"))
        self.assertEqual(b.note, "Generic web framework.")

    def test_faults_name_what_is_missing(self) -> None:
        a, b = self.fetched(1), self.fetched(2)
        self.card(a, CARD.format(kind="touches", nearest="none").replace("**Figures.** Figure 1; figure 2; figure 3.\n", ""))
        self.card(b, "---\nverdict: maybe\n---\nHm.\n")
        _, faults = scout.ingest_cards(self.ledger)
        text = "\n".join(faults)
        self.assertIn("must name the Field work it touches", text)
        self.assertIn("**Figures**", text)
        self.assertIn("verdict must be one of", text)
        self.assertEqual((a.status, b.status), ("fetched", "fetched"))


class DigestAndVerdicts(Cards):
    def three_cards(self) -> list[scout.Item]:
        items = [self.fetched(1, 50), self.fetched(2, 300), self.fetched(3, 10)]
        self.card(items[0], CARD.format(kind="new", nearest="none"))
        self.card(items[1], CARD.format(kind="new", nearest="none"))
        self.card(items[2], CARD.format(kind="touches", nearest="dev_docs/ALIFE.md"))
        scout.ingest_cards(self.ledger)
        return items

    def test_digest_waits_for_enough_cards_then_groups_them(self) -> None:
        a = self.fetched(9)
        self.card(a, CARD.format(kind="new", nearest="none"))
        scout.ingest_cards(self.ledger)
        self.assertIsNone(scout.digest(self.ledger, min_cards=3))
        self.three_cards()
        path = scout.digest(self.ledger, min_cards=3)
        text = path.read_text()
        self.assertLess(text.index("## New ideas"), text.index("## Touching work already in Field"))
        self.assertLess(text.index("### Post 2"), text.index("### Post 1"))  # by points
        self.assertEqual(text.count("verdict: \n"), 4)
        self.assertEqual(len(self.ledger.with_status("digested")), 4)
        self.assertIsNone(scout.digest(self.ledger, min_cards=1))  # nothing left, and not due

    def test_next_digest_comes_three_days_later_by_default(self) -> None:
        self.three_cards()
        self.assertIsNotNone(scout.digest(self.ledger))
        for n in (4, 5, 6):
            self.card(self.fetched(n), CARD.format(kind="new", nearest="none"))
        scout.ingest_cards(self.ledger)
        self.clock(T0 + 2 * 86400)
        self.assertIsNone(scout.digest(self.ledger))
        self.clock(T0 + 3 * 86400)
        self.assertIsNotNone(scout.digest(self.ledger))

    def test_verdicts_approve_reject_and_later_goes_to_the_watch_pool(self) -> None:
        a, b, c = self.three_cards()
        path = scout.digest(self.ledger, min_cards=3)
        text = path.read_text()
        parts = text.split("verdict: \n")
        # Cards appear Post 2, Post 1, Post 3: answer yes, no, later.
        text = parts[0] + "verdict: yes: with the drawing layer\n" + parts[1] + "verdict: no\n" + parts[2] + "verdict: later\n" + parts[3]
        path.write_text(text)
        counts = scout.collect_verdicts(self.ledger)
        self.assertEqual(counts, {"approved": 1, "rejected": 1, "later": 1})
        self.assertEqual((b.status, a.status, c.status), ("approved", "rejected", "watch"))
        self.assertEqual(b.verdict, "yes: with the drawing layer")
        approved = (self.dir / "approved.md").read_text()
        self.assertIn("## [ ] Post 2", approved)
        self.assertIn("https://p.example/2", approved)
        self.assertIn("with the drawing layer", (self.dir / "SCRATCHPAD.md").read_text())
        self.assertEqual(scout.collect_verdicts(self.ledger), {"approved": 0, "rejected": 0, "later": 0})

    def test_status_names_a_digest_awaiting_verdicts(self) -> None:
        self.three_cards()
        scout.digest(self.ledger, min_cards=3)
        self.ledger.save()
        line = scout.status_line(self.dir)
        self.assertIn("digest awaiting your verdicts", line)
        self.assertIn("3 links", line)


class InitJournal(unittest.TestCase):
    def test_creates_an_orphan_journal_then_attaches_it_again(self) -> None:
        import subprocess

        tmp = Path(tempfile.mkdtemp(prefix="scout-git-"))
        self.addCleanup(shutil.rmtree, tmp, True)
        repo = tmp / "repo"
        repo.mkdir()
        env = {**os.environ, "GIT_AUTHOR_NAME": "t", "GIT_AUTHOR_EMAIL": "t@t", "GIT_COMMITTER_NAME": "t", "GIT_COMMITTER_EMAIL": "t@t"}
        for k in [k for k in env if k in ("GIT_DIR", "GIT_WORK_TREE", "GIT_INDEX_FILE")]:
            del env[k]
        run = lambda *a, cwd=repo: subprocess.run(["git", *a], cwd=cwd, env=env, check=True, capture_output=True)
        run("init", "-q", "-b", "main")
        run("commit", "-q", "--allow-empty", "-m", "root")
        journal = tmp / "wt" / "scout-journal"
        with mock.patch.dict(os.environ, env, clear=True):
            self.assertIn("created", scout.init_journal(journal, repo))
            self.assertEqual((journal / ".gitignore").read_text(), "pages/\n*.tmp\n*.tsv\n")
            run("add", "-A", cwd=journal)
            run("commit", "-q", "-m", "scout: first", cwd=journal)
            self.assertIn("already", scout.init_journal(journal, repo))
            run("worktree", "remove", str(journal))
            self.assertIn("attached", scout.init_journal(journal, repo))
            self.assertTrue((journal / "SCRATCHPAD.md").exists())
            branches = subprocess.run(["git", "branch", "--list"], cwd=repo, env=env, capture_output=True, text=True).stdout
            self.assertIn(scout.JOURNAL_BRANCH, branches)


class TheCheckoutsSources(unittest.TestCase):
    def test_sources_json_parses_and_names_https_feeds(self) -> None:
        sources = scout.load_sources()
        self.assertGreater(len(sources["hn_topics"]["queries"]), 20)
        for f in sources["feeds"]:
            self.assertTrue(f["url"].startswith("https://"), f)
        self.assertEqual(set(scout.WATCH_DEFAULTS), set(sources["watch"]))


if __name__ == "__main__":
    unittest.main()
