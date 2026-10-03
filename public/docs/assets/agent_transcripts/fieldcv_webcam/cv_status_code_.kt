# 3 · what's running: re-run to refresh
r = p.latest
print(p, "error:" if p.error else "", p.error or "")
print("ms per detector:", {k: round(v, 1) for k, v in r["timings"].items()})
print(f"{r['width']}x{r['height']} frames, "
      f"{len(r.get('poses', []))} people, {len(r.get('faces', []))} faces, "
      f"hands: {r.get('handedness', [])}, {len(r.get('tracks', []))} tracks, {len(strokes)} strokes")
