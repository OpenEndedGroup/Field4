"""An old run's Stop must not detach the listener installed by a newer run."""
from pathlib import Path
import asyncio,importlib.util,json
H=Path(__file__).resolve().parent;s=importlib.util.spec_from_file_location('rig78replacement',H/'rehearse.py');r=importlib.util.module_from_spec(s)
# Load definitions without re-running successful whole-session acceptance.
exec((H/'rehearse.py').read_text().rsplit('asyncio.run(main())',1)[0],r.__dict__)
async def main():
 body='''import afield.media.browser.addEventListener
ownedOrca.onNote("replacement") { COUNTER.incrementAndGet() }
ownedBrowser.addEventListener("replacement") { if(it.name=="console")COUNTER.incrementAndGet() }'''
 await r.box('g3-old-listeners78',body.replace('COUNTER','oldConsole'),'g3-owner78b')
 await r.box('g3-new-listeners78',body.replace('COUNTER','newConsole'),'g3-owner78b')
 await r.macro('g3-old-listeners-stop78',[{'stop':{'box':'g3-old-listeners78'}}])
 await r.fragment('g3-new-listeners-proof78','g3-owner78b','''go {
 val before=newConsole.get();val oldBefore=oldConsole.get()
 ownedBrowser.evaluate("console.log('replacement still registered')").await();delay(800)
 check(newConsole.get()>before);check(oldConsole.get()==oldBefore)
 println("OLD_STOP_PRESERVED_NEW_LISTENERS78")
}''')
 await r.wait('g3-new-listeners-survived78','g3-owner78b','OLD_STOP_PRESERVED_NEW_LISTENERS78')
 await r.macro('g3-new-listeners-stop78',[{'stop':{'box':'g3-new-listeners78'}}])
 p=H/'behavior.json';d=json.loads(p.read_text());d['oldStopPreservedNewRunListeners']=True;p.write_text(json.dumps(d,indent=2)+'\n')
asyncio.run(main())
