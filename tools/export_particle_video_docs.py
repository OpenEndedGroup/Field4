#!/usr/bin/env python3
"""Publish real Kotlin tracker media and a complete-lifecycle representative track subset."""
import argparse
import gzip
import hashlib
import importlib.util
import json
from pathlib import Path
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('particle_pvt', ROOT/'optional/particle-tracker/pvt.py')
pvt = importlib.util.module_from_spec(spec)
spec.loader.exec_module(pvt)


def sha(path):
    h = hashlib.sha256()
    with Path(path).open('rb') as f:
        for block in iter(lambda:f.read(1024*1024),b''):h.update(block)
    return h.hexdigest()


def _subset(source, target, stride=16):
    # Export whole trajectories, never independent samples or interpolated stand-ins.
    with Path(source).open('rb') as f, gzip.GzipFile(filename=str(target),mode='wb',mtime=0) as out:
        header=pvt.read_header(f)
        count=0
        # First count without allocating the full dense file in memory.
        for r in pvt.read_records(f,header):
            if r[0]==2 and r[1]&64:raise ValueError('Docs export requires merging disabled')
            if r[2]%stride==0:count+=1
        out.write(pvt.HEADER.pack(b'PVTRACK1',1,64,80,header['width'],header['height'],header['seed'],
                                  header['fps'],header['frames'],count,1))
        f.seek(pvt.HEADER.size)
        for r in pvt.read_records(f,header):
            if r[2]%stride==0:out.write(pvt.RECORD.pack(*r))
    return count


def subset(source, target, stride=16):
    target=Path(target)
    with tempfile.TemporaryDirectory(prefix='.particle-subset-',dir=target.parent) as tmp:
        staging=Path(tmp)/'tracks.pvt.gz'
        count=_subset(source,staging,stride)
        staging.replace(target)
    return count


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--run',type=Path,required=True,help='Output directory from ParticleTrackerSmoke')
    parser.add_argument('--source',type=Path,required=True,help='Exact lossless video supplied to the smoke')
    parser.add_argument('--polylines-run',type=Path,help='Output from ParticlePolylineExport for the same tracks')
    args=parser.parse_args()
    run=json.loads((args.run/'run.json').read_text())
    if not run['cacheHitOnRepeat'] or run['device']!='cuda' or run['particlesAtFrame0']<16000:
        raise ValueError('Missing genuine dense CUDA / cached Kotlin validation')
    tracks=Path(run['tracksFile']);receipt=json.loads(tracks.with_name('manifest.json').read_text())
    if sha(tracks)!=receipt['tracks_sha256'] or sha(args.source)!=receipt['identity']['input_sha256']:
        raise ValueError('Tracks/input do not match the verified cache receipt')
    out=ROOT/'public/docs/assets/particle_video';out.mkdir(parents=True,exist_ok=True)
    for name in ('particles.mp4','particles.png','identities.png'):shutil.copyfile(args.run/name,out/name)
    if args.polylines_run:
        drawing_receipt=json.loads((args.polylines_run/'polylines.json').read_text())
        if drawing_receipt['tracks_sha256']!=receipt['tracks_sha256'] or drawing_receipt['draw_points']:
            raise ValueError('Polyline media do not describe the verified tracks / line-only rendering')
        for name in ('polylines.mp4','polylines-full.mp4','polylines-60.png','polylines-180.png','polylines-240.png'):
            shutil.copyfile(args.polylines_run/name,out/name)
    primary='polylines.mp4' if args.polylines_run else 'particles.mp4'
    shutil.copyfile(args.source,out/'source.mkv')
    subprocess.run(['ffmpeg','-v','error','-y','-i',str(args.source),'-an','-c:v','libx264','-crf','18',
        '-pix_fmt','yuv420p','-movflags','+faststart',str(out/'source.mp4')],check=True)
    subprocess.run(['ffmpeg','-v','error','-y','-i',str(out/'source.mp4'),'-i',str(out/primary),
        '-filter_complex','[0:v]scale=800:600:flags=neighbor[a];[a][1:v]hstack=inputs=2[v]',
        '-map','[v]','-an','-c:v','libx264','-crf','18','-pix_fmt','yuv420p','-movflags','+faststart',
        str(out/'comparison.mp4')],check=True)
    subprocess.run(['ffmpeg','-v','error','-y','-ss','7.2','-i',str(out/'comparison.mp4'),
        '-frames:v','1',str(out/'comparison.png')],check=True)
    if args.polylines_run:
        subprocess.run(['ffmpeg','-v','error','-y','-i',str(out/'source.mp4'),'-i',str(out/'polylines.mp4'),
            '-filter_complex','[0:v]scale=800:600:flags=neighbor,lutrgb=r=val*0.35:g=val*0.35:b=val*0.35[bg];[bg][1:v]blend=all_mode=screen[v]',
            '-map','[v]','-an','-c:v','libx264','-crf','18','-pix_fmt','yuv420p','-movflags','+faststart',
            str(out/'polylines-overlay.mp4')],check=True)
    if args.polylines_run:
        subprocess.run(['ffmpeg','-v','error','-y','-ss','7.2','-i',str(out/'polylines-overlay.mp4'),
            '-frames:v','1',str(out/'polylines-overlay.png')],check=True)
    records=subset(tracks,out/'example.pvt.gz')
    public_run={k:v for k,v in run.items() if k!='tracksFile'}
    manifest=dict(source_url='https://commons.wikimedia.org/wiki/File:Loie_Fuller_-_Vue_Lumi%C3%A8re_No_76_-_Serpentine_Dance_-_1896.ogv',
        original_url='https://upload.wikimedia.org/wikipedia/commons/a/a8/Loie_Fuller_-_Vue_Lumi%C3%A8re_No_76_-_Serpentine_Dance_-_1896.ogv',
        source_license='Public domain; Commons PD-old-70-expired / PD-US-expired',
        excerpt_start_seconds=4,excerpt_duration_seconds=12,performer_identity='not verified as Loie Fuller',
        options=receipt['identity']['options'],environment=receipt['identity']['environment'],run=public_run,
        subset=dict(rule='keep complete trajectories with id % 16 == 0',records=records,
                    intended_use='portable reader and scrubber; full 24000-particle movie uses every exported sample'),
        tool_sha256=receipt['identity']['tool'],
        renderer_sha256={name:sha(ROOT/'src/jvmMain/kotlin/afield/stdlib'/name) for name in ('particlevideo.kt','particlevideoview.kt')},
        drawing=dict(style='polylines' if args.polylines_run else 'points',
                     history_frames=25 if args.polylines_run else 0,
                     stable_id_stride=16 if args.polylines_run else 1,
                     full_density_movie='polylines-full.mp4' if args.polylines_run else 'particles.mp4'),
        files={p.name:sha(p) for p in sorted(out.iterdir()) if p.is_file() and p.name!='manifest.json'})
    (out/'manifest.json').write_text(json.dumps(manifest,indent=2)+'\n')
    print(json.dumps(public_run,indent=2))


if __name__=='__main__':main()
