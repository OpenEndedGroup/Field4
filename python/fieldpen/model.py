from __future__ import annotations

import base64
from dataclasses import asdict, dataclass
import json
import math
from pathlib import Path

import numpy as np
import torch
from torch import nn
from torch.nn import functional as F


@dataclass(frozen=True)
class Config:
    hidden: int = 500
    mixtures: int = 20
    layers: int = 1


class HandwritingModel(nn.Module):
    """Inputs: (dx, dy, lift_after_endpoint). Output: Bernoulli + 2D GMM.

    nn.LSTM uses i,f,g,o ordering. The published TensorFlow/JS model uses
    i,g,f,o and adds 1 to the forget gate; see import_distill().
    """
    def __init__(self, config: Config = Config()):
        super().__init__()
        self.config = config
        self.rnn = nn.LSTM(3, config.hidden, config.layers, batch_first=True)
        self.head = nn.Linear(config.hidden, 1 + 6 * config.mixtures)

    def zero_state(self, batch=1):
        p = next(self.parameters())
        z = p.new_zeros(self.config.layers, batch, self.config.hidden)
        return z, z.clone()

    def forward(self, x, state=None):
        y, state = self.rnn(x, state)
        return self.head(y), state

    def parameters_for(self, raw):
        k = self.config.mixtures
        pi, mx, my, sx, sy, rho = raw[..., 1:].split(k, dim=-1)
        return dict(pen_logit=raw[..., 0], logits=pi,
                    mean=torch.stack((mx, my), -1),
                    log_sigma=torch.stack((sx, sy), -1).clamp(-9, 7),
                    rho=rho.tanh().clamp(-0.9999, 0.9999))

    def nll(self, raw, target):
        """Per-token negative log likelihood, evaluated in float32 or higher."""
        p = self.parameters_for(raw.float() if raw.dtype in (torch.float16, torch.bfloat16) else raw)
        u = (target[..., None, :2] - p['mean']) * (-p['log_sigma']).exp()
        r = p['rho']
        v = 1 - r.square()
        q = (u[..., 0].square() + u[..., 1].square() - 2*r*u[..., 0]*u[..., 1]) / v
        log_normal = -math.log(2*math.pi) - p['log_sigma'].sum(-1) - 0.5*v.log() - 0.5*q
        spatial = -torch.logsumexp(p['logits'].log_softmax(-1) + log_normal, -1)
        return spatial + F.binary_cross_entropy_with_logits(p['pen_logit'], target[..., 2], reduction='none')

    def distribution(self, state):
        return self.parameters_for(self.head(state[0][-1]))

    @staticmethod
    def sample(p, generator, mixture_temperature=0.65, noise_scale=0.65, pen_bias=0.0):
        # Temperature on component weights; noise_scale multiplies standard
        # deviations (the Distill slider used T for BOTH of these).
        idx = torch.multinomial((p['logits']/mixture_temperature).softmax(-1), 1,
                                generator=generator)
        take = idx[..., None].expand(-1, -1, 2)
        mu = p['mean'].gather(1, take).squeeze(1)
        sd = p['log_sigma'].exp().gather(1, take).squeeze(1) * noise_scale
        r = p['rho'].gather(1, idx).squeeze(1)
        e = torch.randn(mu.shape, generator=generator, device=mu.device, dtype=mu.dtype)
        xy = mu + sd * torch.stack((e[:, 0], r*e[:, 0] + (1-r.square()).sqrt()*e[:, 1]), -1)
        lift = (torch.rand(r.shape, generator=generator, device=r.device) <
                (p['pen_logit'] + pen_bias).sigmoid()).to(xy.dtype)
        return torch.cat((xy, lift[:, None]), -1)


def import_distill(path):
    """Decode upstream's published quantized weights, not a random initializer."""
    b = json.loads(Path(path).read_text())
    def decode(x, scale):
        if isinstance(x, list):
            return torch.stack([decode(row, scale) for row in x])
        return torch.from_numpy((np.frombuffer(base64.b64decode(x), np.uint8).astype(np.float32)-127)/127*scale)
    out_w, out_b, wx, wh, bias = [decode(x, s) for x, s in zip(b, [1.5, 2.5, 2.0, 0.8, 1.5])]
    m = HandwritingModel()
    h = m.config.hidden
    order = torch.cat([torch.arange(i*h, (i+1)*h) for i in [0, 2, 1, 3]])
    with torch.no_grad():
        m.rnn.weight_ih_l0.copy_(wx.T[order])
        m.rnn.weight_hh_l0.copy_(wh.T[order])
        m.rnn.bias_ih_l0.copy_(bias[order])
        m.rnn.bias_ih_l0[h:2*h].add_(1.0)
        m.rnn.bias_hh_l0.zero_()
        m.head.weight.copy_(out_w.T)
        m.head.bias.copy_(out_b)
    return m


def save_model(model, path, metadata=None):
    torch.save(dict(format_version=1, config=asdict(model.config),
                    model=model.state_dict(), metadata=metadata or {}), path)


DISTILL_WEIGHTS = (Path(__file__).resolve().parents[2]
                   / 'src/jvmMain/resources/afield/pen/distill-b64.json')


def load_model(path=None, device='cpu'):
    """Load a `.pt` checkpoint or a Distill-format `.json`; `None` is the bundled Distill weights."""
    path = DISTILL_WEIGHTS if path is None else path
    if str(path).endswith('.json'):
        m = import_distill(path)
    else:
        d = torch.load(path, map_location='cpu', weights_only=True)
        if d['format_version'] != 1:
            raise ValueError('Unsupported checkpoint version')
        m = HandwritingModel(Config(**d['config']))
        m.load_state_dict(d['model'])
    return m.to(device).eval()


def export_for_field(model, path, metadata=None):
    """Write `model` as a `.fieldpen.json` that Kotlin's `PenModel.load(path)` reads, no torch needed.

    Tensors are little-endian float32, base64-encoded, in torch's own layout and gate order.
    """
    tensors = {k: dict(shape=list(v.shape),
                       data=base64.b64encode(v.detach().cpu().float().contiguous().numpy()
                                             .astype('<f4').tobytes()).decode('ascii'))
               for k, v in model.state_dict().items()}
    Path(path).write_text(json.dumps(dict(format='fieldpen', format_version=1,
                                          config=asdict(model.config), tensors=tensors,
                                          metadata=metadata or {})))
