const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const downloads = require("./web-downloads.cjs");

const FIELD = "https://localhost:9092/index.html";
downloads.configure({ fieldUrl: FIELD });

const item = url => ({ getURL: () => url });
const tab = { getType: () => "window" };
const webview = { getType: () => "webview" };

test("a site's download is the page's; Field's own downloads are left alone", () => {
  assert.equal(downloads.isFieldsOwn(item("https://httpbin.org/a.txt"), tab), false);
  assert.equal(downloads.isFieldsOwn(item("https://localhost:9092/export.png"), tab), true);
  assert.equal(downloads.isFieldsOwn(item("blob:https://localhost:9092/7f2c"), tab), true);
  assert.equal(downloads.isFieldsOwn(item("blob:https://github.com/7f2c"), tab), false);
  assert.equal(downloads.isFieldsOwn(item("data:text/plain,hi"), tab), true);
  assert.equal(downloads.isFieldsOwn(item("data:text/plain,hi"), webview), false);
  assert.equal(downloads.isFieldsOwn(item("https://localhost:9092/x"), webview), false);
});

test("names are made unique against the disk and against names already handed out", () => {
  const folder = fs.mkdtempSync(path.join(os.tmpdir(), "field-downloads-"));
  fs.writeFileSync(path.join(folder, "report.pdf"), "x");
  assert.equal(path.basename(downloads.uniquePath(folder, "report.pdf")), "report (1).pdf");
  assert.equal(path.basename(downloads.uniquePath(folder, "report.pdf")), "report (2).pdf");
  assert.equal(path.basename(downloads.uniquePath(folder, "notes")), "notes");
  assert.equal(path.basename(downloads.uniquePath(folder, "notes")), "notes (1)");
});

test("a name cannot climb out of the folder or hide itself", () => {
  const folder = fs.mkdtempSync(path.join(os.tmpdir(), "field-downloads-"));
  const escaped = downloads.uniquePath(folder, "../../etc/passwd");
  assert.equal(path.dirname(escaped), folder);
  assert.equal(path.basename(downloads.uniquePath(folder, ".bashrc")).startsWith("."), false);
  assert.equal(path.basename(downloads.uniquePath(folder, "")), "download");
});
