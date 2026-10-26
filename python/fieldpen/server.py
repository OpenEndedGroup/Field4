"""Resident local JSONL server. stdout is exclusively protocol; stderr is logs."""
from __future__ import annotations
import argparse
from dataclasses import asdict
import json
import sys
import time
import torch
from .model import load_model
from .session import Session, Controls

MAX_LINE = 4 * 1024 * 1024


class Service:
    def __init__(self, model):
        self.model = model
        self.sessions = {}
        self.snapshots = {}

    def dispatch(self, q):
        op = q['op']
        sid = q.get('session', 'main')
        if not isinstance(sid, str) or not 1 <= len(sid) <= 128:
            raise ValueError('session must be a short string')
        if op == 'health':
            return dict(protocol=1, config=asdict(self.model.config),
                        device=str(next(self.model.parameters()).device), sessions=len(self.sessions))
        if op == 'create':
            if sid in self.sessions:
                raise ValueError('Session already exists; use reset')
            if len(self.sessions) >= 32:
                raise ValueError('Maximum 32 sessions')
            self.sessions[sid] = Session(self.model, q.get('seed', 0))
            return dict(session=sid)
        s = self.sessions[sid]
        if op == 'drop':
            del self.sessions[sid]
            self.snapshots = {k: v for k,v in self.snapshots.items() if k[0] != sid}
            return dict(dropped=sid)
        if op == 'reset':
            s.reset(q.get('seed', 0))
            return dict(count=0)
        if op == 'inspect':
            return s.inspect()
        if op == 'snapshot':
            name = q['name']
            if not isinstance(name, str) or not 1 <= len(name) <= 128:
                raise ValueError('Snapshot name must be a short string')
            if (sid, name) not in self.snapshots and len(self.snapshots) >= 128:
                raise ValueError('Maximum 128 snapshots; release unused snapshots')
            self.snapshots[sid, name] = s.clone()
            return dict(name=name)
        if op == 'release':
            del self.snapshots[sid, q['name']]
            return dict(released=q['name'])
        if op == 'restore':
            self.sessions[sid] = self.snapshots[sid, q['name']].clone()
            return dict(count=self.sessions[sid].count)
        # Mutating computations commit only if the entire request succeeds.
        trial = s.clone()
        if op == 'consume':
            if q.get('trace') and len(q['deltas']) > 256:
                raise ValueError('trace limited to 256 points')
            result = trial.consume(q['deltas'], q.get('trace', False))
        elif op == 'generate':
            result = trial.generate(q.get('steps', 32), Controls(**q.get('controls', {})), q.get('trace', False))
        elif op == 'branches':
            return s.branches(q.get('branches', 32), q.get('steps', 16), q.get('seed', 1), Controls(**q.get('controls', {})))
        elif op == 'intervene':
            result = trial.intervene(q['cell'], q['value'], q.get('layer', 0),
                                     q.get('state', 'c'), q.get('operation', 'set'))
        else:
            raise ValueError(f'Unknown operation: {op}')
        # Check serialization before committing, including NaN/overflow failures.
        json.dumps(result, allow_nan=False)
        self.sessions[sid] = trial
        return result


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--checkpoint', default=None, help='Checkpoint (.pt, .json); default: the bundled Distill weights')
    p.add_argument('--device', default='cpu')
    p.add_argument('--threads', type=int, default=1)
    a = p.parse_args()
    torch.set_num_threads(a.threads)
    service = Service(load_model(a.checkpoint, a.device))
    print('Handwriting ready (protocol 1)', file=sys.stderr, flush=True)
    while True:
        line = sys.stdin.buffer.readline(MAX_LINE+1)
        if not line:
            break
        if len(line) > MAX_LINE:
            print('Request exceeded 4 MiB; closing protocol', file=sys.stderr)
            return 2
        q = {}
        rid = None
        t = time.perf_counter()
        try:
            q = json.loads(line)
            if not isinstance(q, dict):
                raise ValueError('Request must be a JSON object')
            json.dumps(q, allow_nan=False)
            rid = q.get('id')
            result = service.dispatch(q)
            response = dict(id=rid, ok=True, result=result,
                            elapsed_ms=(time.perf_counter()-t)*1000)
            encoded = json.dumps(response, allow_nan=False, separators=(',', ':'))
        except Exception as e:
            encoded = json.dumps(dict(id=rid,
                                      ok=False, error=f'{type(e).__name__}: {e}'), allow_nan=False)
        print(encoded, flush=True)
    return 0


if __name__ == '__main__':
    sys.exit(main())
