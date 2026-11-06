"""Pointer-lock ownership and late-grant cleanup, independent of a window system."""
import shutil
import subprocess
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


class RelativePointerTest(unittest.TestCase):
    @unittest.skipUnless(shutil.which("node"), "Node is required for the browser helper unit test")
    def test_gesture_lifecycle(self):
        result = subprocess.run(["node", "-e", r"""
const assert = require('node:assert/strict');
const vm = require('node:vm');
const source = require('node:fs').readFileSync(process.argv[1], 'utf8');
function setup() {
    const document = new EventTarget(), window = new EventTarget();
    const requests = [];
    let exits = 0;
    document.body = {requestPointerLock() {
        return new Promise((resolve, reject) => requests.push({resolve, reject}));
    }};
    document.pointerLockElement = null;
    document.exitPointerLock = () => {
        exits++; document.pointerLockElement = null;
        document.dispatchEvent(new Event('pointerlockchange'));
    };
    vm.runInNewContext(source, {document, window});
    const event = {isTrusted:true,pointerType:'mouse'};
    return {document,window,requests,event,api:window.FieldRelativePointer,
        exits:()=>exits, grant(emit=true) {
            document.pointerLockElement=document.body;
            requests.at(-1).resolve();
            if(emit) document.dispatchEvent(new Event('pointerlockchange'));
        }};
}
{
    const e=setup(); let lost=0;
    assert(e.api.begin('slider',e.event,()=>lost++)); e.grant();
    assert(e.api.locked('slider')); assert(!e.api.begin('pan',e.event));
    e.api.end('pan'); assert(e.api.locked('slider'));
    e.api.end('slider'); assert.equal(e.exits(),1); assert.equal(lost,0);
    assert(e.api.begin('pan',e.event));
}
{
    const e=setup(); e.api.begin('slider',e.event); e.api.end('slider');
    assert(!e.api.begin('pan',e.event)); e.grant();
    assert.equal(e.exits(),1); assert.equal(e.document.pointerLockElement,null);
    assert(e.api.begin('pan',e.event));
}
{
    const e=setup(); e.api.begin('slider',e.event); e.grant(false);
    e.api.end('slider'); assert.equal(e.exits(),1);
    assert(e.api.begin('pan',e.event));
}
{
    const e=setup(); let lost=0;
    e.api.begin('slider',e.event,()=>lost++); e.grant();
    e.document.pointerLockElement=null;
    e.document.dispatchEvent(new Event('pointerlockchange'));
    assert.equal(lost,1); assert(!e.api.locked('slider'));
    assert(e.api.begin('pan',e.event));
}
{
    const e=setup(); let lost=0;
    e.api.begin('slider',e.event,()=>lost++); e.window.dispatchEvent(new Event('blur'));
    assert.equal(lost,1); e.grant(); assert.equal(e.exits(),1);
    assert.equal(e.document.pointerLockElement,null);
}
{
    const e=setup(); let lost=0;
    e.api.begin('pan',e.event,()=>lost++); e.grant();
    const escape=new Event('keydown'); escape.key='Escape';
    e.window.dispatchEvent(escape);
    assert.equal(lost,1); assert.equal(e.exits(),1);
    assert.equal(e.document.pointerLockElement,null);
}
{
    const e=setup(); let lost=0;
    e.api.begin('slider',e.event,()=>lost++);
    e.requests[0].reject(new Error('denied'));
    e.document.dispatchEvent(new Event('pointerlockerror'));
    assert.equal(lost,0); assert(e.api.begin('pan',e.event));
}
{
    const e=setup();
    assert(!e.api.begin('touch',{isTrusted:true,pointerType:'touch'}));
    assert(!e.api.begin('synthetic',{isTrusted:false,pointerType:'mouse'}));
    assert.equal(e.requests.length,0);
}
""", str(ROOT / "src/commonMain/resources/relativePointer.js")], capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)


if __name__ == "__main__":
    unittest.main()
