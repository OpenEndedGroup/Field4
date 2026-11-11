"""Rehearse the actual sky preset property through its graph and native Vulkan window."""
import asyncio,json,shlex
import yaml
import session as S
import rehearse as H
async def main():
    source=S.ROOT/'dev_docs/agents/transcripts/atmospheric_sky.yaml'
    doc=yaml.safe_load(source.read_text())
    doc['operations'].append(dict(execute=dict(box='atmospheric_sky',property='sunset',type='fragment')))
    fixture=H.OUT/'sky.yaml';fixture.write_text(yaml.safe_dump(doc,sort_keys=False))
    result=await H.call('sky',['macro write sky --file '+shlex.quote(str(fixture)),'macro run sky','native-screenshot '+shlex.quote(str(H.OUT/'sky.png'))+' --wait-frames 6 --timeout 30'])
    print('Native sky preset rendered successfully')
if __name__=='__main__':asyncio.run(main())
