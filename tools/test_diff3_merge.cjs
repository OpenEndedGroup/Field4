// Run with: node tools/test_diff3_merge.cjs
// Regression for AI4 edits being silently reverted by an open Monaco editor.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const context = { window: {} };
vm.createContext(context);
vm.runInContext(fs.readFileSync(path.join(__dirname, '../src/commonMain/resources/diff3.js'), 'utf8'), context);
const merge = (local, base, remote) => context.window.diff.mergeDigIn(local, base, remote);
const expectMerge = (local, base, remote, expected, conflict = false) => {
    const result = merge(local, base, remote);
    assert.equal(result.conflict, conflict);
    assert.equal(result.result.join('\n'), expected.join('\n'));
};

// The observed function edit inserted at lines 8 and 9 and replaced line 10.
// Lexicographic hunk sorting moved 10 ahead of 8, inventing a conflict and
// returning the unchanged local file. Cover both directions and digit boundaries.
for (const boundary of [10, 100]) {
    const base = Array.from({ length: boundary + 12 }, (_, i) => `val value${i} = 0`);
    const remote = [...base];
    remote[boundary] = `val value${boundary} = 1`;
    remote.splice(boundary - 1, 0, 'val frequency = 0.2');
    remote.splice(boundary - 2, 0, 'val offset = 0.5', 'val amplitude = 0.5');
    expectMerge(base, base, remote, remote);
    expectMerge(remote, base, base, remote);
    expectMerge(remote, base, remote, remote);

    const local = [...base];
    local[2] = 'val userEdit = 42';
    const combined = [...remote];
    combined[2] = local[2];
    expectMerge(local, base, remote, combined);
}

// Real competing changes retain the existing local-wins conflict behavior.
expectMerge(['local'], ['base'], ['remote'], ['local'], true);
console.log('PASS: remote edits, local edits, concurrent disjoint edits, digit boundaries, and real conflicts');
