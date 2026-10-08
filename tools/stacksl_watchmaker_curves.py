"""Plot a StackSL watchmaker trial over time: the search's own score and the held-out resemblance.

Reads the CSV `StackSLWatchmakerTrial --curves` writes: every new leader of every run, with the
renders it took, its skill under one fixed referee (LPIPS at 32 px over the whole picture) and its
layout agreement at 128 px. The searches' own scores change scale when their schedule moves to a new
resolution, so they are not plotted. Both panels are step functions of programs rendered, the
search's budget: the median over runs of the current leader, with the middle half shaded.

    uv run --with matplotlib python tools/stacksl_watchmaker_curves.py --curves <curves.csv> --out <figure.png>
"""
import argparse
import csv

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt
import numpy as np

# Dark-mode categorical slots 1-4 of the dataviz reference palette, validated on the figure surface.
SURFACE = "#141414"
TEXT = "#ffffff"
TEXT2 = "#c3c2b7"
GRID = "#2e2e2c"
ARMS = [
    ("wide", "no model", "#3987e5"),
    ("watchmaker", "watchmaker, one look", "#d95926"),
    ("revise", "with the second look", "#199e70"),
    ("frequent", "every 4 generations", "#c98500"),
]
GRIDX = np.arange(0, 1251, 10)


def step(points, xs, before=np.nan):
    """The last value at or before each x: a best-so-far, or the current leader's."""
    points = sorted(points)
    out, j, cur = [], 0, before
    for x in xs:
        while j < len(points) and points[j][0] <= x:
            cur = points[j][1]
            j += 1
        out.append(cur)
    return np.array(out, dtype=float)


def band(ax, runs, colour, label):
    a = np.vstack(runs)
    lo, med, hi = (np.nanpercentile(a, q, axis=0) for q in (25, 50, 75))
    ax.fill_between(GRIDX, lo, hi, color=colour, alpha=0.14, linewidth=0, step="post")
    ax.plot(GRIDX, med, color=colour, linewidth=2, label=label, drawstyle="steps-post")
    return med


def style(ax, title, ylabel):
    ax.set_facecolor(SURFACE)
    ax.set_title(title, color=TEXT, fontsize=12, loc="left", pad=10)
    ax.set_xlabel("programs rendered", color=TEXT2)
    ax.set_ylabel(ylabel, color=TEXT2)
    ax.tick_params(colors=TEXT2)
    for s in ax.spines.values():
        s.set_visible(False)
    ax.grid(True, color=GRID, linewidth=0.8)
    ax.set_xlim(0, 1250)


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--curves", required=True, help="CSV from StackSLWatchmakerTrial --curves")
    p.add_argument("--out", required=True)
    p.add_argument("--targets", default="spot,rings,bands,checker,quadrants")
    a = p.parse_args()
    targets = a.targets.split(",")

    runs = {arm: {} for arm, _, _ in ARMS}
    with open(a.curves) as fh:
        for r in csv.DictReader(fh):
            if r["target"] in targets and r["arm"] in runs:
                runs[r["arm"]].setdefault((r["target"], r["seed"]), []).append(
                    (int(r["renders"]), float(r["skill"]), float(r["layout"])))

    fig, (left, right) = plt.subplots(1, 2, figsize=(12, 4.6), facecolor=SURFACE)
    finals = []
    for arm, label, colour in ARMS:
        band(left, [step([(x, s) for x, s, _ in v], GRIDX) for v in runs[arm].values()], colour, label)
        med = band(right, [step([(x, l) for x, _, l in v], GRIDX) for v in runs[arm].values()], colour, label)
        finals.append((med[-1], colour))
    style(left, "Score: the leader's skill", "skill under LPIPS (0 = a flat fill, 1 = exact)")
    style(right, "Resemblance: the leader's layout agreement", "layout agreement at 128 px")
    left.set_ylim(-0.1, 1.02)
    right.set_ylim(-0.25, 1.02)
    # End values beside the resemblance panel, nudged apart where they would overlap.
    placed = []
    for value, colour in sorted(finals, reverse=True):
        y = value
        while any(abs(y - q) < 0.06 for q in placed):
            y -= 0.06
        placed.append(y)
        right.annotate("●", (1250, y), xytext=(6, 0), textcoords="offset points",
                       color=colour, fontsize=9, va="center", annotation_clip=False)
        right.annotate(f"{value:.2f}", (1250, y), xytext=(16, 0), textcoords="offset points",
                       color=TEXT2, fontsize=9, va="center", annotation_clip=False)
    leg = left.legend(loc="lower right", frameon=False, fontsize=9)
    for t in leg.get_texts():
        t.set_color(TEXT)
    n = len(next(iter(runs.values())))
    fig.text(0.01, 0.01, f"median of {n} runs per search ({len(targets)} targets, 3 seeds); shaded: the middle half of the runs",
             color=TEXT2, fontsize=8)
    fig.tight_layout(rect=(0, 0.04, 0.96, 1))
    fig.savefig(a.out, dpi=150, facecolor=SURFACE)
    print(f"wrote {a.out}")


if __name__ == "__main__":
    main()
