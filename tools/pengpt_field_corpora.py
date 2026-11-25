"""Train PenGPT on Field's own corpora: convert them to PenGPT's stroke files, and pack the result.

    python tools/pengpt_field_corpora.py convert --repo ~/pengpt --out field corpora/*.jsonl
    python ~/pengpt/pengpt.py train --preset quickdraw --dataset field/hershey.sketch.parquet,... ...
    python tools/pengpt_field_corpora.py export --repo ~/pengpt --checkpoint out/field/best.pt --out field-pens.npz

`convert` reads what `PenGpt.writeCorpus` writes, one JSON line per drawing with its points in the
drawing model's frame, and writes `<name>.sketch.parquet` for each file, `<name>` being the file's
name without `.jsonl`. PenGPT takes each file as one source, so a model trained on several files
learns each corpus as a source of its own; every drawing is also labelled with that name.

`export` packs a checkpoint as `inference/pen.py export` does, but keeps the weights in float16
rather than 8 bits, and records in the meta whether the model writes (`"kind": "hand"`) or draws
(`"sketch"`): a drawing model told text labels is format 1, as handwriting is, and `PenGpt` reads
`kind` to tell them apart. `PenGpt.load` and `PenGpt.named` read the file.

Both need PenGPT's own code (`--repo`, a checkout of https://github.com/greydanus/pengpt).
"""

import argparse
import json
import os
import sys

import numpy as np


def convert(a):
    from strokes import normalize, save_strokes, to_strokes

    os.makedirs(a.out, exist_ok=True)
    for path in a.corpora:
        name = os.path.basename(path).removesuffix(".jsonl")
        records = []
        for i, line in enumerate(open(path)):
            r = json.loads(line)
            strokes = normalize(to_strokes(r["points"]))
            if strokes is not None:
                records.append(dict(id=f"{name}-{i}", label=r.get("label", name), strokes=strokes))
        target = os.path.join(a.out, f"{name}.sketch.parquet")
        save_strokes(records, target)
        print(f"{name}: {len(records)} drawings -> {target}", flush=True)


def export(a):
    import torch

    c = torch.load(a.checkpoint, map_location="cpu", weights_only=True)
    m, d = c["model_config"], c["data_config"]
    arrays = {}
    for name, w in c["model"].items():
        name, w = name.replace("_orig_mod.", "").replace("source_emb.", "writer_emb."), w.float().numpy()
        if name != "pen_deltas":
            arrays[name] = w.astype(np.float16) if w.ndim == 2 else w.astype(np.float32)
    kind = "sketch" if all(p.split(":")[0].strip().endswith(".sketch.parquet") for p in d["dataset"].split(",")) else "hand"
    meta = dict(format=2 if m.get("context_slots") else 1, kind=kind, vector=m.get("context_width", 0), vectors=d.get("vectors", ""),
                n_layer=m["n_layer"], n_head=m["n_head"], bands=m["pen_pos_bands"], block_size=m["block_size"],
                pen_rotary=bool(m.get("pen_rotary", False)), grid=d["grid"], max_text_length=d["max_text_length"],
                text_end=bool(d.get("text_end", False)), max_seq_length=d["max_seq_length"], step=c["step"],
                label_drop=float(d.get("label_drop", 0)), hint=float(d.get("hint", 0)))
    np.savez_compressed(a.out, meta=json.dumps(meta), merges=np.array(c["merges"], dtype=np.int16).reshape(-1, 3),
                        writers=np.array(c.get("sources", c.get("writers"))), **arrays)
    print(f"wrote {a.out}: {kind}, format {meta['format']}, sources {list(c.get('sources', []))}, step {c['step']}")


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    sub = ap.add_subparsers(dest="command", required=True)
    cv = sub.add_parser("convert", help="Field corpora (.jsonl) to PenGPT stroke files (.sketch.parquet)")
    cv.add_argument("--repo", required=True)
    cv.add_argument("--out", required=True, help="the folder to write the .sketch.parquet files into")
    cv.add_argument("corpora", nargs="+")
    ex = sub.add_parser("export", help="a trained checkpoint to a float16 .npz for PenGpt.load")
    ex.add_argument("--repo", required=True)
    ex.add_argument("--checkpoint", required=True)
    ex.add_argument("--out", required=True)
    a = ap.parse_args()
    sys.path.insert(0, a.repo)
    {"convert": convert, "export": export}[a.command](a)


if __name__ == "__main__":
    main()
