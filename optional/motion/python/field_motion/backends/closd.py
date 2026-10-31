"""Pinned CLoSD adapter. Stock planner, motor policy, resets and executed-prefix loop remain owners.

The two intervention points are the player's run loop and a delegating Gym proxy at simulate().
No upstream model or simulator source is rewritten. Only one character/env is supported.
"""
import copy
import heapq
import math
import time
import types
from pathlib import Path
import numpy as np
from scipy.spatial.transform import Rotation
from field_motion.recording import skeleton_hash, validate_skeleton, validate_frame

BASIS = np.array([[1., 0., 0.], [0., 0., 1.], [0., -1., 0.]])


class GymForces:
    """Preserve upstream force submissions, adding interventions immediately before each simulate."""
    def __init__(self, gym, bridge):
        self.native = gym
        self.bridge = bridge
        self.force = None
        self.torque = None

    def __getattr__(self, key):
        return getattr(self.native, key)

    def apply_rigid_body_force_tensors(self, sim, forces, torques, frame):
        from isaacgym import gymapi, gymtorch
        if frame not in (gymapi.ENV_SPACE, gymapi.GLOBAL_SPACE):
            raise ValueError('Upstream local-space force accumulation needs an explicit conversion')
        for name, value in [('force', forces), ('torque', torques)]:
            if value is not None:
                tensor = gymtorch.wrap_tensor(value).reshape(-1, 3)
                old = getattr(self, name)
                setattr(self, name, tensor.clone() if old is None else old + tensor)
        return True

    def simulate(self, sim):
        from isaacgym import gymapi, gymtorch
        import torch
        task = self.bridge.task
        self.native.refresh_rigid_body_state_tensor(sim)
        state = task._rigid_body_state.reshape(-1, 13)
        force = torch.zeros_like(state[:, :3]) if self.force is None else self.force
        torque = torch.zeros_like(force) if self.torque is None else self.torque
        self.bridge.apply_forces(state, force, torque)
        if self.force is not None or self.torque is not None or self.bridge.active_force:
            self.native.apply_rigid_body_force_tensors(sim, gymtorch.unwrap_tensor(force), gymtorch.unwrap_tensor(torque), gymapi.ENV_SPACE)
        self.force = self.torque = None
        start = time.perf_counter()
        self.native.simulate(sim)
        self.bridge.physics_submissions_ms += (time.perf_counter() - start) * 1000
        self.bridge.physics_ticks += 1


class CLoSDBridge:
    def __init__(self, player):
        from isaacgym import gymapi
        import torch
        self.player = player
        self.task = task = player.env.task
        if task.num_envs != 1:
            raise ValueError('Field CLoSD currently requires env.num_envs=1')
        self.obs = player.env_reset()
        player.get_batch_size(self.obs["obs"], 1)
        if player.is_rnn:
            player.init_rnn()
        tree = task.skeleton_trees[0]
        names = list(tree.node_names)
        parents = tree.parent_indices.cpu().tolist()
        rest = tree.local_translation.cpu().numpy() @ BASIS.T
        actor = task.humanoid_handles[0]
        env = task.envs[0]
        body_names = list(task.gym.get_actor_rigid_body_names(env, actor))
        props = task.gym.get_actor_rigid_body_properties(env, actor)
        self.body = {}
        bodies = []
        for i, name in enumerate(body_names):
            if name not in names:
                raise ValueError('Rigid body has no skeleton mapping: ' + name)
            index = task.gym.get_actor_rigid_body_index(env, actor, i, gymapi.DOMAIN_SIM)
            self.body[name] = dict(index=index, com=np.array([props[i].com.x, props[i].com.y, props[i].com.z]))
            bodies.append(dict(name=name, joint=name, massKg=props[i].mass))
        if body_names != names:
            raise ValueError('CLoSD pose state order differs from skeleton; explicit reorder required')
        self.skeleton = dict(id='closd.smpl-humanoid', version='d8950bdb', names=names, parents=parents,
            restTranslations=rest.reshape(-1).tolist(), restRotations=[0., 0., 0., 1.] * len(names), bodies=bodies,
            coordinates=dict(sourceUnits='m', sourceHandedness='right', sourceUp='+Z', sourceForward='+X',
                sourceToField=BASIS.reshape(-1).tolist(), metresPerUnit=1., common='RH_Y_UP_METRES_XYZW'))
        self.skeleton['hash'] = skeleton_hash(self.skeleton)
        validate_skeleton(self.skeleton)
        self.parents = parents
        self.time = 0.
        self.index = 0
        self.episode = 0
        self.revision = 0
        self.prompt_revision = 0
        self.physics_ticks = 0
        self.physics_submissions_ms = 0.
        self.planner_ms = 0.
        self.events = []
        self.scheduled = []
        self.attachments = {}
        self.impulses = []
        self.done = False
        self.active_force = False
        self.force_diagnostics = {}
        task.gym = GymForces(task.gym, self)
        original = task.get_mdm_next_planning_horizon
        def plan(*args, **kwargs):
            start = time.perf_counter()
            result = original(*args, **kwargs)
            torch.cuda.synchronize()
            self.revision = max(self.revision, self.prompt_revision)
            self.planner_ms += (time.perf_counter() - start) * 1000
            self.events.append(dict(type='planner', time=self.time, revision=self.revision,
                physicsTick=self.physics_ticks, prefixSource='executed',
                prefixResidualM=float((task.pose_buffer[:, -1] - task._rigid_body_pos).abs().max().item())))
            return result
        task.get_mdm_next_planning_horizon = plan
        if task.context_switch_prob != 0 or task.planning_horizon_multiplyer != 1:
            raise ValueError('Executed feedback requires context_switch_prob=0 and planning_horizon_multiplyer=1')
        self.capabilities = dict(backend='closd', operations=['PROMPT', 'ATTACH', 'UPDATE_ATTACHMENT', 'DETACH', 'IMPULSE', 'RESET', 'ADVANCE'],
            schema=1, fixedControlStep=task.dt, fixedPhysicsStep=task.dt / task.control_freq_inv,
            referenceRotationsAvailable=False, episodeLengthFrames=int(task.max_episode_length), physicsSubsteps=task.gym.get_sim_params(task.sim).substeps, sourceCommit='d8950bdb78312fcd2844f336b3fc079e7368c87f',
            checkpoint='guytevet/CLoSD', checkpointRevision='de7106b947b6f70700b5320d1cd61fef4a9ebc9b', seed=int(torch.initial_seed()),
            termination=dict(earlyEnabled=bool(task._enable_early_termination),
                distanceM=task._termination_distances.detach().cpu().tolist(),
                logic='stock humanoid_im::_compute_reset, tracking distance with stock cycle/recovery guards'),
            resetPolicy='stock auto-reset, logged; reset releases all attachments', contacts='net force magnitude > 1 N')

    def schedule(self, command):
        op = command['operation']
        if op not in self.capabilities['operations'] or op == 'ADVANCE':
            raise ValueError('Unsupported CLoSD operation: ' + op)
        requested = float(command['requestedTime'])
        if not math.isfinite(requested) or requested < 0:
            raise ValueError('Invalid effective time')
        if len(self.scheduled) >= 32:
            raise ValueError('CLoSD scheduled event queue full')
        payload = command['payload']
        if op in ('ATTACH', 'UPDATE_ATTACHMENT', 'IMPULSE'):
            if payload['body'] not in self.body:
                raise ValueError('Unknown rigid body: ' + payload['body'])
            for key in ('localPoint', 'impulse' if op == 'IMPULSE' else 'anchor'):
                if len(payload[key]) != 3 or not np.isfinite(payload[key]).all():
                    raise ValueError('Invalid intervention vector')
            if op != 'IMPULSE':
                velocity = payload.get('anchorVelocity', [0., 0., 0.])
                if len(velocity) != 3 or not np.isfinite(velocity).all():
                    raise ValueError('Invalid anchor velocity')
                for key in ('stiffness', 'damping', 'maximumForce', 'engagementSeconds', 'breakDuration'):
                    if not math.isfinite(payload[key]) or payload[key] < 0:
                        raise ValueError('Invalid tether coefficient: ' + key)
                if payload['maximumForce'] <= 0 or payload['breakDuration'] <= 0:
                    raise ValueError('Invalid tether cap/break duration')
                for key in ('breakForce', 'breakError'):
                    value = payload.get(key)
                    if value is not None and (not math.isfinite(value) or value <= 0):
                        raise ValueError('Invalid break criterion: ' + key)
        heapq.heappush(self.scheduled, (requested, command['revision'], copy.deepcopy(command)))
        return dict(appliedTime=None, frameIndex=None, details=dict(disposition='scheduled'))

    def apply_commands(self):
        import torch
        while self.scheduled and self.scheduled[0][0] <= self.time + 1e-8:
            _, _, cmd = heapq.heappop(self.scheduled)
            op = cmd['operation']; p = cmd['payload']
            if op == 'PROMPT':
                self.task.hml_prompts = [str(p['text'])]
                self.prompt_revision = cmd['revision']
            else:
                self.revision = max(self.revision, cmd['revision'])
            if op in ('ATTACH', 'UPDATE_ATTACHMENT'):
                if op == 'UPDATE_ATTACHMENT' and p['id'] not in self.attachments:
                    raise ValueError('Attachment does not exist: ' + p['id'])
                if op == 'ATTACH' and p['id'] in self.attachments:
                    raise ValueError('Attachment already exists: ' + p['id'])
                old = self.attachments.get(p['id'])
                self.attachments[p['id']] = dict(parameters=copy.deepcopy(p), since=old['since'] if old else self.time,
                    excessive=old['excessive'] if old else 0., updated=self.time)
            elif op == 'DETACH':
                if p['id'] not in self.attachments:
                    raise ValueError('Attachment does not exist: ' + p['id'])
                del self.attachments[p['id']]
            elif op == 'IMPULSE':
                self.impulses.append(copy.deepcopy(p))
            elif op == 'RESET':
                self.reset('manual')
            self.events.append(dict(type='applied', operation=op, revision=cmd['revision'], requestedTime=cmd['requestedTime'],
                appliedTime=self.time, frameIndex=self.index, episode=self.episode, payload=p))

    def reset(self, reason):
        self.attachments.clear(); self.impulses.clear()
        self.obs = self.player.env_reset()
        self.done = False; self.episode += 1
        self.events.append(dict(type='reset', reason=reason, time=self.time, frameIndex=self.index, episode=self.episode))

    def apply_forces(self, states, force, torque):
        import torch
        step = self.task.dt / self.task.control_freq_inv
        # The state position is body origin; linear velocity is COM velocity (verified by the offset-COM probe).
        self.active_force = bool(self.attachments or self.impulses)
        self.force_diagnostics = {}
        def point_state(p):
            b = self.body[p['body']]; idx = b['index']
            state = states[idx].detach().cpu().numpy()
            rotation = Rotation.from_quat(state[3:7])
            local = np.asarray(p['localPoint']) @ BASIS  # common -> source
            point = state[:3] + rotation.apply(local)
            com = state[:3] + rotation.apply(b['com'])
            radius = point - com
            velocity = state[7:10] + np.cross(state[10:13], radius)
            return idx, point, radius, velocity
        def add(idx, radius, vector):
            force[idx] += torch.as_tensor(vector.copy(), dtype=force.dtype, device=force.device)
            torque[idx] += torch.as_tensor(np.cross(radius, vector).copy(), dtype=torque.dtype, device=torque.device)
        for impulse in self.impulses:
            idx, point, radius, velocity = point_state(impulse)
            add(idx, radius, (np.asarray(impulse['impulse']) @ BASIS) / step)
            self.events.append(dict(type='impulsePhysics', time=self.time, physicsTick=self.physics_ticks, body=impulse['body'], revision=self.revision))
        self.impulses.clear()  # one physics tick, never each control substep
        breaks = []
        for identity, tether in self.attachments.items():
            p = tether['parameters']; idx, point, radius, velocity = point_state(p)
            physics_time = self.time + self.physics_ticks % self.task.control_freq_inv * step
            anchor_velocity = np.asarray(p.get('anchorVelocity', [0., 0., 0.])) @ BASIS
            anchor = np.asarray(p['anchor']) @ BASIS + anchor_velocity * (physics_time - tether['updated'])
            error = anchor - point
            ramp = 1. if p['engagementSeconds'] == 0 else min(1., max(0., (physics_time - tether['since']) / p['engagementSeconds']))
            raw = (p['stiffness'] * error - p['damping'] * (velocity - anchor_velocity)) * ramp
            magnitude = float(np.linalg.norm(raw))
            vector = raw * min(1., p['maximumForce'] / max(magnitude, 1e-20))
            add(idx, radius, vector)
            excessive = (p.get('breakForce') is not None and magnitude >= p['breakForce']) or (p.get('breakError') is not None and np.linalg.norm(error) >= p['breakError'])
            tether['excessive'] = tether['excessive'] + step if excessive else 0.
            if tether['excessive'] >= p['breakDuration']:
                breaks.append(identity)
            diagnostic = dict(type='tetherPhysics', id=identity, time=self.time, physicsTick=self.physics_ticks, revision=self.revision,
                physicsTime=physics_time, pointVelocity=(velocity @ BASIS.T).tolist(), torque=(np.cross(radius, vector) @ BASIS.T).tolist(),
                force=(vector @ BASIS.T).tolist(), point=(point @ BASIS.T).tolist(), anchor=(anchor @ BASIS.T).tolist(),
                forceN=float(np.linalg.norm(vector)), rawForceN=magnitude, errorM=float(np.linalg.norm(error)),
                powerWatts=float(vector @ velocity), workJ=float(vector @ velocity) * step)
            self.events.append(diagnostic)
            self.force_diagnostics[identity + '.forceN'] = diagnostic['forceN']
            self.force_diagnostics[identity + '.errorM'] = diagnostic['errorM']
            self.force_diagnostics[identity + '.powerWatts'] = diagnostic['powerWatts']
        for identity in breaks:
            del self.attachments[identity]
            self.events.append(dict(type='tetherBreak', id=identity, time=self.time, physicsTick=self.physics_ticks, revision=self.revision))

    def pose(self, positions, rotations=None):
        world = positions @ BASIS.T
        local = []
        if rotations is not None:
            global_rot = Rotation.from_quat(rotations).as_matrix()
            global_rot = BASIS @ global_rot @ BASIS.T
            local_mats = np.stack([global_rot[i] if p < 0 else global_rot[p].T @ global_rot[i] for i, p in enumerate(self.parents)])
            local = Rotation.from_matrix(local_mats).as_quat().reshape(-1).tolist()
        return dict(root=world[0].tolist(), localRotations=local, worldPositions=world.reshape(-1).tolist())

    def advance(self, seconds):
        import torch
        if not math.isfinite(seconds) or not 0 < seconds <= 10:
            raise ValueError('Advance must be in (0,10] seconds')
        count = int(round(seconds / self.task.dt))
        if count < 1 or abs(count * self.task.dt - seconds) > 1e-6:
            raise ValueError('CLoSD advance must be a multiple of its fixed control step: %.9f seconds' % self.task.dt)
        frames = []
        with torch.no_grad():
            for _ in range(count):
                if self.done:
                    self.reset('stock termination/episode-end')
                self.apply_commands()
                self.obs = self.player.env_reset([])  # stock player wraps current observations on every control update
                self.planner_ms = self.physics_submissions_ms = 0.
                start = time.perf_counter()
                action = self.player.get_action(self.obs, self.player.is_determenistic)
                self.obs, reward, done, info = self.player.env_step(self.player.env, action)
                done = self.player._post_step(info, done.clone())
                torch.cuda.synchronize()
                self.time += self.task.dt; self.index += 1
                self.done = bool(done[0])
                diagnostics = dict(controlMs=(time.perf_counter() - start) * 1000, plannerMs=self.planner_ms,
                    physicsSubmitMs=self.physics_submissions_ms, reward=float(reward[0]), resetPending=float(self.done),
                    terminated=float(self.task._terminate_buf[0]), prefixResidualM=float((self.task.pose_buffer[:, -1] - self.task._rigid_body_pos).abs().max().item()),
                    **self.force_diagnostics)
                positions = self.task._rigid_body_pos[0].detach().cpu().numpy()
                executed = self.pose(positions, self.task._rigid_body_rot[0].detach().cpu().numpy())
                reference = self.pose(self.task.ref_body_pos[0].detach().cpu().numpy())
                diagnostics['trackingErrorM'] = float(np.linalg.norm(np.array(reference['worldPositions']).reshape(-1, 3) - np.array(executed['worldPositions']).reshape(-1, 3), axis=-1).mean())
                contacts = (self.task._contact_forces[0].norm(dim=-1) > 1.).float().cpu().tolist()
                frame = dict(time=self.time, index=self.index, episode=self.episode, revision=self.revision, executed=executed,
                    intended=reference, contacts=contacts, contactProvenance='simulator.measured', diagnostics=diagnostics)
                validate_frame(frame, len(self.parents))
                frames.append(frame)
                if self.done:
                    self.events.append(dict(type='termination' if diagnostics['terminated'] else 'episodeEnd', time=self.time, frameIndex=self.index, episode=self.episode))
        events, self.events = self.events, []
        return dict(frames=frames, events=events)

    def close(self):
        gym = self.task.gym.native
        if self.task.viewer:
            gym.destroy_viewer(self.task.viewer)
        gym.destroy_sim(self.task.sim)
