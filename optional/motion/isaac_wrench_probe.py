#!/usr/bin/env python3
"""Actual Isaac Gym point-force/lifetime probe on a free 1 kg cube, not a CLoSD policy test.

Run in the isolated Python 3.8 SDK environment. No viewer, model, teleport or velocity overwrite.
"""
import argparse
import json
from pathlib import Path
import sys

# Isaac Gym must be imported before Torch.
from isaacgym import gymapi, gymtorch
import numpy as np
import torch


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--cpu", action="store_true", help="Use CPU PhysX/tensors; default tests the GPU pipeline")
    parser.add_argument("--com-x", type=float, default=0.0, help="Offset COM to verify state-origin and velocity semantics")
    args = parser.parse_args()
    gym = gymapi.acquire_gym()
    params = gymapi.SimParams()
    params.dt = 1 / 120
    params.substeps = 1
    params.up_axis = gymapi.UP_AXIS_Z
    params.gravity = gymapi.Vec3(0, 0, 0)
    params.physx.use_gpu = not args.cpu
    params.use_gpu_pipeline = not args.cpu
    params.physx.num_position_iterations = 4
    params.physx.num_velocity_iterations = 1
    sim = gym.create_sim(0, -1, gymapi.SIM_PHYSX, params)
    if sim is None:
        raise RuntimeError("Isaac Gym could not create a simulation")
    try:
        options = gymapi.AssetOptions()
        options.density = 125  # .2 m cube: .008 m^3 -> 1 kg
        options.linear_damping = 0
        options.angular_damping = 0
        asset = gym.create_box(sim, .2, .2, .2, options)
        env = gym.create_env(sim, gymapi.Vec3(-1, -1, -1), gymapi.Vec3(1, 1, 1), 1)
        transform = gymapi.Transform()
        transform.p.z = 1
        actor = gym.create_actor(env, asset, transform, "probe", 0, 0)
        properties = gym.get_actor_rigid_body_properties(env, actor)
        if args.com_x:
            properties[0].com.x = args.com_x
            gym.set_actor_rigid_body_properties(env, actor, properties, False)
        mass = properties[0].mass
        inertia_z = properties[0].inertia.z.z
        assert abs(mass - 1) < 1e-5, mass
        gym.prepare_sim(sim)
        states = gymtorch.wrap_tensor(gym.acquire_rigid_body_state_tensor(sim))
        device = states.device
        force = torch.zeros((1, 1, 3), device=device)
        point = torch.zeros((1, 1, 3), device=device)

        def step():
            gym.simulate(sim)
            gym.fetch_results(sim, True)
            gym.refresh_rigid_body_state_tensor(sim)
            state = states.detach().cpu().numpy().copy()[0]
            if not np.isfinite(state).all():
                raise RuntimeError("Non-finite rigid body state")
            return state

        baseline = step()
        force[0, 0, 1] = 1.0  # N in ENV_SPACE
        point[0, 0] = states[0, :3]
        point[0, 0, 0] += .1  # off-COM point, 0.1 m along X
        applied = gym.apply_rigid_body_force_at_pos_tensors(sim, gymtorch.unwrap_tensor(force),
            gymtorch.unwrap_tensor(point), gymapi.ENV_SPACE)
        if not applied:
            raise RuntimeError("Isaac Gym rejected the point-force tensors")
        forced = step()
        released = step()  # no call to apply force: test one-step lifetime
        expected_dv = params.dt / mass
        expected_dw = (.1 - args.com_x) * params.dt / inertia_z
        measured_dv = float(forced[8] - baseline[8])  # body state: pos3, quat4, linear vel3, angular vel3
        measured_dw = float(forced[12] - baseline[12])
        second_dv = float(released[8] - forced[8])
        assert abs(measured_dv - expected_dv) < expected_dv * .03 + 1e-6, (measured_dv, expected_dv)
        assert abs(measured_dw - expected_dw) < expected_dw * .03 + 1e-6, (measured_dw, expected_dw)
        assert abs(second_dv) < 1e-6, second_dv
        result = dict(probe="isaac.free-cube.point-force", closdPolicyValidated=False,
                      gpuPipeline=not args.cpu, torch=torch.__version__, dt=params.dt,
                      massKg=mass, inertiaZ=inertia_z, forceN=[0, 1, 0], pointOffsetM=[.1, 0, 0],
                      localCentreOfMassM=[args.com_x, 0, 0],
                      expectedDeltaVelocity=expected_dv, measuredDeltaVelocity=measured_dv,
                      expectedDeltaAngularVelocity=expected_dw, measuredDeltaAngularVelocity=measured_dw,
                      deltaVelocityAfterRelease=second_dv,
                      states=[s.tolist() for s in (baseline, forced, released)], passed=True)
        args.output.write_text(json.dumps(result, indent=2) + "\n")
        print(json.dumps(result, indent=2))
    finally:
        gym.destroy_sim(sim)


if __name__ == "__main__":
    main()
