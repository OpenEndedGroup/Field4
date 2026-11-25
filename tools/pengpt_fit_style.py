"""Fit PenGPT to a corpus Field wrote: a source vector, the whole context, or every weight.

    python tools/pengpt_fit_style.py --repo ~/pengpt --checkpoint sketch.pt \
        --train michaux_train.jsonl --valid michaux_valid.jsonl --out michaux.style.json

The corpus is what `PenGpt.writeCorpus` writes: one JSON line per drawing, its points in the model's
frame and the tokens Field's encoder made of them. This script needs PenGPT's own code (`--repo`, a
checkout of https://github.com/greydanus/pengpt) and torch; a GPU makes it minutes rather than hours.

`--mode vector` (the default) does what PenGPT's `inference/hand.py` does for a hand from a photo,
for any corpus and either model: every weight stays as trained and only the vector in the source
slot of the cross-attention context moves, by Adam on the next-token loss. `--mode context` frees
every row the decoder cross-attends to (the source slot and the four slots a prompt fills), still
with the weights frozen. `--mode full` fine-tunes every weight along with the source vector, and
also writes `--npz`, the release's `.npz` with the new weights in float16; the fitted vector
replaces the anonymous source, so an empty prompt draws in the new manner. Copy it into Field's
PenGPT folder (`~/.afield/onnx/models/pengpt/`) as `<name>.npz` and `PenGpt.named("<name>")` loads
it. On one RTX 4090, 2,000 steps of 16 Michaux drawings take about three minutes.
Before fitting it reports:

* whether PenGPT's tokenizer, given Field's points, makes the same tokens Field did;
* the held-out loss (nats per token) under each source the model already knows, the best of which
  starts the fit.

It writes the fitted vector with the held-out loss before and after, and the loss of the model's
own unprompted drawings for comparison. `PenGptPrompt(style = ...)` takes the vector in Field.
"""

import argparse
import json
import math
import sys
import time

import numpy as np


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--repo", required=True, help="a checkout of greydanus/pengpt")
    ap.add_argument("--checkpoint", required=True, help="sketch.pt or hand.pt from the PenGPT release")
    ap.add_argument("--train", required=True)
    ap.add_argument("--valid", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--steps", type=int, default=600)
    ap.add_argument("--lr", type=float, default=0.03)
    ap.add_argument("--batch", type=int, default=16)
    ap.add_argument("--init", default="best", help="'best' (the known source that fits best), a source name, or 'zero'")
    ap.add_argument("--text", default="", help="the prompt the drawings are fitted under; empty for none")
    ap.add_argument("--seed", type=int, default=0)
    ap.add_argument("--mode", choices=["vector", "context", "full"], default="vector")
    ap.add_argument("--weight-lr", type=float, default=1e-4, help="--mode full: AdamW learning rate for the weights")
    ap.add_argument("--npz", default="", help="--mode full: the release .npz to copy, and where to write the fine-tuned one (in,out)")
    ap.add_argument("--valid-every", type=int, default=50)
    a = ap.parse_args()
    sys.stdout.reconfigure(line_buffering=True)  # progress shows in a log as it happens

    sys.path.insert(0, a.repo)
    import torch
    import torch.nn.functional as F
    import pengpt as P

    torch.manual_seed(a.seed)
    dev = "cuda" if torch.cuda.is_available() else "cpu"
    model, ds, cfg, ckpt = P.load_for_sampling(a.checkpoint, dev)
    model.eval().requires_grad_(False)
    st, sources = ds.stroke_tok, list(ckpt["sources"])
    hd = model.cfg.n_embd // model.cfg.n_head
    ctx0 = ds.encode_text(a.text)

    def read(path):
        return [json.loads(line) for line in open(path)]

    train, valid = read(a.train), read(a.valid)

    # Field's tokens against PenGPT's, from the same points
    same, first_diff = 0, None
    checked = [r for r in train + valid if "points" in r]
    for r in checked:
        py = [int(t) for t in st.encode_word(np.array(r["points"], float))]
        if py == r["tokens"]:
            same += 1
        elif first_diff is None:
            k = next((i for i, (p, q) in enumerate(zip(py, r["tokens"])) if p != q), min(len(py), len(r["tokens"])))
            first_diff = dict(at=k, pengpt=py[max(0, k - 3):k + 5], field=r["tokens"][max(0, k - 3):k + 5], lengths=(len(py), len(r["tokens"])))
    print(f"tokenizer parity: {same}/{len(checked)} drawings identical" + (f"; first difference {first_diff}" if first_diff else ""))

    def batch(rows):
        xs, ys = zip(*[ds.make_item(np.array(r["tokens"], np.int64), ctx0)[::2] for r in rows])
        X, Y = torch.stack(xs).to(dev), torch.stack(ys).to(dev)
        n = int((X != st.PAD).sum(1).max())
        return X[:, :n], Y[:, :n]

    C = torch.from_numpy(np.asarray(ctx0)).to(dev)[None]
    if C.dtype == torch.float64:
        C = C.float()

    rows = None  # --mode context: the four prompt rows, learned

    def loss(X, Y, v):
        xy = model.pen_deltas[X].cumsum(1)
        n = X.size(1)
        rotary = P.pen_rotary_tables(torch.arange(n, device=dev), xy, hd) if model.cfg.pen_rotary else P.rotary_tables(n, hd, dev)
        c, mask = model.encode_context(C.expand(len(X), *C.shape[1:]))
        if rows is not None:
            c = torch.cat([P.rms_norm(v).expand(len(X), 1, -1), P.rms_norm(rows).expand(len(X), -1, -1)], 1)
            mask = torch.ones(len(X), 1, 1, c.size(1), dtype=torch.bool, device=dev)
        else:
            c = torch.cat([P.rms_norm(v).expand(len(X), 1, -1), c[:, 1:]], 1)
        with torch.autocast(dev, dtype=torch.bfloat16, enabled=dev == "cuda"):
            logits = model.logits(model.embed(X, xy), c, mask, rotary)
        return F.cross_entropy(logits.float().transpose(1, 2), Y, ignore_index=P.IGNORE_INDEX)

    @torch.no_grad()
    def held_out(v, rows=valid):
        total, count = 0.0, 0
        for i in range(0, len(rows), a.batch):
            X, Y = batch(rows[i:i + a.batch])
            k = int((Y != P.IGNORE_INDEX).sum())
            total += loss(X, Y, v).item() * k
            count += k
        return total / count

    table = model.source_emb.weight
    known = {name: held_out(table[i]) for i, name in enumerate(sources)}
    for name, nll in sorted(known.items(), key=lambda kv: kv[1]):
        print(f"  held out under source {name or '(anyone)'!r}: {nll:.4f} nats/token")
    start = min(known, key=known.get) if a.init == "best" else a.init
    v = (torch.zeros_like(table[0]) + 1e-3 if start == "zero" else table[sources.index(start)].clone()).requires_grad_(True)
    print(f"fitting from {start or '(anyone)'!r}: {a.steps} steps of {a.batch}, lr {a.lr}, on {dev}")

    params = [v]
    if a.mode == "context":
        # start the prompt rows where an empty prompt would put them, a little apart
        rows = (model.ctx_slot.weight.view(4, -1).clone() + 0.01 * torch.randn(4, table.size(1), device=dev)).requires_grad_(True)
        params.append(rows)
    groups = [dict(params=params, lr=a.lr)]
    if a.mode == "full":
        model.requires_grad_(True)
        groups.append(dict(params=[p for p in model.parameters() if p.requires_grad], lr=a.weight_lr, weight_decay=0.0))
    opt = torch.optim.AdamW(groups, weight_decay=0.0)
    sched = torch.optim.lr_scheduler.LambdaLR(opt, lambda s: min(1.0, (s + 1) / 20) * 0.5 * (1 + math.cos(math.pi * min(s, a.steps) / a.steps)))
    rng, t0, history = np.random.default_rng(a.seed), time.time(), []
    for step in range(a.steps):
        X, Y = batch([train[i] for i in rng.choice(len(train), a.batch, replace=False)])
        opt.zero_grad()
        l = loss(X, Y, v)
        l.backward()
        opt.step()
        sched.step()
        if step % a.valid_every == 0 or step == a.steps - 1:
            h = held_out(v)
            history.append(dict(step=step, train=l.item(), valid=h))
            print(f"  step {step:4d}  train {l.item():.4f}  held out {h:.4f}  ({time.time() - t0:.0f}s)")

    model.eval()
    after = held_out(v)
    if a.mode == "full" and a.npz:
        source, target = a.npz.split(",")
        z = np.load(source)
        arrays = {k: z[k] for k in z.files}
        state = {k.replace("source_emb.", "writer_emb."): t.detach().float().cpu().numpy() for k, t in model.state_dict().items() if k != "pen_deltas"}
        state["writer_emb.weight"][0] = v.detach().float().cpu().numpy()
        for k, w in state.items():
            if k + ".scale" in arrays:
                del arrays[k + ".scale"]
            arrays[k] = w.astype(np.float16) if w.ndim == 2 else w.astype(np.float32)
        np.savez_compressed(target, **arrays)
        print(f"wrote {target}")
    result = dict(
        checkpoint=a.checkpoint.split("/")[-1], text=a.text, start=start, steps=a.steps, lr=a.lr,
        train_drawings=len(train), valid_drawings=len(valid), tokenizer_parity=[same, len(checked)], mode=a.mode,
        held_out_before=known[start] if start in known else None, held_out_after=after, known=known, history=history,
        vector=[round(float(x), 6) for x in v.detach().cpu()],
        context_rows=None if rows is None else [[round(float(x), 6) for x in r] for r in rows.detach().cpu()],
    )
    json.dump(result, open(a.out, "w"), indent=1)
    print(f"held out {result['held_out_before']} -> {after:.4f} nats/token; wrote {a.out}")


if __name__ == "__main__":
    main()
