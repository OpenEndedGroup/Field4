const test = require('node:test');
const assert = require('node:assert/strict');
const { useAngleVulkan } = require('../gpu-policy.cjs');
test('desktop keeps Chromium backend selection unless explicitly requested', () => {
  assert.equal(useAngleVulkan('linux', undefined), false);
  assert.equal(useAngleVulkan('linux', '0'), false);
  assert.equal(useAngleVulkan('linux', '1'), true);
  assert.equal(useAngleVulkan('darwin', '1'), false);
  assert.equal(useAngleVulkan('win32', '1'), false);
});
