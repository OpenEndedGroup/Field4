"""Safe ragged stroke datasets: flat float32 deltas + int64 sequence offsets."""
from pathlib import Path
import xml.etree.ElementTree as ET
import numpy as np
import torch
from torch.utils.data import Dataset


def polyline_deltas(strokes, units_per_model_unit=1.0):
    if not np.isfinite(units_per_model_unit) or units_per_model_unit <= 0:
        raise ValueError('Scale must be positive')
    rows, previous = [], None
    for stroke in strokes:
        a = np.asarray(stroke, dtype=np.float64)
        if a.size == 0:
            continue
        if a.ndim != 2 or a.shape[1] != 2 or not np.isfinite(a).all():
            raise ValueError('Each stroke must be finite [N,2]')
        for i, xy in enumerate(a):
            d = np.zeros(2) if previous is None else (xy-previous)/units_per_model_unit
            rows.append([*d, float(i == len(a)-1)])
            previous = xy
    if len(rows) < 2:
        raise ValueError('Need at least two points')
    return np.asarray(rows, dtype=np.float32)


def iam_line(path, scale=20.0):
    root = ET.parse(path).getroot()
    strokes = [[[float(p.attrib['x']), float(p.attrib['y'])] for p in s.findall('Point')]
               for s in root.findall('.//StrokeSet/Stroke')]
    return polyline_deltas(strokes, scale)


def save_dataset(sequences, path, sense=None):
    """Write ragged sequences to one NPZ. `sense` is `(sectors, radius, recent)` when the rows carry
    sense columns after `[dx, dy, lift]` (see `fieldpen.sense`), so training can check them."""
    seq = [np.asarray(s, dtype=np.float32) for s in sequences]
    if not seq:
        raise ValueError('No sequences')
    offsets = np.r_[0, np.cumsum([len(s) for s in seq])].astype(np.int64)
    extra = {} if sense is None else dict(sense=np.asarray(sense, dtype=np.float64))
    np.savez_compressed(path, deltas=np.concatenate(seq), offsets=offsets, **extra)


class StrokeDataset(Dataset):
    def __init__(self, path, limit=2048):
        with np.load(path, allow_pickle=False) as d:
            self.deltas = d['deltas'].astype(np.float32)
            self.offsets = d['offsets'].astype(np.int64)
            # (sectors, radius, recent) for rows that carry what a sensing pen sees; None for plain rows
            self.sense = tuple(d['sense'].tolist()) if 'sense' in d else None
        x, o = self.deltas, self.offsets
        # sense rows: the sectors, then the two values pointing at the middle of the ink
        width = 3 + (int(self.sense[0]) + 2 if self.sense else 0)
        if (x.ndim != 2 or x.shape[1] != width or not np.isfinite(x).all() or
            not np.isin(x[:, 2], [0,1]).all() or o.ndim != 1 or len(o)<2 or
            o[0] != 0 or o[-1] != len(x) or np.any(np.diff(o)<2)):
            raise ValueError('Invalid ragged stroke dataset')
        if limit < 2:
            raise ValueError('limit >= 2 required')
        # Keep sequence starts: no false zero-state initialization mid-line.
        # Longer sequences are truncated, explicitly; increase limit as needed.
        self.limit = limit

    def __len__(self):
        return len(self.offsets)-1

    def __getitem__(self, i):
        a, b = self.offsets[i:i+2]
        return torch.from_numpy(self.deltas[a:min(b, a+self.limit)].copy())


def collate(batch):
    lengths = torch.tensor([len(s) for s in batch])
    target = torch.nn.utils.rnn.pad_sequence(batch, batch_first=True)
    x = torch.zeros_like(target)
    x[:, 1:] = target[:, :-1]
    mask = torch.arange(target.shape[1])[None] < lengths[:, None]
    return x, target, mask


class CropDataset(Dataset):
    """Random windows of `length` points from a ragged stroke dataset, `samples` of them per epoch.

    Each item is `length + 1` rows: the row before the window (a zero row when the window starts
    a sequence, the same start-of-sequence input the model always sees first), then the window.
    `collate_crops` turns that into inputs and targets. Windows start anywhere, mid-stroke
    included, which is how a pen continuing a line meets its data; shorter sequences are used whole.
    """
    def __init__(self, path, length=256, samples=4096, seed=0):
        base = StrokeDataset(path)
        self.deltas, self.offsets, self.sense = base.deltas, base.offsets, base.sense
        self.length, self.samples = length, samples
        self.rng = np.random.default_rng(seed)
        lengths = np.diff(self.offsets)
        self.weights = lengths / lengths.sum()

    def __len__(self):
        return self.samples

    def __getitem__(self, _):
        i = self.rng.choice(len(self.weights), p=self.weights)
        a, b = self.offsets[i], self.offsets[i + 1]
        n = min(self.length, b - a)
        start = int(self.rng.integers(a, b - n + 1))
        window = self.deltas[start:start + n]
        previous = self.deltas[start - 1:start] if start > a else np.zeros((1, self.deltas.shape[1]), np.float32)
        return torch.from_numpy(np.concatenate([previous, window]).copy())


def collate_crops(batch):
    lengths = torch.tensor([len(s) - 1 for s in batch])
    rows = torch.nn.utils.rnn.pad_sequence(batch, batch_first=True)
    x, target = rows[:, :-1].clone(), rows[:, 1:].clone()
    mask = torch.arange(target.shape[1])[None] < lengths[:, None]
    return x, target, mask
