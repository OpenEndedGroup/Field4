"""Train/fine-tune on separate train and validation ragged NPZ files."""
import argparse
from dataclasses import asdict
import json
from pathlib import Path
import random
import torch
from torch.utils.data import DataLoader
from .data import CropDataset, StrokeDataset, collate, collate_crops
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
    p.add_argument('--crop', type=int, default=0,
                   help='Train on random windows of this many points instead of whole sequences')
    p.add_argument('--samples', type=int, default=4096, help='Windows per epoch, with --crop')
    p.add_argument('--burn-in', type=int, default=16,
                   help='With --crop, leave the first points of each window out of the loss')
    p.add_argument('--input-noise', type=float, default=0.0,
                   help='Gaussian noise (model units) added to input displacements while training')
    a = p.parse_args()
    if Path(a.train).resolve() == Path(a.valid).resolve():
        p.error('Use a genuinely held-out validation file')
    torch.set_num_threads(a.threads)
    torch.manual_seed(a.seed)
    random.seed(a.seed)
    m = (load_model(a.init, a.device) if a.init else
         HandwritingModel(Config(a.hidden, a.mixtures, a.layers)).to(a.device))
    train_data = (CropDataset(a.train, a.crop, a.samples, a.seed) if a.crop
                  else StrokeDataset(a.train, a.limit))
    loaders = [DataLoader(train_data, batch_size=a.batch, shuffle=not a.crop,
                          collate_fn=collate_crops if a.crop else collate),
               DataLoader(StrokeDataset(a.valid, a.limit), batch_size=a.batch, collate_fn=collate)]
    opt = torch.optim.AdamW(m.parameters(), lr=a.lr, weight_decay=1e-4)
    sched = torch.optim.lr_scheduler.CosineAnnealingLR(opt, T_max=max(1, a.epochs * len(loaders[0])))
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
                    if phase == 'train' and a.input_noise > 0:
                        x = x.clone()
                        x[..., :2] += a.input_noise * torch.randn_like(x[..., :2])
                    if phase == 'train' and a.crop and a.burn_in > 0:
                        mask = mask.clone()
                        mask[:, :a.burn_in] = False
                    raw, _ = m(x)
                    loss = m.nll(raw, y)[mask].mean()
                    if not torch.isfinite(loss):
                        raise FloatingPointError('Non-finite loss')
                    if phase == 'train':
                        opt.zero_grad(set_to_none=True)
                        loss.backward()
                        torch.nn.utils.clip_grad_norm_(m.parameters(), 1., error_if_nonfinite=True)
                        opt.step()
                        sched.step()
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
