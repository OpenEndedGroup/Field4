import test from 'node:test';
import assert from 'node:assert/strict';
import { classifyGuideLink as link, boxGuideLink, macroGuideLink, headingSlug, guideAssetUrl, notePath, noteUid, noteFilePath } from '../src/commonMain/resources/documentGuideModel.js';
test('relative files and fragments retain the originating guide directory', () => {
    assert.deepEqual(link('../Camera.md#moving-camera','docs/Enemy.md'),{kind:'guide',path:'Camera.md',hash:'moving-camera'});
    assert.deepEqual(link('#moving-camera','docs/Enemy.md'),{kind:'guide',path:'docs/Enemy.md',hash:'moving-camera'});
    assert.deepEqual(link('assets/my%20image.png','docs/Enemy.md'),{kind:'asset',path:'docs/assets/my image.png',hash:''});
    assert.equal(link('../../private.md','docs/Enemy.md').kind,'invalid');
    for (const path of ['javascript:alert(1)','data:text/html,code','file:///etc/passwd','//other/site','%2fprivate.md','..%2fprivate.md','..\\private.md']) assert.equal(link(path).kind,'invalid',path);
});
test('macro links identify one saved macro and an explicit scope', () => {
    assert.deepEqual(link('field://macro/start'),{kind:'macro',name:'start',scope:'document',project:''});
    assert.deepEqual(link(macroGuideLink('a demo','workspace')),{kind:'macro',name:'a demo',scope:'workspace',project:''});
    assert.deepEqual(link(macroGuideLink('start','project','Sound & space')),{kind:'macro',name:'start',scope:'project',project:'Sound & space'});
    for (const href of ['field://macro/','field://macro/../x','field://macro/a%2Fb','field://macro/%00','field://macro/x?scope=wrong','field://macro/x?scope=document&project=other','field://box/%00']) {
        assert.equal(link(href).kind,'invalid',href);
    }
});
test('copied box links preserve uid, property and a one-based source line', () => {
    assert.deepEqual(link(boxGuideLink('box with spaces','fragment',12)),{kind:'box',uid:'box with spaces',property:'fragment',line:12});
    assert.equal(link('field://box/b?line=-1').line,null);
    assert.deepEqual(link('field://guide/docs/Enemy.md#movement','elsewhere/page.md'),{kind:'guide',path:'docs/Enemy.md',hash:'movement'});
});
test('box notes occupy a path namespace no Markdown file can claim', () => {
    assert.equal(noteUid(notePath('a box uid')),'a box uid');
    for (const path of ['README.md','docs/Enemy.md','box/','','box']) assert.equal(noteUid(path),null,path);
    // The viewer sends this path back for a read; the server must not mistake it for a guide file.
    assert.equal(link(notePath('abc')).kind,'asset');
});
test('a typed note name becomes one accepted Markdown path', () => {
    assert.equal(noteFilePath('Framing decisions'),'Framing-decisions.md');
    assert.equal(noteFilePath('docs/Camera notes'),'docs/Camera-notes.md');
    assert.equal(noteFilePath('guides/x.md'),'guides/x.md');
    assert.equal(noteFilePath(''),'note.md');
    assert.equal(noteFilePath('Already.markdown'),'Already.md');
    // No traversal, no hidden file, no sidecar name, no second directory, and bounded length.
    assert.equal(noteFilePath('  ../../etc/passwd  '),'etc-passwd.md');
    assert.equal(noteFilePath('.hidden'),'hidden.md');
    assert.equal(noteFilePath('trailing_'),'trailing.md');
    assert.equal(noteFilePath('other/thing'),'other-thing.md');
    assert.equal(noteFilePath('x'.repeat(120)),'x'.repeat(80)+'.md');
    for (const typed of ['Framing decisions','docs/Camera notes','  ../../etc/passwd  ','.hidden','Weird!!! ##name','']) {
        const path = noteFilePath(typed);
        assert.equal(link(path).kind,'guide',typed);
        assert.equal(link(path).path,path,typed);
        assert.equal(noteUid(path),null,typed);
    }
});
test('headings and asset queries are deterministic and escaped', () => {
    assert.equal(headingSlug('Dash, retreat & repeat!'),'dash-retreat--repeat');
    const url = new URL(guideAssetUrl('/tmp/My document','docs/my image.png'),'https://localhost');
    assert.equal(url.searchParams.get('document'),'/tmp/My document');
    assert.equal(url.searchParams.get('path'),'docs/my image.png');
});
