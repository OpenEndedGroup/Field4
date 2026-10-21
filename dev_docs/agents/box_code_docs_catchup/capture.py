"""Recapture the changed live example through the normal docs runner, respecting admission."""
from pathlib import Path
import argparse
import os
import subprocess

ROOT=Path(__file__).resolve().parents[3]
parser=argparse.ArgumentParser();parser.add_argument('--port',type=int,default=9743);args=parser.parse_args()
env=dict(os.environ,FIELD_DOCS_RUNNER_MODE='always',FIELD_DOCS_RUNNER_PORT=str(args.port))
paint=ROOT/'build/field-paint/release/libfield_paint_jvm.dylib'
if paint.is_file():env.setdefault('AFIELD_PAINT_LIBRARY',str(paint))
# Prefer the caller's configured Node; on Marc's host the bundled runtime is independent of Homebrew.
node=Path('/Users/marc/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/bin/node')
if node.is_file():env.setdefault('FIELD_TEST_NODE',str(node))
result=subprocess.run(['uv','run','--group','docs','python','tools/build_zensical_docs.py','--page','evolving_creatures.md','--skip-build','--no-source-pages'],cwd=ROOT,env=env)
raise SystemExit(result.returncode)
