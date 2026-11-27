const test = require("node:test");
const assert = require("node:assert/strict");
const os = require("node:os");
const fs = require("node:fs");
const path = require("node:path");
const { profileDirectory, applyProfilePreferences, sessionForDirectory, installProfileWebviews } = require("./web-profiles.cjs");

const partition = directory => "field-path:" + encodeURIComponent(directory);

test("a partition names an absolute, normalised profile directory or nothing", () => {
  assert.equal(profileDirectory(partition("/Users/m/doc/files/web-profiles/github")), "/Users/m/doc/files/web-profiles/github");
  assert.equal(profileDirectory("persist:github"), null);
  assert.equal(profileDirectory(undefined), null);
  assert.equal(profileDirectory(partition("relative/dir")), null);
  assert.equal(profileDirectory(partition("/Users/m/../../etc")), null);
  assert.equal(profileDirectory("field-path:%E0%A4%A"), null);
});

test("a profile webview loses any preload and is sandboxed in the profile's session", () => {
  const prefs = { preload: "/x/field-preload.js", partition: "field-path:...", nodeIntegration: true, contextIsolation: false };
  const profileSession = { name: "profile" };
  applyProfilePreferences(prefs, profileSession);
  assert.equal(prefs.preload, undefined);
  assert.equal(prefs.partition, undefined);
  assert.equal(prefs.session, profileSession);
  assert.equal(prefs.nodeIntegration, false);
  assert.equal(prefs.contextIsolation, true);
  assert.equal(prefs.sandbox, true);
  assert.equal(prefs.webviewTag, false);
});

test("one session per directory, made with the directory, and announced once", () => {
  const dir = path.join(fs.mkdtempSync(path.join(os.tmpdir(), "field-profile-")), "web-profiles", "a");
  const made = [];
  const session = { fromPath: d => ({ path: d }) };
  const first = sessionForDirectory(dir, { session, onNewSession: (_, d) => made.push(d) });
  const again = sessionForDirectory(dir, { session, onNewSession: (_, d) => made.push(d) });
  assert.equal(first, again);
  assert.deepEqual(made, [dir]);
  assert.equal(fs.statSync(dir).isDirectory(), true);
});

test("a webview without a profile partition is refused", () => {
  const handlers = {};
  const contents = { on: (name, fn) => { handlers[name] = fn; } };
  installProfileWebviews(contents, { session: { fromPath: () => ({}) } });
  let prevented = false;
  handlers["will-attach-webview"]({ preventDefault: () => { prevented = true; } }, {}, { partition: "persist:x", src: "https://a" });
  assert.equal(prevented, true);
});

// A stand-in for an Electron session's cookie store.
function fakeCookieSession() {
  const calls = [];
  let changed = null;
  return {
    calls,
    emit: (...args) => changed(...args),
    cookies: {
      get: async filter => { calls.push(["get", filter]); return [{ name: "a", value: "1" }]; },
      set: async cookie => { calls.push(["set", cookie]); },
      remove: async (url, name) => { calls.push(["remove", url, name]); },
      flushStore: async () => { calls.push(["flush"]); },
      on: (event, fn) => { if (event === "changed") changed = fn; },
    },
  };
}

test("profile requests reach the named profile's cookies, and the shared one with no directory", async () => {
  const shared = fakeCookieSession();
  const profiles = {};
  const session = {
    defaultSession: shared,
    fromPath: dir => (profiles[dir] ??= fakeCookieSession()),
  };
  const dir = path.join(fs.mkdtempSync(path.join(os.tmpdir(), "field-profile-")), "web-profiles", "b");
  const { handleProfileRequest } = require("./web-profiles.cjs");
  assert.deepEqual(await handleProfileRequest({ directory: "", action: "cookies-get", params: { url: "https://x", junk: 1, name: "" } }, null, { session }),
    [{ name: "a", value: "1" }]);
  assert.deepEqual(shared.calls, [["get", { url: "https://x" }]]);
  await handleProfileRequest({ directory: dir, action: "cookies-set", params: { url: "https://x", name: "n", value: "v", evil: true } }, null, { session });
  assert.deepEqual(profiles[dir].calls, [["set", { url: "https://x", name: "n", value: "v" }]]);
  await assert.rejects(handleProfileRequest({ directory: dir, action: "cookies-set", params: { name: "n" } }, null, { session }), /url/);
  await assert.rejects(handleProfileRequest({ directory: "relative", action: "cookies-get" }, null, { session }), /Not a profile/);
  await assert.rejects(handleProfileRequest({ directory: "", action: "drop-tables" }, null, { session }), /Unknown/);

  const sent = [];
  const sender = { isDestroyed: () => false, send: (channel, change) => sent.push([channel, change]) };
  await handleProfileRequest({ directory: dir, action: "cookies-watch" }, sender, { session });
  await handleProfileRequest({ directory: dir, action: "cookies-watch" }, sender, { session });
  profiles[dir].emit({}, { name: "n" }, "inserted", false);
  assert.deepEqual(sent, [["field4-web-profile-event", { directory: dir, kind: "cookie", cookie: { name: "n" }, cause: "inserted", removed: false }]]);
});
