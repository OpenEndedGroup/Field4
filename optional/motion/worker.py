#!/usr/bin/env python3
"""Own one pinned optional backend in a process; parent supplies an authenticated loopback socket."""
import argparse
import contextlib
import hmac
import hashlib
import os
from pathlib import Path
import runpy
import socket
import subprocess
import sys

sys.path.insert(0, str(Path(__file__).resolve().parent / 'python'))
from field_motion.wire import receive, send


def serve_bridge(bridge, stream, token):
    adapter_file = Path(sys.modules[type(bridge).__module__].__file__)
    bridge.capabilities.update(adapterSha256=hashlib.sha256(adapter_file.read_bytes()).hexdigest(),
        workerSha256=hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
        pythonVersion=sys.version.split()[0], torchVersion=sys.modules['torch'].__version__)
    try:
        while True:
            request = receive(stream)
            if not hmac.compare_digest(str(request.pop('token', '')), token):
                raise PermissionError('Motion authentication failed')
            identity = request.get('id')
            try:
                operation = request['op']
                if operation == 'hello':
                    result = dict(schema=1, skeleton=bridge.skeleton, capabilities=bridge.capabilities)
                elif operation == 'command':
                    result = bridge.schedule(request['command'])
                elif operation == 'advance':
                    result = bridge.advance(float(request['seconds']))
                elif operation == 'close':
                    send(stream, dict(id=identity, ok=True, result={}))
                    return
                else:
                    raise ValueError('Unknown motion operation: ' + operation)
                send(stream, dict(id=identity, ok=True, result=result))
            except (ValueError, KeyError) as e:
                send(stream, dict(id=identity, ok=False, error=str(e)))
    except EOFError:
        pass
    finally:
        bridge.close()


def serve(player, stream, token):
    from field_motion.backends.closd import CLoSDBridge
    return serve_bridge(CLoSDBridge(player), stream, token)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--backend', choices=['closd', 'ardy'], required=True)
    parser.add_argument('--source', type=Path, required=True)
    parser.add_argument('--connect', type=int, required=True)
    parser.add_argument('--override', action='append', default=[])
    parser.add_argument('--embedding-cache', type=Path)
    parser.add_argument('--encoder-url', default='http://127.0.0.1:9550/')
    parser.add_argument('--seed', type=int, default=0)
    parser.add_argument('--steps', type=int, default=4)
    args = parser.parse_args()
    token = os.environ['FIELD_MOTION_TOKEN']
    if not 1 <= args.connect <= 65535:
        raise ValueError('Invalid parent port')
    source = args.source.resolve()
    expected = ('d8950bdb78312fcd2844f336b3fc079e7368c87f' if args.backend == 'closd'
        else 'e706e27acc88070918308a10922b8f7174d0825e')
    actual = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=source, text=True).strip()
    if actual != expected:
        raise ValueError(args.backend + ' source revision mismatch: expected ' + expected)
    with socket.create_connection(('127.0.0.1', args.connect), timeout=30) as stream:
        stream.settimeout(None)
        # All source logging goes to stderr; control/pose payloads only use the loopback socket.
        with contextlib.redirect_stdout(sys.stderr):
            os.chdir(source)
            sys.path[:0] = [str(source), str(source / 'closd')]
            if args.backend == 'ardy':
                from field_motion.backends.ardy import ArdyBridge
                bridge = ArdyBridge(args.embedding_cache or source.parent.parent / 'embeddings', args.encoder_url, args.seed, args.steps)
                return serve_bridge(bridge, stream, token)
            from isaacgym import gymapi
            from learning import im_amp_players
            im_amp_players.IMAMPPlayerContinuous.run = lambda player: serve(player, stream, token)
            sys.argv = ['closd/run.py', 'learning=im_big', 'robot=smpl_humanoid', 'epoch=-1', 'test=True',
                'no_virtual_display=True', 'headless=True', 'env.num_envs=1', 'env=closd_t2m',
                'exp_name=CLoSD_t2m_finetune', 'learning.params.config.player.games_num=1', 'seed=' + str(args.seed)] + args.override
            runpy.run_path(str(source / 'closd/run.py'), run_name='__main__')


if __name__ == '__main__':
    main()
