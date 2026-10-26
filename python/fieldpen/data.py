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


def save_dataset(sequences, path):
    seq = [np.asarray(s, dtype=np.float32) for s in sequences]
    if not seq:
        raise ValueError('No sequences')
    offsets = np.r_[0, np.cumsum([len(s) for s in seq])].astype(np.int64)
    np.savez_compressed(path, deltas=np.concatenate(seq), offsets=offsets)


class StrokeDataset(Dataset):
    def __init__(self, path, limit=2048):
        with np.load(path, allow_pickle=False) as d:
            self.deltas = d['deltas'].astype(np.float32)
            self.offsets = d['offsets'].astype(np.int64)
        x, o = self.deltas, self.offsets
        if (x.ndim != 2 or x.shape[1] != 3 or not np.isfinite(x).all() or
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
