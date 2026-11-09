#!/usr/bin/env python3
"""Author the bundled SBS calibration movie; this is source media, not a Field render capture.

Two analytical pinhole views of moving spheres provide real parallax, fine floor lines and
smooth SDR gradients. Field's documentation captures decode and draw this movie through the
real Vulkan player. Rebuild: uv run --with numpy python tools/generate_stereo_video_demo.py
"""
import argparse
import json
import subprocess
from pathlib import Path

import numpy as np


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, default=Path('public/docs/assets/stereo_video/stereo-demo.mkv'))
    args = parser.parse_args()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    width, height, fps, frames = 640, 360, 24, 72
    xx, yy = np.meshgrid((np.arange(width)+.5)/width*2-1, 1-(np.arange(height)+.5)/height*2)
    ray = np.stack([xx*width/height*.55, yy*.55, -np.ones_like(xx)], axis=-1)
    ray /= np.linalg.norm(ray, axis=-1, keepdims=True)
    direction = np.array([-.4,.8,.5]); direction /= np.linalg.norm(direction)
    command = ['ffmpeg','-v','error','-y','-f','rawvideo','-pixel_format','rgb48le','-video_size',f'{width*2}x{height}',
               '-framerate',str(fps),'-i','pipe:0','-an','-vf','scale=in_range=full:out_range=limited:out_color_matrix=bt709',
               '-c:v','ffv1','-level','3','-pix_fmt','yuv420p10le','-color_primaries','bt709','-color_trc','iec61966-2-1','-colorspace','bt709',
               '-color_range','tv',str(args.output)]
    process = subprocess.Popen(command, stdin=subprocess.PIPE)
    try:
        for frame in range(frames):
            phase = 2*np.pi*frame/frames
            images = []
            for eye in [-.032,.032]:
                origin = np.array([eye,.12,0.0])
                image = np.empty((*xx.shape,3)); image[:] = np.array([.004,.008,.025])
                distance = np.full(xx.shape,100.0)
                # Distant floor: fine lines make the speed/temporal-exposure controls visible.
                floor_t = (-.55-origin[1])/np.minimum(ray[...,1],-1e-7)
                floor = origin+ray*floor_t[...,None]
                floor_hit = (ray[...,1]<-.02)&(floor_t<12)
                grid = np.exp(-((np.sin(floor[...,0]*12)/.045)**2))+np.exp(-((np.sin(floor[...,2]*12)/.045)**2))
                floor_color = np.array([.008,.018,.04])+grid[...,None]*np.array([.08,.16,.23])*np.exp(-floor_t[...,None]*.2)
                image = np.where(floor_hit[...,None],floor_color,image)
                distance = np.where(floor_hit,floor_t,distance)
                for center,radius,color in [
                    ([-.62+.14*np.sin(phase),.02,-2.0],.33,[.04,.65,.85]),
                    ([.54,.16+.12*np.cos(phase),-2.6],.43,[.95,.25,.045]),
                    ([.18*np.cos(phase),-.12,-1.45+.15*np.sin(phase)],.16,[.5,.13,.85]),
                ]:
                    center=np.array(center); oc=origin-center
                    b=np.sum(ray*oc,axis=-1); disc=b*b-(np.sum(oc*oc)-radius*radius)
                    t=-b-np.sqrt(np.maximum(disc,0)); hit=(disc>=0)&(t>0)&(t<distance)
                    normal=(origin+ray*t[...,None]-center)/radius
                    diffuse=np.maximum(np.sum(normal*direction,axis=-1),0)
                    rim=(1-np.clip(-np.sum(normal*ray,axis=-1),0,1))**3
                    shade=np.array(color)*(.07+.8*diffuse[...,None])+rim[...,None]*.3
                    image=np.where(hit[...,None],shade,image); distance=np.where(hit,t,distance)
                # sRGB transfer with BT.709 primaries: this is SDR source material, not a display-space screenshot.
                image=np.clip(image,0,1)
                encoded=np.where(image<.0031308,12.92*image,1.055*np.power(image,1/2.4)-.055)
                images.append(np.round(encoded*65535).astype('<u2'))
            process.stdin.write(np.concatenate(images,axis=1).tobytes())
    finally:
        process.stdin.close()
    if process.wait():
        raise SystemExit('ffmpeg failed')
    print(json.dumps({'file':str(args.output),'packed':[width*2,height],'fps':fps,'frames':frames,'seconds':frames/fps,'ipd_metres':.064}))


if __name__ == '__main__':
    main()
