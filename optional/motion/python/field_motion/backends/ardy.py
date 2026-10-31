"""Core27 ARDY streaming adapter, using the pinned model's public autoregressive step.

Generated future windows remain editable. Only complete model tokens are committed/displayed;
retained history is bounded by the trained ten-second window. No pose correction is applied.
"""
import hashlib
import heapq
import math
import time
from pathlib import Path
import numpy as np
from scipy.spatial.transform import Rotation
from field_motion.recording import skeleton_hash, validate_skeleton, validate_frame

ENCODER_ID = 'LLM2Vec:base=8afb486c1db24fe5011ec46dfbe5b5dccdb575c2:mntp=31474e395ada192e8ed1586db6be79fb3b70c9c0:supervised=baa8ebf04a1c2500e61288e7dad65e8ae42601a7:bf16'


def embedding_key(text):
    return hashlib.sha256((ENCODER_ID + '\0' + text).encode()).hexdigest()


def skeleton_metadata(skeleton):
    names = list(skeleton.bone_order_names)
    parents = skeleton.joint_parents.cpu().tolist()
    world = skeleton.neutral_joints.cpu().numpy()
    local = np.array([world[i] if p < 0 else world[i] - world[p] for i, p in enumerate(parents)])
    result = dict(id='ardy.core27', version='e706e27a', names=names, parents=parents,
        restTranslations=local.reshape(-1).tolist(), restRotations=[0., 0., 0., 1.] * len(names), bodies=[],
        coordinates=dict(sourceUnits='m', sourceHandedness='right', sourceUp='+Y', sourceForward='+Z',
            sourceToField=[1., 0., 0., 0., 1., 0., 0., 0., 1.], metresPerUnit=1., common='RH_Y_UP_METRES_XYZW'))
    result['hash'] = skeleton_hash(result)
    validate_skeleton(result)
    return result


def motion_frame(output, i, time_seconds, index, episode, revision, diagnostics=None):
    pose = dict(root=output['root_positions'][i].reshape(-1).tolist(),
        worldPositions=output['posed_joints'][i].reshape(-1).tolist(),
        localRotations=Rotation.from_matrix(output['local_rot_mats'][i]).as_quat().reshape(-1).tolist())
    return dict(time=time_seconds, index=index, episode=episode, revision=revision, executed=pose,
        intended=None, contacts=output.get('foot_contacts', np.empty((len(output['root_positions']), 0)))[i].astype(float).reshape(-1).tolist(),
        contactProvenance='ardy.predicted' if 'foot_contacts' in output else 'unavailable', diagnostics=diagnostics or {})


class ArdyBridge:
    def __init__(self, embedding_cache, encoder_url='http://127.0.0.1:9550/', seed=0, steps=4):
        import torch
        from ardy.model import load_model
        from huggingface_hub import snapshot_download
        from ardy.model.text_encoder_api import TextEncoderAPI
        from ardy.tools import seed_everything
        seed_everything(seed)
        self.torch = torch
        snapshot = Path(snapshot_download('nvidia/ARDY-Core-RP-20FPS-Horizon40',
            revision='fe1f897459605c98d5418d036bfcfe73bc244fd8', local_files_only=True))
        self.model = load_model(snapshot.name, checkpoints_dir=str(snapshot.parent), device='cuda', text_encoder=False)
        self.rep = self.model.motion_rep
        self.skeleton = skeleton_metadata(self.model.skeleton)
        self.fps = float(self.rep.fps)
        self.patch = int(self.model.num_frames_per_token)
        self.horizon = int(self.model.gen_horizon_len)
        self.history_limit = (int(10 * self.fps) - self.horizon) // self.patch * self.patch
        if not 1 <= steps <= int(self.model.diffusion.num_base_steps):
            raise ValueError('Unsupported ARDY denoising step count')
        self.steps = steps
        self.encoder_url = encoder_url
        self.encoder = None
        self.cache = Path(embedding_cache); self.cache.mkdir(parents=True, exist_ok=True)
        self.text = 'A person walks forward.'
        self.history = None
        self.future = None
        self.future_output = None
        self.future_cursor = 0
        self.index = 0; self.episode = 0; self.revision = 0
        self.route = []; self.targets = []; self.scheduled = []; self.events = []
        self.generation_ms = 0.; self.encoding_ms = 0.
        self.capabilities = dict(backend='ardy', schema=1,
            operations=['PROMPT', 'ROOT_PLAN', 'JOINT_TARGETS', 'RESET', 'ADVANCE'],
            sourceCommit='e706e27acc88070918308a10922b8f7174d0825e', fps=self.fps,
            checkpoint='nvidia/ARDY-Core-RP-20FPS-Horizon40', checkpointRevision='fe1f897459605c98d5418d036bfcfe73bc244fd8',
            seed=seed, denoisingSteps=steps,
            textEncoderIdentity=ENCODER_ID,
            commitFrames=self.patch, minimumAdvanceSeconds=self.patch / self.fps,
            generationHorizonFrames=self.horizon, editableFuture=True, postprocess=False,
            rootPlanAxes='XZ', globalJointTargetsRequireExplicitRootPlan=True,
            contactNames=list(self.model.skeleton.foot_joint_names),
            contacts='model predictions, not physical measurements')

    @property
    def time(self):
        return self.index / self.fps

    def schedule(self, command):
        op = command['operation']; p = command['payload']
        if op not in self.capabilities['operations'] or op == 'ADVANCE':
            raise ValueError('Unsupported ARDY operation: ' + op)
        requested = float(command['requestedTime'])
        if not math.isfinite(requested) or requested < 0 or len(self.scheduled) >= 32:
            raise ValueError('Invalid time or full ARDY event queue')
        if op == 'JOINT_TARGETS':
            for target in p['targets']:
                if target['joint'] not in self.skeleton['names']:
                    raise ValueError('Unknown ARDY joint: ' + target['joint'])
        heapq.heappush(self.scheduled, (requested, command['revision'], command))
        return dict(appliedTime=None, frameIndex=None, details=dict(disposition='scheduled', firstEditableTime=self.time))

    def apply_commands(self):
        while self.scheduled and self.scheduled[0][0] <= self.time + 1e-8:
            _, _, command = heapq.heappop(self.scheduled)
            op = command['operation']; p = command['payload']
            if op == 'PROMPT': self.text = str(p['text'])
            elif op == 'ROOT_PLAN': self.route = p['points']
            elif op == 'JOINT_TARGETS':
                if p['targets'] and not self.route:
                    raise ValueError('Core27 global joint positions require an explicit timed root plan; submit ROOT_PLAN first')
                self.targets = p['targets']
            elif op == 'RESET':
                self.history = None; self.route = []; self.targets = []; self.episode += 1
                self.events.append(dict(type='reset', time=self.time, frameIndex=self.index, episode=self.episode, reason='manual'))
            self.revision = command['revision']
            self.future = self.future_output = None
            self.events.append(dict(type='applied', operation=op, revision=self.revision,
                requestedTime=command['requestedTime'], appliedTime=self.time, frameIndex=self.index,
                episode=self.episode, payload=p))

    def embedding(self):
        torch = self.torch
        # Key includes the actual encoder family and base revision, not just prompt spelling.
        key = embedding_key(self.text)
        path = self.cache / (key + '.npy')
        start = time.perf_counter()
        if path.is_file():
            array = np.load(path, allow_pickle=False)
        else:
            from ardy.model.text_encoder_api import TextEncoderAPI
            if self.encoder is None: self.encoder = TextEncoderAPI(self.encoder_url)
            values, lengths = self.encoder(self.text)
            array = values[0, :lengths[0]].float().cpu().numpy()
            np.save(path, array, allow_pickle=False)
        if array.ndim != 2 or array.shape[1] != 4096 or not np.isfinite(array).all():
            raise ValueError('Invalid cached ARDY text embedding')
        self.encoding_ms = (time.perf_counter() - start) * 1000
        values = torch.from_numpy(array).to('cuda').unsqueeze(0)
        return values, torch.ones(values.shape[:2], dtype=torch.bool, device='cuda')

    def conditions(self, length, history_length):
        from collections import defaultdict
        torch = self.torch
        indices = defaultdict(list); data = defaultdict(list)
        start = self.time - history_length / self.fps
        if self.route:
            times = np.array([p['time'] for p in self.route])
            points = np.array([[p['x'], p['z']] for p in self.route])
            if len(times) < 2 or np.any(np.diff(times) <= 0) or not np.isfinite(points).all():
                raise ValueError('ARDY route needs two ordered finite timed points')
            slots = np.arange(history_length, length)
            seconds = start + slots / self.fps
            slots = slots[(seconds >= times[0] - 1e-8) & (seconds <= times[-1] + 1e-8)]
            seconds = start + slots / self.fps
            if len(slots):
                indices['root_2d'].append(torch.tensor(slots))
                data['root_2d'].append(torch.tensor(np.stack([np.interp(seconds, times, points[:, axis]) for axis in (0, 1)], axis=-1), dtype=torch.float32))
                if all(p.get('heading') is not None for p in self.route):
                    headings = np.unwrap([p['heading'] for p in self.route])
                    angles = np.interp(seconds, times, headings)
                    indices['global_root_heading'].append(torch.tensor(slots))
                    data['global_root_heading'].append(torch.tensor(np.stack([np.cos(angles), np.sin(angles)], axis=-1), dtype=torch.float32))
        for target in self.targets:
            frame = int(round((target['time'] - start) * self.fps))
            if not history_length <= frame < length: continue
            joint = self.skeleton['names'].index(target['joint'])
            indices['global_joints_positions'].append(torch.tensor([[frame, joint]]))
            data['global_joints_positions'].append(torch.tensor([target['position']], dtype=torch.float32))
            if target.get('orientation') is not None:
                indices['global_joints_rots'].append(torch.tensor([[frame, joint]]))
                data['global_joints_rots'].append(torch.tensor(Rotation.from_quat([target['orientation']]).as_matrix(), dtype=torch.float32))
        if not indices: return None, None
        observed, mask = self.rep.create_conditions(indices, data, length, True, 'cuda')
        observed[:history_length] = 0; mask[:history_length] = 0
        return observed.unsqueeze(0), mask.unsqueeze(0)

    def generate(self):
        torch = self.torch
        features, pad = self.embedding()
        history_length = 0 if self.history is None else self.history.shape[1]
        length = history_length + self.horizon
        observed, mask = self.conditions(length, history_length)
        torch.cuda.synchronize(); start = time.perf_counter()
        with torch.inference_mode():
            sample = self.model.autoregressive_step(num_frames=length, num_denoising_steps=self.steps,
                motion_mask=mask, observed_motion=observed, cfg_weight=(2., 2.), text_feat=features,
                text_pad_mask=pad, init_history_sequence=self.history,
                init_global_translation=torch.zeros(1, 3, device='cuda') if self.history is None else None,
                init_first_heading_angle=torch.zeros(1, device='cuda') if self.history is None else None)
            self.future = sample[:, history_length:].clone()
            output = self.rep.inverse(self.future, is_normalized=True)
        torch.cuda.synchronize()
        self.generation_ms = (time.perf_counter() - start) * 1000
        self.future_output = {key: value[0].detach().cpu().numpy() for key, value in output.items()}
        self.future_cursor = 0
        self.events.append(dict(type='generation', time=self.time, revision=self.revision, generationMs=self.generation_ms,
            encodingMs=self.encoding_ms, historyFrames=history_length, horizonFrames=self.horizon, postprocess=False))

    def advance(self, seconds):
        count = round(seconds * self.fps)
        if count <= 0 or count % self.patch or abs(count / self.fps - seconds) > 1e-6 or seconds > 10:
            raise ValueError('ARDY advance must commit complete model tokens: multiples of %.3f seconds' % (self.patch / self.fps))
        frames = []
        for _ in range(count // self.patch):
            self.apply_commands()
            if self.future is None or self.future_cursor >= self.horizon: self.generate()
            first = self.future_cursor
            for i in range(first, first + self.patch):
                diagnostics = dict(generationMs=self.generation_ms, encodingMs=self.encoding_ms, uncommittedFrames=self.horizon - i - 1)
                frame = motion_frame(self.future_output, i, self.time, self.index, self.episode, self.revision, diagnostics)
                validate_frame(frame, len(self.skeleton['names']))
                frames.append(frame); self.index += 1
            committed = self.future[:, first:first + self.patch]
            self.history = committed.clone() if self.history is None else self.torch.cat([self.history, committed], dim=1)[:, -self.history_limit:]
            self.future_cursor += self.patch
        events, self.events = self.events, []
        return dict(frames=frames, events=events)

    def close(self):
        self.history = self.future = self.future_output = self.model = None
        self.torch.cuda.empty_cache()
