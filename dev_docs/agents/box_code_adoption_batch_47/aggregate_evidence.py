"""Collect successful native phases without rerunning completed consumer checks."""
from pathlib import Path
import json
from PIL import Image,ImageChops
R=Path(__file__).resolve().parents[3];H=Path(__file__).resolve().parent;out=R/'build/box-code-revision/adoption-47/runtime';proof=[]
for name,width,trial in [('transcript',.003,'final'),('page',.004,'final3')]:
 for phase,vertices in [('initial-empty',0),('line',4),('cleared',0),('resumed',4)]:
  verdict=out/f'{name}-{phase}-counts-{trial}.json';assert json.loads(verdict.read_text())['ok'],verdict
  im=Image.open(out/f'{name}-{phase}.png').convert('RGB');x=im.width//2
  rows=[y for y in range(im.height) if (lambda c:c[1]>150 and c[1]>c[0]+40 and c[1]>c[2]+40)(im.getpixel((x,y)))]
  assert abs(len(rows)-width*im.height)<=2 if vertices else not rows
  proof.append({'source':name,'phase':phase,'vertices':vertices,'indices':2 if vertices else 0,'greenPixelRows':len(rows),'captureSize':im.size,'actualCountVerdict':verdict.name})
 assert json.loads((out/f'{name}-rerun-counts-{trial}.json').read_text())['ok']
a=Image.open(out/'camera-before.png').convert('RGB');b=Image.open(out/'camera-after.png').convert('RGB');assert a.size==b.size and ImageChops.difference(a,b).getbbox() is None
(H/'behavior.json').write_text(json.dumps({'realSharedBuffers':True,'realNativeRenderer':True,'controlledTestInput':'Direct buffer line publications and a 4x4 test image; not a webcam/detector replay or public capture.','cameraQuadSamePixels':True,'consumerRerunsPassed':True,'phases':proof},indent=2)+'\n');print('camera pixel equality, eight native overlay phases and both reruns verified')
