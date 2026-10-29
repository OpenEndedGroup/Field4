#!/usr/bin/env python3
"""Launch ARDY's unchanged CPU text-encoder service in its own optional environment."""
import argparse
import json
import os
from pathlib import Path
import subprocess

parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--home',type=Path,required=True)
parser.add_argument('--cache',type=Path,help='Hugging Face cache on a volume with room for the 16 GB encoder')
parser.add_argument('--port',type=int,default=9550)
args=parser.parse_args()
home=args.home.resolve();source=home/'upstream/ardy'
pins=json.loads((Path(__file__).resolve().parent/'upstreams.json').read_text())['ardy']
if subprocess.check_output(['git','rev-parse','HEAD'],cwd=source,text=True).strip()!=pins['commit']:raise ValueError('ARDY source pin mismatch')
if not 1<=args.port<=65535:raise ValueError('Invalid port')
env=dict(os.environ,TEXT_ENCODER_DEVICE='cpu',CUDA_VISIBLE_DEVICES='',OMP_NUM_THREADS='8',MKL_NUM_THREADS='8')
if args.cache:
 env['HF_HUB_CACHE']=env['HUGGINGFACE_CACHE_DIR']=str(args.cache.resolve())
# Upstream Gradio only allows returned files in its cwd or system temporary directory.
command=[str(home/'environments/ardy/bin/python'),'scripts/run_text_encoder_server.py','--host','127.0.0.1','--port',str(args.port),'--device','cpu']
subprocess.run(command,cwd=source,env=env,check=True)
