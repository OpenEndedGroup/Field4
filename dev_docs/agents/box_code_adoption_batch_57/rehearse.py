"""Run the actual Chromium table example and its real constructor/query failure paths."""
from pathlib import Path
import asyncio,importlib.util,json,yaml
R=Path(__file__).resolve().parents[3];H=Path(__file__).resolve().parent
s=importlib.util.spec_from_file_location('rig57',H.parent/'box_code_adoption_batch_26/rehearse.py');n=importlib.util.module_from_spec(s);s.loader.exec_module(n);m=n.m;m.PORT=9719;m.OUT=R/'build/box-code-revision/adoption-57/runtime';m.OUT.mkdir(parents=True,exist_ok=True)
async def main():
 code=yaml.safe_load((R/'dev_docs/assets/headless_browser/headless_browser_table.yaml').read_text())['operations'][0]['create']['properties']['code']
 for phase in ['success','constructor-failure','query-failure']:
  body=code.replace('File.createTempFile("field-headless-browser-", ".html")','File.createTempFile("field-headless-browser-", ".html").also { pages += it }').replace('.use { browser ->','.also { browsers += it }.use { browser ->')
  if phase=='constructor-failure':body=body.replace('HeadlessPuppetBrowser(url = url,','HeadlessPuppetBrowser(projectRoot = File("build/box-code-revision/adoption-57/missing-runtime"), url = url,')
  if phase=='query-failure':body=body.replace('val tableSelector = "#readings"','val tableSelector = "["')
  imports='\n'.join(x for x in body.splitlines() if x.startswith('import '));body='\n'.join(x for x in body.splitlines() if not x.startswith('import '))
  prefix=imports+'\nval pages=mutableListOf<File>()\nval browsers=mutableListOf<HeadlessPuppetBrowser>()\n'
  proof='''check(pages.size==1 && pages.none {it.exists()}) {"temporary page survived"}
for(browser in browsers) {
    val sessionField=browser.javaClass.getDeclaredField("session").also {it.isAccessible=true}
    val session=sessionField.get(browser)
    val processField=session.javaClass.getDeclaredField("process").also {it.isAccessible=true}
    val process=processField.get(session) as Process
    check(!process.isAlive) {"Chromium process survived scope exit"}
}
'''
  if phase=='success':
   await m.program(phase,prefix+body,proof+'check(browsers.size==1);proof("actual Chromium table read; process and file released")',capture=True,channel='output')
  else:
   if phase=='constructor-failure':proof+='check(failure is IllegalArgumentException && failure.message!!.contains("Missing browser texture runtime"));check(browsers.isEmpty())\n'
   else:proof+='check(failure!=null);check(browsers.size==1)\n'
   await m.program(phase,prefix+'val failure=runCatching {\n'+body+'\n}.exceptionOrNull()',proof+'proof("actual failure releases page and any browser process")')
 (H/'behavior.json').write_text(json.dumps({'actualChromiumTable':True,'successRemovesPageAndTerminatesProcess':True,'missingRuntimeConstructorRemovesPage':True,'actualInvalidSelectorRemovesPageAndTerminatesProcess':True,'library':'Electron 44.5.1 from the primary installation, checkout-local producer','limits':'Local HTML only; no network or Vulkan. Constructor failure uses an actually missing project runtime. Diagnostic capture is a separate browser view and does not replace public media.'},indent=2)+'\n')
asyncio.run(main())
