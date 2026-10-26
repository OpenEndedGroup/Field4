from __future__ import annotations

import copy
from dataclasses import dataclass
import math
import torch


@dataclass
class Controls:
    mixture_temperature: float = 0.65
    noise_scale: float = 0.65
    pen_bias: float = 0.0
    drift_x: float = 0.0
    drift_y: float = 0.0

    def __post_init__(self):
        if not all(math.isfinite(v) for v in vars(self).values()):
            raise ValueError('Controls must be finite')
        if not 0.01 <= self.mixture_temperature <= 10 or not 0 <= self.noise_scale <= 10:
            raise ValueError('temperature in [0.01,10], noise_scale in [0,10]')
        if abs(self.pen_bias) > 30 or max(abs(self.drift_x), abs(self.drift_y)) > 100:
            raise ValueError('pen_bias <= 30 and drift <= 100 in magnitude')


class Session:
    """State has consumed BOS and every committed point. Head predicts next point.

    points: [x,y,move] absolute coordinates. move applies to the segment ARRIVING
    at this point; deltas: [dx,dy,lift] where lift applies AFTER this point.
    """
    def __init__(self, model, seed=0):
        self.model = model
        self.device = next(model.parameters()).device
        self.rng = torch.Generator(device=self.device).manual_seed(seed)
        self.reset(seed)

    @torch.no_grad()
    def reset(self, seed=0):
        self.rng.manual_seed(seed)
        _, self.state = self.model(torch.zeros(1, 1, 3, device=self.device), self.model.zero_state())
        self.position = torch.zeros(1, 2, device=self.device)
        self.pen_up = torch.ones(1, device=self.device)
        self.count = 0

    def clone(self):
        other = copy.copy(self)
        other.state = tuple(x.clone() for x in self.state)
        other.position = self.position.clone()
        other.pen_up = self.pen_up.clone()
        other.rng = torch.Generator(device=self.device)
        other.rng.set_state(self.rng.get_state())
        return other

    @torch.no_grad()
    def consume(self, deltas, trace=False):
        x = torch.as_tensor(deltas, dtype=torch.float32, device=self.device)
        if x.ndim != 2 or x.shape[1] != 3 or len(x) > 4096 or not torch.isfinite(x).all():
            raise ValueError('deltas must be finite [N,3], N <= 4096')
        if not ((x[:, 2] == 0) | (x[:, 2] == 1)).all():
            raise ValueError('lift must be 0 or 1')
        points, hs, cs = [], [], []
        for row in x:
            self.position += row[None, :2]
            points.append(torch.cat((self.position, self.pen_up[:, None]), -1).clone())
            _, self.state = self.model(row[None, None], self.state)
            self.pen_up = row[2:3].clone()
            self.count += 1
            if trace:
                hs.append(self.state[0][:, 0].clone())
                cs.append(self.state[1][:, 0].clone())
        out = dict(points=torch.cat(points).cpu().tolist() if points else [], count=self.count)
        if trace:
            out.update(h=torch.stack(hs).cpu().tolist() if hs else [],
                       c=torch.stack(cs).cpu().tolist() if cs else [])
        return out

    @torch.no_grad()
    def generate(self, steps=32, controls=None, trace=False):
        if not isinstance(steps, int) or not 1 <= steps <= 4096:
            raise ValueError('steps must be in [1,4096]')
        if trace and steps > 256:
            raise ValueError('trace limited to 256 steps')
        controls = controls or Controls()
        deltas, points, hs, cs = [], [], [], []
        for _ in range(steps):
            p = self.model.distribution(self.state)
            x = self.model.sample(p, self.rng, controls.mixture_temperature,
                                  controls.noise_scale, controls.pen_bias)
            x[:, 0] += controls.drift_x
            x[:, 1] += controls.drift_y
            self.position += x[:, :2]
            points.append(torch.cat((self.position, self.pen_up[:, None]), -1).clone())
            _, self.state = self.model(x[:, None], self.state)
            self.pen_up = x[:, 2].clone()
            deltas.append(x)
            self.count += 1
            if trace:
                hs.append(self.state[0][:, 0].clone())
                cs.append(self.state[1][:, 0].clone())
        out = dict(deltas=torch.cat(deltas).cpu().tolist(), points=torch.cat(points).cpu().tolist(), count=self.count)
        if trace:
            out.update(h=torch.stack(hs).cpu().tolist(), c=torch.stack(cs).cpu().tolist())
        return out

    @torch.no_grad()
    def branches(self, branches=32, steps=16, seed=1, controls=None):
        if not isinstance(branches, int) or not 1 <= branches <= 128:
            raise ValueError('branches must be in [1,128]')
        if not isinstance(steps, int) or not 1 <= steps <= 256 or steps*branches > 8192:
            raise ValueError('steps <= 256, total branch points <= 8192')
        c = controls or Controls()
        state = tuple(t.repeat(1, branches, 1) for t in self.state)
        position = self.position.repeat(branches, 1)
        pen = self.pen_up.repeat(branches)
        rng = torch.Generator(device=self.device).manual_seed(seed)
        ds, ps = [], []
        for _ in range(steps):
            p = self.model.distribution(state)
            x = self.model.sample(p, rng, c.mixture_temperature, c.noise_scale, c.pen_bias)
            x[:, 0] += c.drift_x
            x[:, 1] += c.drift_y
            position += x[:, :2]
            ps.append(torch.cat((position, pen[:, None]), -1).clone())
            ds.append(x)
            _, state = self.model(x[:, None], state)
            pen = x[:, 2].clone()
        return dict(deltas=torch.stack(ds, 1).cpu().tolist(), points=torch.stack(ps, 1).cpu().tolist())

    @torch.no_grad()
    def inspect(self):
        p = self.model.distribution(self.state)
        w = p['logits'].softmax(-1)
        mu, sd, r = p['mean'], p['log_sigma'].exp(), p['rho']
        mean = (w[..., None]*mu).sum(1)
        cov = torch.diag_embed(sd.square())
        cov[..., 0, 1] = cov[..., 1, 0] = r*sd[..., 0]*sd[..., 1]
        d = mu-mean[:, None]
        covariance = (w[..., None, None]*(cov + d[..., :, None]*d[..., None, :])).sum(1)
        return dict(h=self.state[0][:, 0].cpu().tolist(), c=self.state[1][:, 0].cpu().tolist(),
                    weights=w[0].cpu().tolist(), means=mu[0].cpu().tolist(),
                    sigmas=sd[0].cpu().tolist(), correlations=r[0].cpu().tolist(),
                    pen_probability=p['pen_logit'].sigmoid().item(),
                    mean=mean[0].cpu().tolist(), covariance=covariance[0].cpu().tolist(),
                    position=self.position[0].cpu().tolist(), count=self.count)

    @torch.no_grad()
    def intervene(self, cell, value, layer=0, state='c', operation='set'):
        if not 0 <= cell < self.model.config.hidden or not 0 <= layer < self.model.config.layers:
            raise ValueError('Invalid cell/layer')
        if state not in ('h', 'c') or operation not in ('set', 'add') or not math.isfinite(value) or abs(value)>100:
            raise ValueError('Invalid intervention')
        t = self.state[0 if state == 'h' else 1]
        if operation == 'set':
            t[layer, 0, cell] = value
        else:
            t[layer, 0, cell] += value
        return self.inspect()
