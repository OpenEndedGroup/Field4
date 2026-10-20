#!/usr/bin/env python3
"""Render actual stored poses for review: reference cyan, executed white, tether red.

Offline evidence renderer, independent of the Field renderer; each panel's camera follows its root.
"""
import argparse
import json
import math
from pathlib import Path
import subprocess
import sys
sys.path.insert(0, str(Path(__file__).resolve().parent / 'python'))
from field_motion.recording import Recording
from PIL import Image, ImageDraw, ImageFont


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('recordings', nargs='+', type=Path)
    p.add_argument('--output', type=Path, required=True)
    args = p.parse_args()
    recordings = [Recording(path) for path in args.recordings]
    frames = [list(r.frames()) for r in recordings]
    events = [list(r.events()) for r in recordings]
    rates = [1. / (series[1]['time'] - series[0]['time']) for series in frames]
    if any(abs(rate - rates[0]) > .001 for rate in rates):
        raise ValueError('Review panels must have the same sample rate; resample explicitly first')
    fps = rates[0]
    n = min(map(len, frames)); width = 420 * len(frames); height = 620
    fonts = [ImageFont.truetype('/usr/share/fonts/TTF/DejaVuSans.ttf', size) for size in (19, 15, 12)]
    # Common-source data and explicit camera/projection convention accompany the exported media.
    provenance = dict(renderer='offline Pillow orthographic review', camera='each panel follows executed root XZ; +Y up',
        sources=[dict(path=str(path), skeletonHash=r.skeleton['hash'], metadata=r.metadata) for path, r in zip(args.recordings, recordings)], frames=n,
        reference='cyan positions-only', executed='white', tether='red actual logged point/anchor', fps=fps)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    command = ['ffmpeg', '-y', '-loglevel', 'error', '-f', 'rawvideo', '-vcodec', 'rawvideo', '-pix_fmt', 'rgb24', '-s', f'{width}x{height}',
        '-r', str(fps), '-i', '-', '-an', '-c:v', 'libx264', '-crf', '18', '-pix_fmt', 'yuv420p', str(args.output)]
    process = subprocess.Popen(command, stdin=subprocess.PIPE)
    sheets = []
    try:
        for index in range(n):
            image = Image.new('RGB', (width, height), '#10151e'); draw = ImageDraw.Draw(image)
            for panel, (r, series, log) in enumerate(zip(recordings, frames, events)):
                frame = series[index]; xoff = panel * 420
                root = frame['executed']['root']
                def xy(point):
                    x, y, z = point
                    return (xoff + 210 + 190 * ((x-root[0]) * .82 + (z-root[2]) * .57), 500 - 190 * y)
                draw.text((xoff+18, 16), str(r.metadata.get('mode', r.metadata.get('prompt', 'recording')))[:35].upper(), font=fonts[0], fill='#f4f7fc')
                draw.text((xoff+18, 47), f"{frame['time']:.2f}s  episode {frame['episode']}  frame {frame['index']}", font=fonts[1], fill='#aab7cc')
                draw.text((xoff+18, 73), 'root-following camera · common Y-up metres', font=fonts[2], fill='#7a8ba4')
                draw.line([xy([root[0]-3,0,root[2]]),xy([root[0]+3,0,root[2]])], fill='#334051', width=1)
                draw.line([xy([root[0],0,root[2]-3]),xy([root[0],0,root[2]+3])], fill='#334051', width=1)
                def skeleton(pose, color, thickness):
                    if not pose:return
                    points = [pose['worldPositions'][i:i+3] for i in range(0,len(pose['worldPositions']),3)]
                    for i, parent in enumerate(r.skeleton['parents']):
                        if parent >= 0:draw.line([xy(points[parent]),xy(points[i])],fill=color,width=thickness)
                    for point in points:
                        x,y=xy(point);draw.ellipse((x-2,y-2,x+2,y+2),fill=color)
                skeleton(frame.get('intended'), '#38bed4', 2)
                skeleton(frame['executed'], '#eef1fa', 4)
                tether = [e for e in log if e['type']=='tetherPhysics' and abs(e['time']-(frame['time']-1/30)) < .018]
                if tether:
                    e=tether[-1];a=xy(e['anchor']);b=xy(e['point'])
                    draw.line([a,b],fill='#ff596b',width=2);draw.ellipse((a[0]-5,a[1]-5,a[0]+5,a[1]+5),outline='#ff596b',width=2)
                    draw.text((xoff+18, 550), f"tether {e['forceN']:.1f} N · error {e['errorM']:.2f} m",font=fonts[1],fill='#ff8794')
                label = 'raw kinematic output · no postprocessing' if r.metadata.get('backend') == 'ardy' else f"tracking {frame['diagnostics'].get('trackingErrorM',0)*100:.1f} cm · stock physics/controller"
                draw.text((xoff+18, 576), label,font=fonts[2],fill='#aab7cc')
                if panel:draw.line([(xoff,0),(xoff,height)],fill='#2a3546',width=1)
            process.stdin.write(image.tobytes())
            if index in (30,75,105,135,165):sheets.append(image.copy())
        process.stdin.close()
        if process.wait():raise RuntimeError('ffmpeg failed')
    finally:
        if process.poll() is None:process.kill();process.wait()
    if sheets:
        sheet=Image.new('RGB',(width,height*len(sheets)))
        for i,image in enumerate(sheets):sheet.paste(image,(0,i*height))
        sheet.save(args.output.with_suffix('.png'))
    args.output.with_suffix('.provenance.json').write_text(json.dumps(provenance,indent=2)+'\n')
    print(args.output)


if __name__=='__main__':main()
