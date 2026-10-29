"""Delete the local HTML page even when browser setup fails before a browser exists."""
from pathlib import Path
import json,yaml
R=Path(__file__).resolve().parents[3];H=Path(__file__).resolve().parent
p=R/'dev_docs/assets/headless_browser/headless_browser_table.yaml';before=p.read_text();d=yaml.safe_load(before);old=d['operations'][0]['create']['properties']['code'];s=old
s=s.replace('val localPage = File.createTempFile("field-headless-browser-", ".html")\n','val localPage = File.createTempFile("field-headless-browser-", ".html")\ntry {\n')
s=s.replace('val browser = HeadlessPuppetBrowser(url = url, width = 1000, height = 700, framesPerSecond = 1)\n\ntry {','HeadlessPuppetBrowser(url = url, width = 1000, height = 700, framesPerSecond = 1).use { browser ->')
s=s.replace('} finally {\n    // It owns an operating-system process. Always.\n    browser.close()\n    localPage.delete()\n}', '''    } // use closes Chromium on success or a failed page query.
} finally {
    // Also runs if writing the page or constructing Chromium fails.
    localPage.delete()
}''')
assert s!=old and 'browser.close()' not in s
start=before.index('          import afield.media.browser.HeadlessPuppetBrowser');end=before.index('  - execute:',start);after=before[:start]+''.join('          '+line+'\n' if line else '\n' for line in s.rstrip().splitlines())+before[end:];p.write_text(after);(H/'changes.json').write_text(json.dumps([{'path':str(p.relative_to(R)),'before':before,'after':after}],indent=2)+'\n')
