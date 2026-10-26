"""Train/fine-tune on separate train and validation ragged NPZ files."""
import argparse
from dataclasses import asdict
import json
from pathlib import Path
import random
import torch
from torch.utils.data import DataLoader
from .data import StrokeDataset, collate
from .model import Config, HandwritingModel, load_model, save_model


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--train', required=True)
    p.add_argument('--valid', required=True)
    p.add_argument('--out', required=True)
    p.add_argument('--init', help='Warm-start model weights; optimizer starts fresh')
    p.add_argument('--device', default='cuda' if torch.cuda.is_available() else 'cpu')
    p.add_argument('--epochs', type=int, default=30)
    p.add_argument('--batch', type=int, default=16)
    p.add_argument('--limit', type=int, default=2048)
    p.add_argument('--hidden', type=int, default=500)
    p.add_argument('--layers', type=int, default=1)
    p.add_argument('--mixtures', type=int, default=20)
    p.add_argument('--lr', type=float, default=1e-4)
    p.add_argument('--seed', type=int, default=42)
    p.add_argument('--threads', type=int, default=1)
    a = p.parse_args()
    if Path(a.train).resolve() == Path(a.valid).resolve():
        p.error('Use a genuinely held-out validation file')
    torch.set_num_threads(a.threads)
    torch.manual_seed(a.seed)
    random.seed(a.seed)
    m = (load_model(a.init, a.device) if a.init else
         HandwritingModel(Config(a.hidden, a.mixtures, a.layers)).to(a.device))
    loaders = [DataLoader(StrokeDataset(path, a.limit), batch_size=a.batch,
                          shuffle=(i == 0), collate_fn=collate)
               for i, path in enumerate([a.train, a.valid])]
    opt = torch.optim.AdamW(m.parameters(), lr=a.lr, weight_decay=1e-4)
    out = Path(a.out)
    out.mkdir(parents=True, exist_ok=True)
    best = float('inf')
    for epoch in range(a.epochs):
        metrics = {}
        for phase, loader in zip(['train', 'valid'], loaders):
            m.train(phase == 'train')
            total, count = 0., 0
            with torch.set_grad_enabled(phase == 'train'):
                for x, y, mask in loader:
                    x, y, mask = x.to(a.device), y.to(a.device), mask.to(a.device)
                    raw, _ = m(x)
                    loss = m.nll(raw, y)[mask].mean()
                    if not torch.isfinite(loss):
                        raise FloatingPointError('Non-finite loss')
                    if phase == 'train':
                        opt.zero_grad(set_to_none=True)
                        loss.backward()
                        torch.nn.utils.clip_grad_norm_(m.parameters(), 1., error_if_nonfinite=True)
                        opt.step()
                    n = int(mask.sum())
                    total += float(loss.detach())*n
                    count += n
            metrics[phase] = total/count
        meta = dict(epoch=epoch+1, metrics=metrics, args=vars(a), config=asdict(m.config))
        save_model(m, out/'last.pt', meta)
        if metrics['valid'] < best:
            best = metrics['valid']
            save_model(m, out/'best.pt', meta)
        with (out/'metrics.jsonl').open('a') as f:
            f.write(json.dumps(meta)+'\n')
        print(json.dumps(meta), flush=True)


if __name__ == '__main__':
    main()
