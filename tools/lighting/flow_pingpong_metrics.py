"""Acceptance metrics for the NVIDIA ping-pong fixture, not a general flow confidence model."""
from math import hypot, isfinite

BORDER = 4  # flow texels = 16 input pixels; unchanged from the original check
COST_LIMIT = 64  # NV raw matching cost, specific to this fixture/backend
OUTLIER_PIXELS = 5.0


def quantile(values, fraction):
    values = sorted(values)
    return values[min(len(values) - 1, int(len(values) * fraction))] if values else None


def measure(pixels, width, height, expected, stationary, difference):
    """All XY errors are Euclidean distances in input pixels. Never drop bad-cost samples silently."""
    if len(pixels) != width * height * 4 or width <= 2 * BORDER or height <= 2 * BORDER:
        raise ValueError("Wrong motion texture dimensions")
    if not all(isfinite(v) for v in pixels) or not all(isfinite(v) for v in (*expected, difference)):
        return {"passed": False, "failures": ["Non-finite flow, cost, expected motion or source difference"]}
    if any(pixels[i + 2] < 0 or pixels[i + 3] != 1 for i in range(0, len(pixels), 4)):
        return {"passed": False, "failures": ["Invalid cost or unpacked W channel"]}
    speeds = [hypot(pixels[i], pixels[i + 1]) for i in range(0, len(pixels), 4)]
    errors, selected, bad_positions, aliases = [], [], [], {}
    for y in range(BORDER, height - BORDER):
        for x in range(BORDER, width - BORDER):
            i = (y * width + x) * 4
            dx, dy = pixels[i] - expected[0], pixels[i + 1] - expected[1]
            e = hypot(dx, dy)
            errors.append(e)
            if pixels[i + 2] <= COST_LIMIT:
                selected.append(e)
            if e > OUTLIER_PIXELS:
                bad_positions.append((x, y))
                # Diagnostic grouping only: aliases include deviations from exact cell offsets.
                cell = f"{round(dx / 34)},{round(dy / 34)}"
                aliases[cell] = aliases.get(cell, 0) + 1
    result = {
        "population": len(errors), "borderFlowTexels": BORDER,
        "medianEpe": quantile(errors, .5), "p95Epe": quantile(errors, .95), "maxEpe": max(errors),
        "outlierPixels": OUTLIER_PIXELS, "outlierCount": len(bad_positions),
        "outlierFraction": len(bad_positions) / len(errors),
        "costLimit": COST_LIMIT, "costSelectedCount": len(selected),
        "costCoverage": len(selected) / len(errors), "costSelectedP95Epe": quantile(selected, .95),
        "costSelectedOutlierFraction": sum(e > OUTLIER_PIXELS for e in selected) / len(selected) if selected else None,
        "rounded34PixelAliasCounts": dict(sorted(aliases.items(), key=lambda item: -item[1])[:8]),
        "fullFieldSpeedMedian": quantile(speeds, .5), "fullFieldSpeedP95": quantile(speeds, .95),
        "fullFieldSpeedMax": max(speeds), "sourceMaxDifference": difference,
    }
    failures = []
    if stationary:
        if not (quantile(speeds, .5) < .125 and quantile(speeds, .95) < .25 and max(speeds) < .5):
            failures.append("Paused full field contains motion")
        if difference != 0 or tuple(expected) != (0, 0):
            failures.append("Paused inputs or analytical motion differ")
    else:
        if difference <= .1:
            failures.append("Moving source images did not change")
        if result["medianEpe"] >= .5:
            failures.append("Raw interior median EPE >= 0.5 px")
        # Keep the original accuracy requirement. Cost is diagnostic only: even a
        # low-cost match can lock onto a repeated cell in an ambiguous source.
        if result["p95Epe"] >= 1:
            failures.append("Raw interior p95 EPE >= 1 px")
    result.update(passed=not failures, failures=failures)
    return result
