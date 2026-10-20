#!/usr/bin/env python3
"""Measure actual recorded-pose JSON loopback overhead; this does not time inference or display."""
import argparse
import json
from pathlib import Path
import socket
import statistics
import sys
import threading
import time
sys.path.insert(0,str(Path(__file__).resolve().parent/'python'))
from field_motion.recording import Recording
from field_motion.wire import send,receive
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('recording',type=Path)
parser.add_argument('--iterations',type=int,default=300)
parser.add_argument('--batch',type=int,default=4)
parser.add_argument('--output',type=Path)
args=parser.parse_args()
if not 1<=args.iterations<=10000 or not 1<=args.batch<=100:raise ValueError('Invalid benchmark count')
recording=Recording(args.recording)
from itertools import islice
payload=dict(frames=list(islice(recording.frames(),args.batch)),events=[])
server=socket.socket();server.bind(('127.0.0.1',0));server.listen(1)
def echo():
 with server:
  client,_=server.accept()
  with client:
   try:
    while True:send(client,receive(client))
   except EOFError:pass
thread=threading.Thread(target=echo);thread.start()
timings=[]
with socket.create_connection(server.getsockname()) as client:
 for _ in range(args.iterations):
  start=time.perf_counter();send(client,payload);received=receive(client);timings.append((time.perf_counter()-start)*1000)
thread.join(timeout=5)
if received!=payload or thread.is_alive():raise AssertionError('Loopback round trip failed')
timings.sort()
result=dict(transport='127.0.0.1 TCP, bounded length-framed JSON, echo round trip',
 skeletonHash=recording.skeleton['hash'],batchFrames=len(payload['frames']),iterations=args.iterations,
 jsonPayloadBytes=len(json.dumps(payload,separators=(',',':')).encode()),p50Ms=statistics.median(timings),
 p95Ms=timings[int((len(timings)-1)*.95)],inferenceMeasured=False,displayMeasured=False)
text=json.dumps(result,indent=2)+'\n'
if args.output:args.output.write_text(text)
print(text)
