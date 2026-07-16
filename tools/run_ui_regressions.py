#!/usr/bin/env python3
"""Run browser-level Field UI regressions through tools/field_backdoor.py.

The tests here cover command-palette and controller-page behavior that is too
frontend-specific for the snippet-oriented FIELD_TEST runner. Each test returns
structured assertion data through page-eval and captures a representative
screenshot. Passing screenshots are retained as a rolling history; the newest
failure screenshot is kept separately for quick inspection.
"""

from __future__ import annotations

import argparse
import json
import re
import shutil
import subprocess
import sys
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Any


TOOLS_ROOT = Path(__file__).resolve().parent
REPO_ROOT = TOOLS_ROOT.parent
BACKDOOR_PATH = TOOLS_ROOT / "field_backdoor.py"
BUILD_ROOT = REPO_ROOT / "build" / "ui-regressions"
GENERATED_ROOT = BUILD_ROOT / "generated"
ARTIFACT_ROOT = BUILD_ROOT / "artifacts"
RESULTS_PATH = BUILD_ROOT / "results.json"

DEFAULT_TIMEOUT = 30.0
PLAYWRIGHT_TIMEOUT = 180.0
PASS_SCREENSHOT_LIMIT = 5


class UiRegressionError(RuntimeError):
    pass


@dataclass(frozen=True)
class UiRegression:
    id: str
    description: str
    commands: list[str]
    screenshot: str
    document: str | None = None


STACK_MARKER_SNIPPET = """\
println(1)
"""

EDITABLE_LINES_SNIPPET = """\
import afield.graphics.*
import afield.stdlib.*

val foo = fline {
    color = vec(0.2, 0.8, 1.0, 1.0)
    thickness = 2.0
    moveTo(vec(0.0, 0.0, 0.0))
    lineTo(vec(160.0, 80.0, 0.0))
    lineTo(vec(280.0, 20.0, 0.0))
}
lines(foo, editable = true)
"""

STATIC_LINES_SNIPPET = """\
import afield.graphics.*
import afield.stdlib.*

val staticLine = fline {
    color = vec(1.0, 0.55, 0.2, 1.0)
    thickness = 3.0
    moveTo(vec(0.0, 0.0, 0.0))
    lineTo(vec(80.0, 130.0, 0.0))
    lineTo(vec(180.0, 40.0, 0.0))
    lineTo(vec(300.0, 160.0, 0.0))
}
lines(staticLine, w = 360.0, h = 220.0, fitToSize = true)
"""

CONTROLLER_CONTROL_GROUP_SNIPPET = """\
import afield.stdlib.linalg.Vector2f
import afield.stdlib.linalg.Vector4f

var gain = 0.35
var enabled = true
var label = "alpha"
var velocity = Vector2f(0.25f, -0.5f)
var tint = Vector4f(0.2f, 0.7f, 1.0f, 1.0f)

controlGroup("controls") {
    slider("gain", min = 0.0, max = 1.0, step = 0.01, initialValue = gain) { gain = it }
    checkbox("enabled", initialValue = enabled) { enabled = it }
    stringInput("label", initialValue = label) { label = it }
    vec2Control(
        "velocity",
        initialValue = velocity,
        xRange = -1.0 to 1.0,
        yRange = -1.0 to 1.0,
        step = 0.05
    ) { velocity = it }
    colorPicker("tint", initialValue = tint) { tint = it }
}

display("summary", \"\"\"
<div id="controllers-control-summary" style="padding:14px;background:#18252a;color:#d6fff8;border:1px solid #5ac8b3;">
  native controller controls ready
</div>
\"\"\".trimIndent())
"""

SELECTION_SCREENSHOT_SNIPPET = """\
println("selection screenshot target")
display("main", \"\"\"
<div id="selection-screenshot-output" style="padding:16px;background:#20222d;color:#fff;border:1px solid #8fa7ff;">
  screenshot backdoor target output
</div>
\"\"\".trimIndent())
"""

HISTORY_PANEL_SNIPPET = """\
println("history panel regression")
display("history", \"\"\"
<div id="history-panel-output" style="padding:14px;background:#1b2026;color:#dff3ff;border:1px solid #6fb4dd;">
  history panel output
</div>
\"\"\".trimIndent())
"""

MACRO_TRANSCRIPT_PANEL_SNIPPET = """\
println("macro transcript panel regression")
display("macro", \"\"\"
<div id="macro-transcript-panel-output" style="padding:14px;background:#202018;color:#fff1c2;border:1px solid #d7a84b;">
  macro transcript output
</div>
\"\"\".trimIndent())
"""

MACRO_TRANSCRIPT_PANEL_MACRO = """\
name: ui-regression-panel
description: Browser regression fixture for the macro recorder panel.
operations:
  - create:
      box: ui_regression_macro_box
      name: UI Regression Macro Box
      frame:
        x: 120.0
        y: 120.0
        w: 260.0
        h: 120.0
      properties:
        code: |
          println("macro panel fixture")
  - launch:
      box: ui_regression_macro_box
      property: code
      line: 0
      code: |
        println("macro panel fixture launch")
      singleLine: false
      type: launch
      strand: default
  - wait:
      frames: 1
"""

MACRO_TRANSCRIPT_PANEL_SLOW_MACRO = """\
name: ui-regression-panel-slow
description: Browser regression fixture for macro playback progress delivery.
operations:
  - create:
      box: ui_regression_macro_slow_box
      name: UI Regression Slow Macro Box
      frame:
        x: 420.0
        y: 120.0
        w: 260.0
        h: 120.0
      properties:
        code: |
          println("slow macro fixture")
  - launch:
      box: ui_regression_macro_slow_box
      property: code
      line: 0
      code: |
        println("slow macro fixture launch")
      singleLine: false
      type: launch
      strand: default
  - wait:
      millis: 3000
"""

MACRO_TRANSCRIPT_PANEL_BATCH_MACRO = """\
name: ui-regression-panel-batch
description: Browser regression fixture for macro launch batch completion.
operations:
  - create:
      box: ui_regression_macro_batch_a
      name: UI Regression Batch Macro A
      frame:
        x: 120.0
        y: 280.0
        w: 260.0
        h: 120.0
      properties:
        code: |
          println("batch macro fixture a")
  - create:
      box: ui_regression_macro_batch_b
      name: UI Regression Batch Macro B
      frame:
        x: 420.0
        y: 280.0
        w: 260.0
        h: 120.0
      properties:
        code: |
          println("batch macro fixture b")
  - launch:
      box: ui_regression_macro_batch_a
      property: code
      line: 0
      code: |
        Thread.sleep(1200)
        println("batch macro launch a")
      singleLine: false
      type: launch
      strand: default
  - launch:
      box: ui_regression_macro_batch_b
      property: code
      line: 0
      code: |
        println("batch macro launch b")
      singleLine: false
      type: launch
      strand: default
  - wait:
      millis: 1
"""

INLINE_VISUALIZERS_SNIPPET = """\
var tick = 0.0

val arp = noteProcess {
    val chord = listOf(48, 55, 60, 64)
    var step = 0
    while (true) {
        note(
            pitch = chord[step % chord.size],
            duration = 0.18,
            velocity = 0.85,
            label = "n$step"
        )
        rest(0.25)
        step++
    }
}

println("inline visualizers ready")

var playhead = 0.0

loop {
    tick += 0.4
    val value = sin(tick * 0.22) * 0.7 +
        cos(tick * 0.11) * 0.2 +
        tick * 0.01

    __m("histogram").histogram(value, dur = 96)
    __m("trail").history(value, dur = 48)
    __m("summary").stats(value, dur = 48)
    __m("arp").pianoRoll(arp.at(playhead), widthPx = 480, heightPx = 96)
    playhead += 0.08
    frame()
}
"""

EDITOR_SYNC_INITIAL_SNIPPET = """\
println("editor sync initial")
"""

OUTPUT_AREA_BASICS_SNIPPET = """\
display("main", \"\"\"
<div id="ui-output-basic" data-state="first">
  first output should be replaced
</div>
\"\"\".trimIndent())

display("main", \"\"\"
<div id="ui-output-basic" data-state="second" style="padding:16px;background:#10242d;color:#7ee7ff;border:1px solid #49c7e8;font-family:system-ui,sans-serif;">
  output replacement ok
  <span id="ui-output-token">token-42</span>
</div>
<script>
  window.__uiOutputScriptRan = (window.__uiOutputScriptRan || 0) + 1;
  document.body.setAttribute("data-ui-output-script-ran", String(window.__uiOutputScriptRan));
</script>
\"\"\".trimIndent())
"""

COMMAND_PALETTE_THIS_BINDING_JS = r"""
const delay = (ms) => new Promise(resolve => setTimeout(resolve, ms));
const ed = window.globalEditor;
const errors = [];
const onError = (event) => errors.push(String(event.error && (event.error.stack || event.error.message) || event.message || event));
window.addEventListener("error", onError);
try {
  if (!ed) return { ok: false, reason: "globalEditor missing", errors };
  ed.focus();
  window.__uiRegressionActionThis = null;
  window.__uiRegressionActionRan = false;
  ed.addAction({
    id: "ui-regression-this-binding",
    label: "UI Regression This Binding",
    run: function(editorArg) {
      window.__uiRegressionActionThis = this && this.id;
      window.__uiRegressionActionRan = true;
      if (!editorArg || editorArg !== ed) throw new Error("editor argument was not the active editor");
      ed.focus();
    },
  });
  window.openFieldCommandPalette({ includeEditor: true, editor: ed });
  await delay(250);
  const input = document.querySelector(".afield-palette-input");
  if (!input) return { ok: false, reason: "palette input missing", errors };
  input.value = "ui regression this binding";
  input.dispatchEvent(new Event("input", { bubbles: true }));
  await delay(250);
  const rows = [...document.querySelectorAll(".afield-palette-row")].map(row => row.textContent.trim());
  input.dispatchEvent(new KeyboardEvent("keydown", { key: "Enter", bubbles: true, cancelable: true }));
  await delay(250);
  const palette = document.querySelector(".afield-palette");
  const result = {
    ran: !!window.__uiRegressionActionRan,
    thisId: window.__uiRegressionActionThis,
    rows: rows.slice(0, 5),
    focus: ed.hasTextFocus(),
    paletteVisible: palette ? getComputedStyle(palette).display !== "none" : null,
    errors,
  };
  result.ok = result.ran &&
    result.thisId === "ui-regression-this-binding" &&
    result.focus === true &&
    result.paletteVisible === false &&
    result.errors.length === 0;
  return result;
} finally {
  window.removeEventListener("error", onError);
}
"""

MONACO_CONTEXT_MENU_ACTIONS_JS = r"""
const delay = (ms) => new Promise(resolve => setTimeout(resolve, ms));
const errors = [];
const onError = (event) => errors.push(String(event.error && (event.error.stack || event.error.message) || event.message || event));
window.addEventListener("error", onError);
try {
  await delay(200);
  const labels = [...document.querySelectorAll(".context-view .action-label, .monaco-menu .action-label")]
    .filter((node) => {
      const rect = node.getBoundingClientRect();
      const style = getComputedStyle(node);
      return rect.width > 0 && rect.height > 0 && style.visibility !== "hidden" && style.display !== "none";
    })
    .map((node) => node.textContent.replace(/\s+/g, " ").trim())
    .filter(Boolean);

  const counts = new Map();
  for (const label of labels) counts.set(label, (counts.get(label) || 0) + 1);
  const duplicates = [...counts.entries()]
    .filter(([label, count]) => count > 1 && (label === "Go to Paired Shader Definition" || label.startsWith("Edit: ")))
    .map(([label, count]) => ({ label, count }));
  const editLabels = labels.filter((label) => label.startsWith("Edit: "));
  const ed = window.globalEditor;
  const propertyIds = [
    "code", "vertex", "fragment", "stackSL", "vertex_fragment", "compute", "task", "mesh",
    "raygen", "miss", "closesthit", "anyhit", "intersection", "callable",
    "vertex_s", "fragment_s", "compute_s", "task_s", "mesh_s", "raygen_s", "miss_s",
    "closesthit_s", "anyhit_s", "intersection_s", "callable_s",
    "preamble", "introduction", "attic", "notes", "metadata", "contents"
  ].map((property) => `switch-property-${property}`);
  const fieldActionIds = ["paired-shader-definition", ...propertyIds];
  const fieldActions = ed && typeof ed.getAction === "function"
    ? fieldActionIds
      .map((id) => {
        const action = ed.getAction(id);
        return action ? { requestedId: id, id: String(action.id || ""), label: String(action.label || "") } : null;
      })
      .filter(Boolean)
    : [];
  const actionCounts = new Map();
  for (const action of fieldActions) actionCounts.set(action.requestedId, (actionCounts.get(action.requestedId) || 0) + 1);
  const actionDuplicates = [...actionCounts.entries()]
    .filter(([_id, count]) => count > 1)
    .map(([id, count]) => ({ id, count }));
  const propertyHandleCount = ed && ed.afieldPropertyActionDisposables
    ? ed.afieldPropertyActionDisposables.length
    : 0;
  const result = {
    labels: labels.slice(0, 80),
    editLabelCount: editLabels.length,
    duplicates,
    fieldActionCount: fieldActions.length,
    missingActions: fieldActionIds.filter((id) => !actionCounts.has(id)),
    propertyHandleCount,
    actionDuplicates,
    errors,
  };
  result.ok = duplicates.length === 0 &&
    actionDuplicates.length === 0 &&
    fieldActions.length === fieldActionIds.length &&
    propertyHandleCount === propertyIds.length &&
    errors.length === 0;
  if (!result.ok && fieldActions.length === 0) result.reason = "Monaco editor actions were not available";
  return result;
} finally {
  window.removeEventListener("error", onError);
}
"""

OUTPUT_AREA_BASICS_JS = r"""
const delay = (ms) => new Promise(resolve => setTimeout(resolve, ms));
const errors = [];
const onError = (event) => errors.push(String(event.error && (event.error.stack || event.error.message) || event.message || event));
window.addEventListener("error", onError);
const waitFor = async (predicate, timeoutMs = 8000) => {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    const value = predicate();
    if (value) return value;
    await delay(75);
  }
  return predicate();
};
try {
  const boxes = [...document.querySelectorAll(".box")];
  const selected = window.currentSelectedCode && window.currentSelectedCode[0] ? window.currentSelectedCode[0] : null;
  const box = selected ||
    (window.selectedList && window.selectedList[0]) ||
    boxes.find((candidate) => String(candidate.textContent || "").includes("OutputAreaBasicsRegression")) ||
    boxes.at(-1) ||
    null;
  const uid = box ? (box.uid || box.getAttribute("uid") || box.getAttribute("data-uid") || "") : "";
  if (!box || !uid) return { ok: false, reason: "box not found", uid, boxCount: boxes.length, errors };

  if (typeof window.selectBox === "function") window.selectBox(box);
  if (typeof window.selectionContextChanged === "function") window.selectionContextChanged();

  const outputArea = await waitFor(() => box.querySelector(".outputArea"), 6000);
  const contain = outputArea && outputArea.querySelector(".disclosure-group");
  const inter = contain && typeof window.disclosureInterface === "function" ? window.disclosureInterface(contain) : null;
  await waitFor(() => contain && contain.querySelectorAll(".disclosure").length > 0, 6000);
  if (outputArea) {
    outputArea.style.visibility = "visible";
    outputArea.style.height = "auto";
  }
  const ids = contain ? [...contain.querySelectorAll(".disclosure")].map((node) => node.__disclosureId || "") : [];
  if (inter && typeof inter.open === "function") {
    ids.forEach((id) => id && inter.open(id));
  }
  await delay(250);

  const marker = box.querySelector("#ui-output-basic");
  const markers = box.querySelectorAll("#ui-output-basic");
  const token = box.querySelector("#ui-output-token");
  const content = marker ? marker.closest(".disclosure-content") : null;
  const outputRect = outputArea ? outputArea.getBoundingClientRect() : { width: 0, height: 0 };
  const contentRect = content ? content.getBoundingClientRect() : { width: 0, height: 0 };
  const text = content ? String(content.textContent || "") : "";
  const fieldErrors = [...box.querySelectorAll(".error, .field-error")].map((node) => String(node.textContent || "").trim()).filter(Boolean);
  const scriptRuns = Number(document.body.getAttribute("data-ui-output-script-ran") || window.__uiOutputScriptRan || 0);

  const result = {
    uid,
    ids,
    disclosureCount: contain ? contain.querySelectorAll(".disclosure").length : 0,
    markerCount: markers.length,
    markerState: marker ? marker.getAttribute("data-state") : "",
    tokenText: token ? token.textContent : "",
    hasFirstText: text.includes("first output should be replaced"),
    hasSecondText: text.includes("output replacement ok"),
    scriptRuns,
    outputWidth: Math.round(outputRect.width || 0),
    outputHeight: Math.round(outputRect.height || 0),
    contentWidth: Math.round(contentRect.width || 0),
    contentHeight: Math.round(contentRect.height || 0),
    fieldErrors,
    errors,
  };
  result.ok = result.disclosureCount >= 1 &&
    result.markerCount === 1 &&
    result.markerState === "second" &&
    result.tokenText === "token-42" &&
    result.hasSecondText &&
    !result.hasFirstText &&
    result.scriptRuns > 0 &&
    result.contentWidth > 0 &&
    result.contentHeight > 0 &&
    result.fieldErrors.length === 0 &&
    result.errors.length === 0;
  return result;
} finally {
  window.removeEventListener("error", onError);
}
"""

TOAST_RESTACK_JS = r"""
const delay = (ms) => new Promise(resolve => setTimeout(resolve, ms));
const errors = [];
const onError = (event) => errors.push(String(event.error && (event.error.stack || event.error.message) || event.message || event));
window.addEventListener("error", onError);
const waitFor = async (predicate, timeoutMs = 5000) => {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    const value = predicate();
    if (value) return value;
    await delay(50);
  }
  return predicate();
};
const toastBottoms = () => [...document.querySelectorAll(".field-toast")]
  .filter((node) => !node.classList.contains("field-toast-exit"))
  .map((node) => Number.parseFloat(getComputedStyle(node).bottom || node.style.bottom || "0") || 0);
try {
  [...document.querySelectorAll(".bubble, .field-toast")].forEach((node) => node.remove());
  if (typeof window.notify !== "function") return { ok: false, reason: "notify missing", errors };

  for (let i = 0; i < 5; i++) {
    window.notify(`toast ${i}`, `toast-${i}`);
  }
  await waitFor(() => toastBottoms().length >= 5, 2000);
  await delay(300);
  window.notify("toast 5", "toast-5");
  await waitFor(() => toastBottoms().length >= 5, 2000);
  await delay(350);

  const bottoms = toastBottoms().sort((a, b) => a - b);
  const first = bottoms[0] ?? 0;
  const deltas = bottoms.slice(1).map((value, index) => value - bottoms[index]);
  const loading = document.createElement("div");
  loading.className = "field-startup-progress";
  loading.innerHTML = '<div class="field-startup-progress-title">Loading document</div><div class="field-startup-progress-detail"></div><div class="field-startup-progress-track"><div class="field-startup-progress-bar"></div></div>';
  document.body.appendChild(loading);
  const loadingStyle = getComputedStyle(loading);
  const result = {
    count: bottoms.length,
    bottoms,
    first,
    deltas,
    loadingLeft: Number.parseFloat(loadingStyle.left || "0") || 0,
    loadingBottom: Number.parseFloat(loadingStyle.bottom || "0") || 0,
    errors,
  };
  loading.remove();
  result.ok = result.count >= 4 &&
    Math.abs(first - 10) <= 1.5 &&
    result.deltas.every((delta) => delta >= 6) &&
    Math.abs(result.loadingLeft - 10) <= 1.5 &&
    Math.abs(result.loadingBottom - 10) <= 1.5 &&
    result.errors.length === 0;
  return result;
} finally {
  window.removeEventListener("error", onError);
}
"""

COMMAND_PALETTE_SMOKE_SWEEP_JS = r"""
const delay = (ms) => new Promise(resolve => setTimeout(resolve, ms));
const errors = [];
const onError = (event) => errors.push(String(event.error && (event.error.stack || event.error.message) || event.message || event));
window.addEventListener("error", onError);

const paletteVisible = () => {
  const palette = document.querySelector(".afield-palette");
  return palette ? getComputedStyle(palette).display !== "none" : false;
};
const boxUid = (box) => box && (box.uid || box.getAttribute("uid") || box.getAttribute("data-uid") || "");
const boxes = () => [...document.querySelectorAll(".box")];
const waitFor = async (predicate, timeoutMs = 3000) => {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    const value = predicate();
    if (value) return value;
    await delay(50);
  }
  return predicate();
};

async function runPaletteCommand(query, options) {
  window.openFieldCommandPalette(options || {});
  await delay(250);
  const input = document.querySelector(".afield-palette-input");
  if (!input) return { ok: false, query, reason: "palette input missing" };
  input.value = query;
  input.dispatchEvent(new Event("input", { bubbles: true }));
  await delay(300);
  const rows = [...document.querySelectorAll(".afield-palette-row")].map(row => row.textContent.trim());
  input.dispatchEvent(new KeyboardEvent("keydown", { key: "Enter", bubbles: true, cancelable: true }));
  await delay(400);
  return { ok: true, query, rows: rows.slice(0, 5), paletteVisible: paletteVisible() };
}

try {
  const ed = window.globalEditor;
  if (!ed) return { ok: false, reason: "globalEditor missing", errors };
  if (typeof window.openFieldCommandPalette !== "function") return { ok: false, reason: "openFieldCommandPalette missing", errors };

  const initialBoxes = boxes();
  const seed = (window.selectedList && window.selectedList[0]) || initialBoxes.at(-1);
  if (seed && typeof window.selectBox === "function") window.selectBox(seed);
  if (typeof window.selectionContextChanged === "function") window.selectionContextChanged();
  ed.focus();

  const beforeNewBoxCount = boxes().length;
  const documentCommand = await runPaletteCommand("new box", { includeEditor: false, editor: ed });
  const afterNewBoxCount = await waitFor(() => boxes().length > beforeNewBoxCount ? boxes().length : 0);

  const target = seed || boxes().at(-1);
  const targetUid = boxUid(target);
  if (target && typeof window.selectBox === "function") window.selectBox(target);
  if (typeof window.selectionContextChanged === "function") window.selectionContextChanged();
  await delay(250);

  const propertyCommand = await runPaletteCommand("switch to metadata", { includeEditor: false, editor: ed });
  await waitFor(() => (window.selectedProperty || window.currentSelectedProperty) === "metadata", 3000);
  const selectedProperty = window.selectedProperty || window.currentSelectedProperty || "";

  window.__uiRegressionSmokeActionRan = false;
  window.__uiRegressionSmokeActionThis = null;
  ed.addAction({
    id: "ui-regression-smoke-action",
    label: "UI Regression Smoke Action",
    run: function(editorArg) {
      window.__uiRegressionSmokeActionRan = editorArg === ed;
      window.__uiRegressionSmokeActionThis = this && this.id;
      ed.focus();
    },
  });
  const editorCommand = await runPaletteCommand("ui regression smoke action", { includeEditor: true, editor: ed });

  const result = {
    beforeNewBoxCount,
    afterNewBoxCount,
    targetUid,
    selectedProperty,
    actionRan: !!window.__uiRegressionSmokeActionRan,
    actionThis: window.__uiRegressionSmokeActionThis,
    editorFocus: ed.hasTextFocus(),
    documentCommand,
    propertyCommand,
    editorCommand,
    paletteVisible: paletteVisible(),
    errors,
  };
  result.ok = result.afterNewBoxCount > result.beforeNewBoxCount &&
    result.selectedProperty === "metadata" &&
    result.actionRan &&
    result.actionThis === "ui-regression-smoke-action" &&
    result.editorFocus === true &&
    result.paletteVisible === false &&
    result.documentCommand.paletteVisible === false &&
    result.propertyCommand.paletteVisible === false &&
    result.editorCommand.paletteVisible === false &&
    result.errors.length === 0;
  return result;
} finally {
  window.removeEventListener("error", onError);
}
"""

EDITOR_OPEN_EDIT_SYNC_JS = r"""
const delay = (ms) => new Promise(resolve => setTimeout(resolve, ms));
const errors = [];
const onError = (event) => errors.push(String(event.error && (event.error.stack || event.error.message) || event.message || event));
window.addEventListener("error", onError);
const waitFor = async (predicate, timeoutMs = 5000) => {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    const value = predicate();
    if (value) return value;
    await delay(75);
  }
  return predicate();
};
const normalize = (value) => String(value || "").replace(/\r\n/g, "\n");
try {
  const ed = window.globalEditor;
  const selected = window.currentSelectedCode && window.currentSelectedCode[0] ? window.currentSelectedCode[0] : null;
  const uid = selected && (selected.uid || selected.getAttribute?.("uid")) || "";
  if (!ed) return { ok: false, reason: "globalEditor missing", errors };
  if (!selected || !uid) return { ok: false, reason: "selected box missing", errors };
  if (typeof window.switchToProperty !== "function") return { ok: false, reason: "switchToProperty missing", uid, errors };

  const codeText = "val uiRegressionEditorSync = 42\nprintln(\"editor-sync \" + uiRegressionEditorSync)\n";
  const metadataText = "title: editor sync regression\nkind: ui-regression\n";

  ed.focus();
  ed.setValue(codeText);
  const codeSynced = await waitFor(() => normalize(selected.code) === codeText ? true : false);

  window.switchToProperty(uid, "metadata");
  const metadataOpen = await waitFor(() =>
    (window.selectedProperty === "metadata" || window.currentSelectedProperty === "metadata") &&
    normalize(ed.getModel().getValue()) === normalize(selected.metadata || "")
  );

  ed.setValue(metadataText);
  const metadataSynced = await waitFor(() => normalize(selected.metadata) === metadataText ? true : false);

  window.switchToProperty(uid, "code");
  const codeReopened = await waitFor(() =>
    (window.selectedProperty === "code" || window.currentSelectedProperty === "code") &&
    normalize(ed.getModel().getValue()) === codeText
  );

  const result = {
    uid,
    codeSynced: !!codeSynced,
    metadataOpen: !!metadataOpen,
    metadataSynced: !!metadataSynced,
    codeReopened: !!codeReopened,
    selectedProperty: window.selectedProperty || "",
    currentSelectedProperty: window.currentSelectedProperty || "",
    modelValue: normalize(ed.getModel().getValue()),
    selectedCode: normalize(selected.code),
    selectedMetadata: normalize(selected.metadata),
    editorFocus: ed.hasTextFocus(),
    errors,
  };
  result.ok = result.codeSynced &&
    result.metadataOpen &&
    result.metadataSynced &&
    result.codeReopened &&
    result.modelValue === codeText &&
    result.selectedCode === codeText &&
    result.selectedMetadata === metadataText &&
    result.editorFocus === true &&
    result.errors.length === 0;
  return result;
} finally {
  window.removeEventListener("error", onError);
}
"""

INSERT_STACK_MARKER_JS = r"""
const delay = (ms) => new Promise(resolve => setTimeout(resolve, ms));
const ed = window.globalEditor;
const errors = [];
const onError = (event) => errors.push(String(event.error && (event.error.stack || event.error.message) || event.message || event));
window.addEventListener("error", onError);
try {
  if (!ed) return { ok: false, reason: "globalEditor missing", errors };
  ed.setValue("println(1)");
  ed.setPosition({ lineNumber: 1, column: 1 });
  ed.focus();
  const before = ed.getModel().getValue();
  window.openFieldCommandPalette({ includeEditor: true, editor: ed });
  await delay(250);
  const input = document.querySelector(".afield-palette-input");
  if (!input) return { ok: false, reason: "palette input missing", before, errors };
  input.value = "insert stack mark";
  input.dispatchEvent(new Event("input", { bubbles: true }));
  await delay(250);
  const rows = [...document.querySelectorAll(".afield-palette-row")].map(row => row.textContent.trim());
  input.dispatchEvent(new KeyboardEvent("keydown", { key: "Enter", bubbles: true, cancelable: true }));
  await delay(250);
  const after = ed.getModel().getValue();
  const palette = document.querySelector(".afield-palette");
  const result = {
    rows: rows.slice(0, 5),
    before,
    after,
    changed: before !== after,
    inserted: /__m\("[A-Za-z0-9-]+"\)/.test(after),
    focus: ed.hasTextFocus(),
    paletteVisible: palette ? getComputedStyle(palette).display !== "none" : null,
    errors,
  };
  result.ok = result.changed &&
    result.inserted &&
    result.focus === true &&
    result.paletteVisible === false &&
    result.errors.length === 0;
  return result;
} finally {
  window.removeEventListener("error", onError);
}
"""

CONTROLLERS_EDITABLE_LINES_JS = r"""
const delay = (ms) => new Promise(resolve => setTimeout(resolve, ms));
const errors = [];
const onError = (event) => errors.push(String(event.error && (event.error.stack || event.error.message) || event.message || event));
window.addEventListener("error", onError);
try {
  const boxes = [...document.querySelectorAll(".box")];
  const selected = window.selectedList && window.selectedList[0] ? window.selectedList[0] : null;
  const box = selected || boxes.find((candidate) => String(candidate.textContent || "").includes("EditableLinesRegression")) || boxes.at(-1) || null;
  const uid = box ? (box.uid || box.getAttribute("uid") || box.getAttribute("data-uid") || "") : "";
  const result = { uid, fieldHref: window.location.href, boxCount: boxes.length };
  if (!box || !uid) return { ok: false, reason: "box not found", ...result, errors };

  if (typeof window.selectBox === "function") window.selectBox(box);
  if (typeof window.selectionContextChanged === "function") window.selectionContextChanged();
  await delay(700);

  const url = new URL(window.location.href);
  const documentName = url.searchParams.get("file") || "scratch";
  const panel = document.createElement("div");
  panel.id = "__ui_regression_controller_preview";
  panel.style.cssText = [
    "position:fixed",
    "right:24px",
    "top:24px",
    "width:min(520px,46vw)",
    "height:min(900px,88vh)",
    "z-index:2147483000",
    "background:#0b0d0f",
    "border:1px solid rgba(255,255,255,0.28)",
    "box-shadow:0 18px 50px rgba(0,0,0,0.42)",
    "overflow:hidden"
  ].join(";");
  const iframe = document.createElement("iframe");
  iframe.id = "__ui_regression_controller_iframe";
  iframe.src = `/controllers?page=outputs&file=${encodeURIComponent(documentName)}`;
  iframe.style.cssText = "width:100%;height:100%;border:0;display:block;background:#050505;";
  panel.replaceChildren(iframe);
  document.getElementById("__ui_regression_controller_preview")?.remove();
  document.body.appendChild(panel);

  await new Promise((resolve) => {
    iframe.addEventListener("load", resolve, { once: true });
    setTimeout(resolve, 5000);
  });

  const deadline = Date.now() + 12000;
  let last = {};
  while (Date.now() < deadline) {
    const doc = iframe.contentDocument;
    const state = doc?.querySelector("#outputs-state")?.textContent || "";
    const frames = doc ? [...doc.querySelectorAll("#outputs-list iframe")] : [];
    const frame = frames[0] || null;
    const outputDoc = frame && frame.contentDocument;
    const editor = outputDoc && outputDoc.querySelector("[data-field-fline-editor=\"true\"]");
    const svg = outputDoc && outputDoc.querySelector(".field-fline-editor-canvas");
    last = {
      ...result,
      controllerHref: iframe.contentWindow ? iframe.contentWindow.location.href : iframe.src,
      state,
      frameCount: frames.length,
      frameKind: frame && frame.dataset ? frame.dataset.outputKind || "" : "",
      frameHeight: frame ? Math.round(frame.getBoundingClientRect().height) : 0,
      frameAspect: frame && frame.dataset ? frame.dataset.flineEditorAspect || "" : "",
      hasEditor: !!editor,
      initialized: !!(editor && editor.__fieldFLineEditorInitialized),
      svgChildren: svg ? svg.childElementCount : 0,
      svgWidth: svg ? Math.round(svg.getBoundingClientRect().width) : 0,
      svgHeight: svg ? Math.round(svg.getBoundingClientRect().height) : 0,
      bodyClass: outputDoc && outputDoc.body ? outputDoc.body.className : "",
      errors: [
        ...errors,
        ...(doc ? [...doc.querySelectorAll(".error, .field-error")].map(e => e.textContent).slice(0, 5) : [])
      ],
    };
    last.ok = String(last.state).includes("EditableLinesRegression") &&
      last.frameCount === 1 &&
      last.frameKind === "fline-editor" &&
      last.hasEditor &&
      last.initialized &&
      last.svgChildren > 0 &&
      last.svgWidth > 0 &&
      last.svgHeight > 0 &&
      last.errors.length === 0;
    if (last.ok) return last;
    await delay(250);
  }
  return { ok: false, reason: "controllers editable lines output did not become ready", ...last };
} finally {
  window.removeEventListener("error", onError);
}
"""

CONTROLLERS_STATIC_LINES_JS = r"""
const delay = (ms) => new Promise(resolve => setTimeout(resolve, ms));
const errors = [];
const onError = (event) => errors.push(String(event.error && (event.error.stack || event.error.message) || event.message || event));
window.addEventListener("error", onError);
try {
  const boxes = [...document.querySelectorAll(".box")];
  const selected = window.selectedList && window.selectedList[0] ? window.selectedList[0] : null;
  const box = selected || boxes.find((candidate) => String(candidate.textContent || "").includes("StaticLinesRegression")) || boxes.at(-1) || null;
  const uid = box ? (box.uid || box.getAttribute("uid") || box.getAttribute("data-uid") || "") : "";
  const result = { uid, fieldHref: window.location.href, boxCount: boxes.length };
  if (!box || !uid) return { ok: false, reason: "box not found", ...result, errors };

  if (typeof window.selectBox === "function") window.selectBox(box);
  if (typeof window.selectionContextChanged === "function") window.selectionContextChanged();
  await delay(700);

  const url = new URL(window.location.href);
  const documentName = url.searchParams.get("file") || "scratch";
  const panel = document.createElement("div");
  panel.id = "__ui_regression_static_lines_preview";
  panel.style.cssText = [
    "position:fixed",
    "right:24px",
    "top:24px",
    "width:min(520px,46vw)",
    "height:min(900px,88vh)",
    "z-index:2147483000",
    "background:#0b0d0f",
    "border:1px solid rgba(255,255,255,0.28)",
    "box-shadow:0 18px 50px rgba(0,0,0,0.42)",
    "overflow:hidden"
  ].join(";");
  const iframe = document.createElement("iframe");
  iframe.id = "__ui_regression_static_lines_iframe";
  iframe.src = `/controllers?page=outputs&file=${encodeURIComponent(documentName)}`;
  iframe.style.cssText = "width:100%;height:100%;border:0;display:block;background:#050505;";
  panel.replaceChildren(iframe);
  document.getElementById("__ui_regression_static_lines_preview")?.remove();
  document.body.appendChild(panel);

  await new Promise((resolve) => {
    iframe.addEventListener("load", resolve, { once: true });
    setTimeout(resolve, 5000);
  });

  const deadline = Date.now() + 12000;
  let last = {};
  while (Date.now() < deadline) {
    const doc = iframe.contentDocument;
    const state = doc?.querySelector("#outputs-state")?.textContent || "";
    const frames = doc ? [...doc.querySelectorAll("#outputs-list iframe")] : [];
    const frame = frames[0] || null;
    const outputDoc = frame && frame.contentDocument;
    const lines = outputDoc && outputDoc.querySelector(".afield-lines-output");
    const svg = outputDoc && outputDoc.querySelector(".afield-lines-output svg");
    last = {
      ...result,
      controllerHref: iframe.contentWindow ? iframe.contentWindow.location.href : iframe.src,
      state,
      frameCount: frames.length,
      frameKind: frame && frame.dataset ? frame.dataset.outputKind || "" : "",
      frameHeight: frame ? Math.round(frame.getBoundingClientRect().height) : 0,
      hasLines: !!lines,
      linesInitialized: !!(lines && lines.__fieldControllerLinesState),
      svgChildren: svg ? svg.childElementCount : 0,
      svgViewBox: svg ? svg.getAttribute("viewBox") || "" : "",
      svgWidth: svg ? Math.round(svg.getBoundingClientRect().width) : 0,
      svgHeight: svg ? Math.round(svg.getBoundingClientRect().height) : 0,
      errors: [
        ...errors,
        ...(doc ? [...doc.querySelectorAll(".error, .field-error")].map(e => e.textContent).slice(0, 5) : [])
      ],
    };
    last.ok = String(last.state).includes("StaticLinesRegression") &&
      last.frameCount === 1 &&
      last.frameKind !== "fline-editor" &&
      last.hasLines &&
      last.linesInitialized &&
      last.svgChildren > 0 &&
      last.svgWidth > 0 &&
      last.svgHeight > 0 &&
      last.errors.length === 0;
    if (last.ok) return last;
    await delay(250);
  }
  return { ok: false, reason: "controllers static lines output did not become ready", ...last };
} finally {
  window.removeEventListener("error", onError);
}
"""

CONTROLLERS_CONTROL_GROUP_JS = r"""
const delay = (ms) => new Promise(resolve => setTimeout(resolve, ms));
const errors = [];
const onError = (event) => errors.push(String(event.error && (event.error.stack || event.error.message) || event.message || event));
window.addEventListener("error", onError);
try {
  const boxes = [...document.querySelectorAll(".box")];
  const selected = window.selectedList && window.selectedList[0] ? window.selectedList[0] : null;
  const box = selected || boxes.find((candidate) => String(candidate.textContent || "").includes("ControllerControlsRegression")) || boxes.at(-1) || null;
  const uid = box ? (box.uid || box.getAttribute("uid") || box.getAttribute("data-uid") || "") : "";
  const result = { uid, fieldHref: window.location.href, boxCount: boxes.length };
  if (!box || !uid) return { ok: false, reason: "box not found", ...result, errors };

  if (typeof window.selectBox === "function") window.selectBox(box);
  if (typeof window.selectionContextChanged === "function") window.selectionContextChanged();
  await delay(900);

  const url = new URL(window.location.href);
  const documentName = url.searchParams.get("file") || "scratch";
  const panel = document.createElement("div");
  panel.id = "__ui_regression_controller_controls_preview";
  panel.style.cssText = [
    "position:fixed",
    "right:24px",
    "top:24px",
    "width:min(920px,70vw)",
    "height:min(900px,88vh)",
    "z-index:2147483000",
    "background:#0b0d0f",
    "border:1px solid rgba(255,255,255,0.28)",
    "box-shadow:0 18px 50px rgba(0,0,0,0.42)",
    "display:grid",
    "grid-template-columns:1fr 1fr",
    "gap:1px",
    "overflow:hidden"
  ].join(";");
  const controlsFrame = document.createElement("iframe");
  controlsFrame.id = "__ui_regression_controls_iframe";
  controlsFrame.src = `/controllers?page=controls&file=${encodeURIComponent(documentName)}`;
  controlsFrame.style.cssText = "width:100%;height:100%;border:0;display:block;background:#050505;";
  const outputsFrame = document.createElement("iframe");
  outputsFrame.id = "__ui_regression_controls_outputs_iframe";
  outputsFrame.src = `/controllers?page=outputs&file=${encodeURIComponent(documentName)}`;
  outputsFrame.style.cssText = "width:100%;height:100%;border:0;display:block;background:#050505;";
  panel.replaceChildren(controlsFrame, outputsFrame);
  document.getElementById("__ui_regression_controller_controls_preview")?.remove();
  document.body.appendChild(panel);

  await Promise.all([controlsFrame, outputsFrame].map((frame) => new Promise((resolve) => {
    frame.addEventListener("load", resolve, { once: true });
    setTimeout(resolve, 5000);
  })));

  const expectedLabels = ["gain", "enabled", "label", "velocity", "tint"];
  const controlTypesReady = (doc, rootSelector) => {
    const root = doc && doc.querySelector(rootSelector);
    const controls = root ? [...root.querySelectorAll(".phone-control")] : [];
    const labels = controls.map((node) => node.querySelector(".phone-control-label")?.textContent?.trim() || "");
    return {
      count: controls.length,
      labels,
      hasSlider: !!root?.querySelector(".phone-control-slider input[type='range']"),
      hasCheckbox: !!root?.querySelector(".phone-control-checkbox .phone-toggle"),
      hasString: !!root?.querySelector(".phone-control-string .phone-text-input"),
      hasVec2: !!root?.querySelector(".phone-control-vec2 .phone-vec2-pad"),
      hasColor: !!root?.querySelector(".phone-control-color .phone-color-input"),
    };
  };

  const deadline = Date.now() + 12000;
  let last = {};
  while (Date.now() < deadline) {
    const controlsDoc = controlsFrame.contentDocument;
    const outputsDoc = outputsFrame.contentDocument;
    const controlsState = controlsDoc?.querySelector("#group-state")?.textContent || "";
    const outputsState = outputsDoc?.querySelector("#outputs-state")?.textContent || "";
    const controls = controlTypesReady(controlsDoc, "#group-controls");
    const outputControls = controlTypesReady(outputsDoc, "#outputs-controls");
    const outputFrames = outputsDoc ? [...outputsDoc.querySelectorAll("#outputs-list iframe")] : [];
    const summaryFrame = outputFrames[0] || null;
    const summaryDoc = summaryFrame && summaryFrame.contentDocument;
    const summary = summaryDoc?.querySelector("#controllers-control-summary");
    last = {
      ...result,
      controlsState,
      outputsState,
      controls,
      outputControls,
      outputFrameCount: outputFrames.length,
      hasSummary: !!summary,
      summaryText: summary ? summary.textContent.trim() : "",
      errors: [
        ...errors,
        ...(controlsDoc ? [...controlsDoc.querySelectorAll(".error, .field-error")].map(e => e.textContent).slice(0, 5) : []),
        ...(outputsDoc ? [...outputsDoc.querySelectorAll(".error, .field-error")].map(e => e.textContent).slice(0, 5) : [])
      ],
    };
    const controlsReady = controls.count === 5 &&
      expectedLabels.every((label) => controls.labels.includes(label)) &&
      controls.hasSlider && controls.hasCheckbox && controls.hasString && controls.hasVec2 && controls.hasColor;
    const outputsReady = outputControls.count === 5 &&
      expectedLabels.every((label) => outputControls.labels.includes(label)) &&
      outputControls.hasSlider && outputControls.hasCheckbox && outputControls.hasString && outputControls.hasVec2 && outputControls.hasColor &&
      last.outputFrameCount === 1 &&
      last.hasSummary &&
      last.summaryText.includes("native controller controls ready");
    last.ok = String(controlsState).includes("ControllerControlsRegression") &&
      String(outputsState).includes("ControllerControlsRegression") &&
      controlsReady &&
      outputsReady &&
      last.errors.length === 0;
    if (last.ok) return last;
    await delay(250);
  }
  return { ok: false, reason: "controller native control group did not become ready", ...last };
} finally {
  window.removeEventListener("error", onError);
}
"""

CONTROLLERS_MOBILE_WORKSPACE_JS = r"""
const delay = (ms) => new Promise(resolve => setTimeout(resolve, ms));
const waitFor = async (predicate, timeoutMs = 20000) => {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    const value = predicate();
    if (value) return value;
    await delay(100);
  }
  return predicate();
};
const boxes = [...document.querySelectorAll(".box")];
const box = (window.selectedList && window.selectedList[0]) || boxes.find(node => String(node.textContent || "").includes("MobileControllerRegression")) || boxes.at(-1);
const uid = box && (box.uid || box.getAttribute("uid") || box.getAttribute("data-uid") || "");
if (!uid) return { ok: false, reason: "selected mobile controller box missing" };

const url = new URL(window.location.href);
const documentName = url.searchParams.get("file") || "scratch";
const controllerQuery = new URLSearchParams({ page: "select", file: documentName });
if (url.searchParams.get("scratch")) controllerQuery.set("scratch", url.searchParams.get("scratch"));
if (url.searchParams.get("sessionKey")) controllerQuery.set("sessionKey", url.searchParams.get("sessionKey"));
document.getElementById("__ui_regression_mobile_controller")?.remove();
const frame = document.createElement("iframe");
frame.id = "__ui_regression_mobile_controller";
frame.src = `/controllers?${controllerQuery.toString()}`;
frame.style.cssText = [
  "position:fixed", "left:18px", "top:18px", "width:390px", "height:844px",
  "z-index:2147483000", "border:0", "outline:1px solid rgba(72,201,255,.8)", "background:#050505",
  "box-shadow:0 18px 60px rgba(0,0,0,.72)"
].join(";");
document.body.appendChild(frame);
await new Promise(resolve => { frame.addEventListener("load", resolve, { once: true }); setTimeout(resolve, 5000); });

const controllerDoc = frame.contentDocument;
const controllerWin = frame.contentWindow;
const workspace = await waitFor(() => controllerDoc?.querySelector(".workspace-frame"));
const workspaceWin = workspace && workspace.contentWindow;
const palette = await waitFor(() => workspaceWin?.document?.querySelector(".afield-palette"));
const paletteInput = palette?.querySelector(".afield-palette-input");
const selectedUid = workspaceWin?.selectedList?.[0]?.uid || "";
paletteInput?.focus();
if (typeof workspaceWin?.selectionContextChanged === "function") workspaceWin.selectionContextChanged();
await delay(400);
const selectPageStayedOpen = controllerDoc?.querySelector('[data-controller-page="select"]')?.classList.contains("active") === true;
const paletteStayedOpen = !!palette && workspaceWin.getComputedStyle(palette).display !== "none";
paletteInput.value = "MobileControllerRegression";
paletteInput.dispatchEvent(new Event("input", { bubbles: true }));
const paletteInputAcceptedText = paletteInput.value === "MobileControllerRegression";
const phone390 = {
  width: controllerDoc?.documentElement.clientWidth || 0,
  height: controllerDoc?.documentElement.clientHeight || 0,
  tabs: controllerDoc?.querySelectorAll(".controller-tab").length || 0,
  paletteVisible: !!palette && workspaceWin.getComputedStyle(palette).display !== "none",
  paletteInputFont: paletteInput ? parseFloat(workspaceWin.getComputedStyle(paletteInput).fontSize) : 0,
  selectedUid,
  selectPageStayedOpen,
  paletteStayedOpen,
  paletteInputAcceptedText,
};

const resultRow = await waitFor(() => [...palette.querySelectorAll(".afield-palette-row")]
  .find(row => String(row.textContent || "").includes("MobileControllerRegression")));
resultRow?.click();
const selectionResultActivatedEditor = await waitFor(() =>
  controllerDoc?.querySelector('[data-page="editor"]')?.classList.contains("active") === true &&
  workspaceWin.getComputedStyle(palette).display === "none");
const workspaceAfterSelection = controllerDoc?.querySelector("#select-frame-host .workspace-frame");
const workspaceFramePreserved = workspaceAfterSelection === workspace && workspaceAfterSelection?.contentWindow === workspaceWin;
const selectedResultSettled = await waitFor(() => workspaceWin?.selectedList?.[0]?.uid === uid);
const editorModel = await waitFor(() => {
  const model = workspaceWin?.globalEditor?.getModel?.();
  return model?.getValue?.().includes("mobile controller editor") ? model : null;
});
const editorValue = editorModel?.getValue?.() || "";
const editorVisible = !!workspaceWin?.document?.querySelector(".monaco-editor");

controllerDoc?.querySelector('[data-page="chat"]')?.click();
const chatFrame = await waitFor(() => controllerDoc?.querySelector("#chat-frame-host .remote-frame"));
const chatWin = chatFrame && chatFrame.contentWindow;
const chatRoot = await waitFor(() => chatWin?.document?.querySelector("#controller-chat-root"));
const chatReady = await waitFor(() => {
  const heading = chatRoot?.querySelector(".controller-chat-heading")?.textContent || "";
  const container = chatRoot?.querySelector(".controller-chat-container");
  return heading.includes("MobileControllerRegression") && container && typeof chatWin?.aiChatUpdate === "function" ? container : null;
});
const chatContainer = chatReady || chatRoot?.querySelector(".controller-chat-container");
const fixtureHistory = [
  "<<you>>", "Please update the shader on this box.",
  "<<progress>>", "Using EditFileRequest", '{"file":"fragment","newContentChars":312,"newContentPreview":"#version 450"}',
  "<<progress>>", "CompileKotlin finished", "success=true diagnostics=0",
  "<<ai>>", "Updated `fragment` and verified the change."
].join("\n");
if (chatWin?.aiChatUpdate && chatContainer) chatWin.aiChatUpdate(chatContainer, uid, fixtureHistory);
const editSummary = await waitFor(() => chatRoot?.querySelector('.ai-chat-progress[data-kind="edit"]'));
const chatTextarea = await waitFor(() => chatRoot?.querySelector(".ai-chat-input textarea"));
const sendButton = chatRoot?.querySelector(".ai-chat-input button");

frame.style.width = "430px";
frame.style.height = "932px";
await delay(150);
const phone430 = {
  width: controllerDoc?.documentElement.clientWidth || 0,
  height: controllerDoc?.documentElement.clientHeight || 0,
  navBottom: Math.round(controllerDoc?.querySelector(".page-tabs")?.getBoundingClientRect().bottom || 0),
};

frame.style.width = "390px";
frame.style.height = "430px";
await delay(180);
chatTextarea?.focus();
const inputRect = chatTextarea?.getBoundingClientRect();
const navRect = controllerDoc?.querySelector(".page-tabs")?.getBoundingClientRect();
const compact = {
  width: controllerDoc?.documentElement.clientWidth || 0,
  height: controllerDoc?.documentElement.clientHeight || 0,
  navVisible: !!navRect && navRect.bottom <= 431 && navRect.top >= 0,
  inputVisible: !!inputRect && inputRect.bottom <= (chatWin?.innerHeight || 0) + 1 && inputRect.top >= 0,
};

frame.style.width = "390px";
frame.style.height = "844px";
await delay(180);

const result = {
  uid,
  phone390,
  phone430,
  compact,
  selectionResultActivatedEditor: !!selectionResultActivatedEditor,
  workspaceFramePreserved,
  selectedResultSettled: !!selectedResultSettled,
  editorVisible,
  editorHasFixture: editorValue.includes("mobile controller editor"),
  chatHeading: chatRoot?.querySelector(".controller-chat-heading")?.textContent || "",
  chatHasInput: !!chatTextarea,
  chatHasSend: !!sendButton && chatWin.getComputedStyle(sendButton).display !== "none",
  editSummary: editSummary?.querySelector("summary")?.textContent?.trim() || "",
  editOpen: !!editSummary?.open,
};
result.ok = phone390.width === 390 && phone390.height === 844 && phone390.tabs === 6 &&
  phone390.paletteVisible && phone390.paletteInputFont >= 16 && phone390.selectedUid === uid &&
  phone390.selectPageStayedOpen && phone390.paletteStayedOpen && phone390.paletteInputAcceptedText &&
  result.selectionResultActivatedEditor && result.workspaceFramePreserved && result.selectedResultSettled &&
  phone430.width === 430 && phone430.height === 932 && phone430.navBottom === 932 &&
  compact.width === 390 && compact.height === 430 && compact.navVisible && compact.inputVisible &&
  result.editorVisible && result.editorHasFixture && result.chatHasInput && result.chatHasSend &&
  result.chatHeading.includes("MobileControllerRegression") && result.editSummary.includes("fragment") && result.editOpen;
return result.ok ? result : { ok: false, reason: "mobile controller workspace did not satisfy phone layout contract", ...result };
"""

SELECTION_SCREENSHOT_BACKDOOR_JS = r"""
const delay = (ms) => new Promise(resolve => setTimeout(resolve, ms));
const errors = [];
const onError = (event) => errors.push(String(event.error && (event.error.stack || event.error.message) || event.message || event));
window.addEventListener("error", onError);
const waitFor = async (predicate, timeoutMs = 6000) => {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    const value = predicate();
    if (value) return value;
    await delay(75);
  }
  return predicate();
};
try {
  const ed = window.globalEditor;
  const boxes = [...document.querySelectorAll(".box")];
  const source = boxes.find((candidate) => String(candidate.textContent || "").includes("ScreenshotBackdoorSource")) || null;
  const target = boxes.find((candidate) => String(candidate.textContent || "").includes("ScreenshotBackdoorTarget")) ||
    (window.selectedList && window.selectedList[0]) ||
    boxes.at(-1) ||
    null;
  const sourceUid = source ? (source.uid || source.getAttribute("uid") || source.getAttribute("data-uid") || "") : "";
  const targetUid = target ? (target.uid || target.getAttribute("uid") || target.getAttribute("data-uid") || "") : "";
  if (!ed) return { ok: false, reason: "globalEditor missing", errors };
  if (!source || !target || !targetUid) return { ok: false, reason: "test boxes missing", sourceUid, targetUid, boxCount: boxes.length, errors };

  if (typeof window.selectBox === "function") window.selectBox(target);
  if (typeof window.selectionContextChanged === "function") window.selectionContextChanged();
  if (typeof window.switchToProperty === "function") window.switchToProperty(targetUid, "code");
  await waitFor(() => window.currentSelectedCode && window.currentSelectedCode[0] === target, 3000);
  await waitFor(() => ed.getModel() && String(ed.getModel().getValue()).includes("selection screenshot target"), 3000);
  ed.focus();
  await delay(250);

  const selectedList = window.selectedList ? [...window.selectedList] : [];
  const currentSelectedCode = window.currentSelectedCode ? [...window.currentSelectedCode] : [];
  const targetRect = target.getBoundingClientRect();
  const sourceSelected = selectedList.includes(source) || currentSelectedCode.includes(source);
  const targetSelected = selectedList.includes(target) || currentSelectedCode.includes(target);
  const modelValue = ed.getModel() ? ed.getModel().getValue() : "";
  const output = target.querySelector("#selection-screenshot-output");
  const result = {
    sourceUid,
    targetUid,
    selectedCount: selectedList.length,
    currentSelectedCodeCount: currentSelectedCode.length,
    sourceSelected,
    targetSelected,
    selectedProperty: window.selectedProperty || window.currentSelectedProperty || "",
    editorHasTargetCode: modelValue.includes("selection screenshot target"),
    editorFocus: ed.hasTextFocus(),
    targetWidth: Math.round(targetRect.width || 0),
    targetHeight: Math.round(targetRect.height || 0),
    hasOutput: !!output,
    errors,
  };
  result.ok = result.sourceUid &&
    result.targetUid &&
    result.sourceUid !== result.targetUid &&
    result.targetSelected &&
    !result.sourceSelected &&
    result.selectedProperty === "code" &&
    result.editorHasTargetCode &&
    result.editorFocus &&
    result.targetWidth > 0 &&
    result.targetHeight > 0 &&
    result.hasOutput &&
    result.errors.length === 0;
  return result;
} finally {
  window.removeEventListener("error", onError);
}
"""

SELECTION_SUBTITLE_BOX_JS = r"""
const delay = (ms) => new Promise(resolve => setTimeout(resolve, ms));
const errors = [];
const onError = (event) => errors.push(String(event.error && (event.error.stack || event.error.message) || event.message || event));
window.addEventListener("error", onError);
const waitFor = async (predicate, timeoutMs = 3000) => {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    const value = predicate();
    if (value) return value;
    await delay(75);
  }
  return predicate();
};
try {
  const target =
    [...document.querySelectorAll(".box")].find((candidate) => String(candidate.textContent || "").includes("SelectionSubtitleRegression")) ||
    (window.selectedList && window.selectedList[0]) ||
    null;
  if (!target) return { ok: false, reason: "test box missing", boxCount: document.querySelectorAll(".box").length, errors };

  if (typeof window.selectBox === "function") window.selectBox(target);
  if (typeof window.selectionContextChanged === "function") window.selectionContextChanged();

  const pathText = await waitFor(() => target.querySelector(".file-proxy-path")?.textContent?.trim() || "", 3000);
  const selectedBefore = getComputedStyle(target, "::before");
  const beforeBorderWidthPx = Number.parseFloat(selectedBefore.borderTopWidth || selectedBefore.borderWidth || "0") || 0;
  const boxStyle = getComputedStyle(target);
  const result = {
    selected: target.classList.contains("selected"),
    fileProxyBox: target.classList.contains("file-proxy-box"),
    pathText,
    beforeBorderColor: selectedBefore.borderColor,
    beforeBorderWidth: selectedBefore.borderWidth,
    beforeBorderWidthPx,
    beforeIsSelectedColor: /72,\s*201,\s*255/.test(selectedBefore.borderColor),
    boxBorderColor: boxStyle.borderColor,
    boxIsSelectedColor: /72,\s*201,\s*255/.test(boxStyle.borderColor),
    errors,
  };
  result.ok = result.selected &&
    result.fileProxyBox &&
    !!result.pathText &&
    result.boxIsSelectedColor &&
    !result.beforeIsSelectedColor &&
    result.beforeBorderWidthPx >= 4 &&
    result.errors.length === 0;
  return result;
} finally {
  window.removeEventListener("error", onError);
}
"""

LANGUAGE_SELECTION_CUSTOMIZATION_JS = r"""
const title = this.querySelector('.box-title-stack') || this;
let label = this.querySelector('[data-afield-language-label="true"]');
if (!label) {
    label = document.createElement('div');
    label.setAttribute('data-afield-language-label', 'true');
    title.appendChild(label);
}
label.textContent = "Python";
label.style.fontSize = "9px";
label.style.lineHeight = "1.1";
label.style.opacity = "0.58";
label.style.fontWeight = "500";
label.style.letterSpacing = "0";
label.style.textTransform = "none";
label.style.pointerEvents = "none";
label.style.userSelect = "none";
label.style.marginTop = "1px";
this.style.margin = "";
"""

LANGUAGE_SELECTION_BOX_JS = r"""
const delay = (ms) => new Promise(resolve => setTimeout(resolve, ms));
const errors = [];
const onError = (event) => errors.push(String(event.error && (event.error.stack || event.error.message) || event.message || event));
window.addEventListener("error", onError);
const waitFor = async (predicate, timeoutMs = 3000) => {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    const value = predicate();
    if (value) return value;
    await delay(75);
  }
  return predicate();
};
try {
  const target =
    [...document.querySelectorAll(".box")].find((candidate) => String(candidate.textContent || "").includes("LanguageSelectionRegression")) ||
    (window.selectedList && window.selectedList[0]) ||
    null;
  if (!target) return { ok: false, reason: "test box missing", boxCount: document.querySelectorAll(".box").length, errors };

  await waitFor(() => target.querySelector('[data-afield-language-label="true"]')?.textContent?.trim() === "Python", 3000);
  if (typeof window.selectBox === "function") window.selectBox(target);
  if (typeof window.selectionContextChanged === "function") window.selectionContextChanged();

  const selectedBefore = getComputedStyle(target, "::before");
  const boxStyle = getComputedStyle(target);
  const result = {
    selected: target.classList.contains("selected"),
    label: target.querySelector('[data-afield-language-label="true"]')?.textContent?.trim() || "",
    borderStyle: boxStyle.borderTopStyle,
    borderWidth: boxStyle.borderTopWidth,
    borderColor: boxStyle.borderColor,
    boxIsSelectedColor: /72,\s*201,\s*255/.test(boxStyle.borderColor),
    beforeBorderColor: selectedBefore.borderColor,
    beforeIsSelectedColor: /72,\s*201,\s*255/.test(selectedBefore.borderColor),
    errors,
  };
  result.ok = result.selected &&
    result.label === "Python" &&
    result.borderStyle !== "none" &&
    result.borderWidth !== "0px" &&
    result.boxIsSelectedColor &&
    !result.beforeIsSelectedColor &&
    result.errors.length === 0;
  return result;
} finally {
  window.removeEventListener("error", onError);
}
"""

HISTORY_PANEL_OPEN_JS = r"""
const delay = (ms) => new Promise(resolve => setTimeout(resolve, ms));
const errors = [];
const onError = (event) => errors.push(String(event.error && (event.error.stack || event.error.message) || event.message || event));
window.addEventListener("error", onError);
const waitFor = async (predicate, timeoutMs = 10000) => {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    const value = predicate();
    if (value) return value;
    await delay(100);
  }
  return predicate();
};
try {
  const boxes = [...document.querySelectorAll(".box")];
  const box = (window.selectedList && window.selectedList[0]) ||
    boxes.find((candidate) => String(candidate.textContent || "").includes("HistoryPanelRegression")) ||
    boxes.at(-1) ||
    null;
  const uid = box ? (box.uid || box.getAttribute("uid") || box.getAttribute("data-uid") || "") : "";
  if (!box || !uid) return { ok: false, reason: "box not found", uid, boxCount: boxes.length, errors };

  if (typeof window.selectBox === "function") window.selectBox(box);
  if (typeof window.selectionContextChanged === "function") window.selectionContextChanged();
  if (typeof window.switchToProperty === "function") window.switchToProperty(uid, "code");
  await delay(400);

  const filename = window.currentDocumentFilename || "default";
  if (typeof window.switchToHistoryForFile !== "function") return { ok: false, reason: "switchToHistoryForFile missing", uid, filename, errors };
  window.switchToHistoryForFile(filename);

  const container = await waitFor(() => {
    const node = document.querySelector(".history-iframe-container");
    if (!node) return null;
    const style = getComputedStyle(node);
    return style.visibility !== "hidden" && Number(style.opacity || "0") > 0 ? node : null;
  }, 5000);
  const iframe = window.getHistoryIframeElement ? window.getHistoryIframeElement() : document.querySelector("#history-iframe");
  const historyDoc = await waitFor(() => {
    const doc = iframe && iframe.contentDocument;
    if (!doc || !doc.querySelector("#rail")) return null;
    const dots = doc.querySelectorAll("#rail [data-version-index]");
    const loading = doc.querySelector("#change-list .empty")?.textContent || "";
    return dots.length > 0 || !String(loading).includes("Loading") ? doc : null;
  }, 12000);
  if (!container || !iframe || !historyDoc) {
    const doc = iframe && iframe.contentDocument;
    return {
      ok: false,
      reason: "history iframe did not load",
      uid,
      filename,
      hasContainer: !!container,
      hasIframe: !!iframe,
      iframeSrc: iframe ? iframe.getAttribute("src") || "" : "",
      iframeLocation: (() => { try { return iframe && iframe.contentWindow ? iframe.contentWindow.location.href : ""; } catch (_) { return "unavailable"; } })(),
      docReadyState: doc ? doc.readyState || "" : "",
      docTitle: doc ? doc.title || "" : "",
      docBodyText: doc && doc.body ? String(doc.body.textContent || "").trim().slice(0, 240) : "",
      errors
    };
  }

  function readResult() {
    const rail = historyDoc.querySelector("#rail");
    const changeList = historyDoc.querySelector("#change-list");
    const result = {
      uid,
      filename,
      iframeSrc: iframe.getAttribute("src") || "",
      containerVisible: true,
      dotCount: historyDoc.querySelectorAll("#rail [data-version-index]").length,
      activeDotCount: historyDoc.querySelectorAll("#rail .dot.active").length,
      targetVersion: historyDoc.querySelector("#target-version")?.textContent?.trim() || "",
      targetMeta: historyDoc.querySelector("#target-meta")?.textContent?.trim() || "",
      metricBoxes: historyDoc.querySelector("#metric-boxes")?.textContent?.trim() || "",
      hasRefresh: !!historyDoc.querySelector("#refresh-btn"),
      hasClose: !!historyDoc.querySelector("#close-btn"),
      hasSearch: !!historyDoc.querySelector("#history-search-input"),
      railWidth: rail ? Math.round(rail.getBoundingClientRect().width || 0) : 0,
      changeListText: changeList ? String(changeList.textContent || "").trim().slice(0, 160) : "",
      errors,
    };
    const versionNumber = Number(String(result.targetVersion || "").replace("#", ""));
    const metricBoxCount = Number.parseInt(String(result.metricBoxes || "0"), 10) || 0;
    const targetMetaBoxMatch = String(result.targetMeta || "").match(/(\d+)\s+boxes/);
    const targetMetaBoxCount = targetMetaBoxMatch ? Number.parseInt(targetMetaBoxMatch[1], 10) || 0 : 0;
    result.ok = result.iframeSrc.includes("/history") &&
      Number.isFinite(versionNumber) &&
      versionNumber >= 1 &&
      (metricBoxCount >= 1 || targetMetaBoxCount >= 1) &&
      result.targetMeta.length > 0 &&
      result.hasRefresh &&
      result.hasClose &&
      result.hasSearch &&
      result.railWidth > 0 &&
      !result.changeListText.includes("Loading document history") &&
      result.errors.length === 0;
    return result;
  }

  let result = readResult();
  const savedDeadline = Date.now() + 30000;
  while (!result.ok && Date.now() < savedDeadline) {
    historyDoc.querySelector("#refresh-btn")?.click();
    await delay(1000);
    result = readResult();
  }

  const dots = [...historyDoc.querySelectorAll("#rail [data-version-index]")];
  const latestDot = dots.at(-1);
  if (latestDot) latestDot.click();
  historyDoc.querySelector(".rail-zoom-btn[data-zoom='in']")?.click();
  const search = historyDoc.querySelector("#history-search-input");
  if (search) {
    search.value = "history";
    search.dispatchEvent(new Event("input", { bubbles: true }));
  }
  await delay(350);
  if (result.ok) result = readResult();
  return result;
} finally {
  window.removeEventListener("error", onError);
}
"""

HISTORY_PANEL_CLOSE_JS = r"""
const delay = (ms) => new Promise(resolve => setTimeout(resolve, ms));
const errors = [];
const onError = (event) => errors.push(String(event.error && (event.error.stack || event.error.message) || event.message || event));
window.addEventListener("error", onError);
const waitFor = async (predicate, timeoutMs = 5000) => {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    const value = predicate();
    if (value) return value;
    await delay(75);
  }
  return predicate();
};
try {
  const iframe = window.getHistoryIframeElement ? window.getHistoryIframeElement() : document.querySelector("#history-iframe");
  const doc = iframe && iframe.contentDocument;
  const close = doc && doc.querySelector("#close-btn");
  if (!iframe || !doc || !close) return { ok: false, reason: "history close button missing", hasIframe: !!iframe, hasDoc: !!doc, errors };
  close.click();
  const hidden = await waitFor(() => {
    const container = document.querySelector(".history-iframe-container");
    if (!container) return false;
    const style = getComputedStyle(container);
    return style.visibility === "hidden" || Number(style.opacity || "1") === 0;
  }, 5000);
  const ed = window.globalEditor;
  if (ed && typeof ed.focus === "function") {
    ed.focus();
    await waitFor(() => typeof ed.hasTextFocus === "function" && ed.hasTextFocus(), 1200);
  }
  const result = {
    hidden: !!hidden,
    iframeSrc: iframe.getAttribute("src") || "",
    editorFocus: ed && typeof ed.hasTextFocus === "function" ? ed.hasTextFocus() : null,
    errors,
  };
  result.ok = result.hidden &&
    result.iframeSrc === "" &&
    result.errors.length === 0;
  return result;
} finally {
  window.removeEventListener("error", onError);
}
"""

VISIONOS_SHARED_HISTORY_PANEL_JS = r"""
const delay = (ms) => new Promise(resolve => setTimeout(resolve, ms));
const errors = [];
const onError = (event) => errors.push(String(event.error && (event.error.stack || event.error.message) || event.message || event));
window.addEventListener("error", onError);
const waitFor = async (predicate, timeoutMs = 15000) => {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    const value = predicate();
    if (value) return value;
    await delay(100);
  }
  return predicate();
};
try {
  const filename = window.currentDocumentFilename || "default";
  const context = `ui-regression-shared-history-${Date.now()}`;
  const makeProjection = (mode) => {
    const frame = document.createElement("iframe");
    const url = new URL("/index.html", window.location.href);
    url.searchParams.set("file", filename);
    url.searchParams.set("shaderSelectionContext", context);
    url.searchParams.set(mode, "1");
    if (mode === "noeditor") url.searchParams.set("transparent", "1");
    frame.src = url.toString();
    frame.dataset.projection = mode;
    frame.style.position = "fixed";
    frame.style.border = "0";
    frame.style.background = "transparent";
    document.body.appendChild(frame);
    return frame;
  };

  const canvasFrame = makeProjection("noeditor");
  Object.assign(canvasFrame.style, { inset: "0", width: "100vw", height: "100vh", zIndex: "20000" });
  const editorFrame = makeProjection("editorOnly");
  Object.assign(editorFrame.style, { left: "0", top: "0", width: "1px", height: "1px", opacity: "0", zIndex: "19999" });

  const ready = await waitFor(() => {
    try {
      return canvasFrame.contentWindow?.fieldBackdoorUiReady === true &&
        editorFrame.contentWindow?.fieldBackdoorUiReady === true &&
        typeof editorFrame.contentWindow?.showDocumentHistory === "function";
    } catch (_) { return false; }
  }, 20000);
  if (!ready) {
    return {
      ok: false,
      reason: "paired projections did not become ready",
      canvasReady: canvasFrame.contentWindow?.fieldBackdoorUiReady,
      editorReady: editorFrame.contentWindow?.fieldBackdoorUiReady,
      hasCommand: typeof editorFrame.contentWindow?.showDocumentHistory === "function",
      errors,
    };
  }

  editorFrame.contentWindow.showDocumentHistory();
  const historyContainer = await waitFor(() => {
    const node = canvasFrame.contentDocument?.querySelector(".history-iframe-container");
    if (!node) return null;
    const style = canvasFrame.contentWindow.getComputedStyle(node);
    return style.visibility !== "hidden" && Number(style.opacity || "0") > 0 ? node : null;
  }, 8000);
  const historyFrame = canvasFrame.contentDocument?.querySelector("#history-iframe");
  const historyReady = await waitFor(() => {
    const doc = historyFrame?.contentDocument;
    return doc?.querySelector("#rail") && doc?.querySelector("#close-btn") ? doc : null;
  }, 15000);
  const editorHistory = editorFrame.contentDocument?.querySelector(".history-iframe-container");
  const editorHistoryStyle = editorHistory ? editorFrame.contentWindow.getComputedStyle(editorHistory) : null;
  const bounds = historyContainer?.getBoundingClientRect();
  const result = {
    filename,
    canvasMode: canvasFrame.contentWindow?.fieldDisplayMode || "",
    editorMode: editorFrame.contentWindow?.fieldDisplayMode || "",
    canvasHistoryVisible: !!historyContainer,
    canvasHistorySrc: historyFrame?.getAttribute("src") || "",
    canvasHistoryLoaded: !!historyReady,
    canvasHistoryWidth: Math.round(bounds?.width || 0),
    editorHistoryVisible: !!editorHistoryStyle && editorHistoryStyle.visibility !== "hidden" && Number(editorHistoryStyle.opacity || "0") > 0,
    errors,
  };
  result.ok = result.canvasMode === "no-editor" &&
    result.editorMode === "editor-only" &&
    result.canvasHistoryVisible &&
    result.canvasHistorySrc.includes("/history") &&
    result.canvasHistoryLoaded &&
    result.canvasHistoryWidth >= Math.round(window.innerWidth * 0.8) &&
    !result.editorHistoryVisible &&
    result.errors.length === 0;
  return result;
} finally {
  window.removeEventListener("error", onError);
}
"""

MACRO_TRANSCRIPT_PANEL_JS = r"""
const delay = (ms) => new Promise(resolve => setTimeout(resolve, ms));
const errors = [];
const onError = (event) => errors.push(String(event.error && (event.error.stack || event.error.message) || event.message || event));
window.addEventListener("error", onError);
const waitFor = async (predicate, timeoutMs = 12000) => {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    const value = predicate();
    if (value) return value;
    await delay(100);
  }
  return predicate();
};
const panelVisible = (panel) => panel && panel.classList.contains("is-visible") && getComputedStyle(panel).display !== "none";
try {
  const boxes = [...document.querySelectorAll(".box")];
  const box = (window.selectedList && window.selectedList[0]) ||
    boxes.find((candidate) => String(candidate.textContent || "").includes("MacroTranscriptRegression")) ||
    boxes.at(-1) ||
    null;
  const uid = box ? (box.uid || box.getAttribute("uid") || box.getAttribute("data-uid") || "") : "";
  if (!box || !uid) return { ok: false, reason: "box not found", uid, boxCount: boxes.length, errors };
  if (typeof window.macroTranscriptPanel !== "function") return { ok: false, reason: "macroTranscriptPanel missing", uid, errors };

  if (typeof window.selectBox === "function") window.selectBox(box);
  if (typeof window.selectionContextChanged === "function") window.selectionContextChanged();
  window.macroTranscriptPanel();

  const panel = await waitFor(() => {
    const node = document.querySelector("#macro-recorder-panel");
    return panelVisible(node) ? node : null;
  }, 5000);
  if (!panel) return { ok: false, reason: "macro panel did not open", uid, errors };

  const rowReady = await waitFor(() => {
    const rows = [...panel.querySelectorAll(".macro-recorder-transcript-row")];
    const selectable = rows.find((row) => {
      const input = row.querySelector("input[data-transcript-index]");
      return input && !input.disabled;
    });
    return rows.length > 0 && selectable ? { rows, selectable } : null;
  }, 15000);
  const seedButton = await waitFor(() =>
    [...panel.querySelectorAll("[data-role='document-list'] button")]
      .find((button) => button.getAttribute("data-macro-name") === "ui-regression-panel"),
    12000
  );
  const slowButton = await waitFor(() =>
    [...panel.querySelectorAll("[data-role='document-list'] button")]
      .find((button) => button.getAttribute("data-macro-name") === "ui-regression-panel-slow"),
    12000
  );
  const batchButton = await waitFor(() =>
    [...panel.querySelectorAll("[data-role='document-list'] button")]
      .find((button) => button.getAttribute("data-macro-name") === "ui-regression-panel-batch"),
    12000
  );

  if (!rowReady || !seedButton || !slowButton || !batchButton) {
    return {
      ok: false,
      reason: "macro panel did not render seeded transcript/macro state",
      uid,
      visible: panelVisible(panel),
      rowCount: panel.querySelectorAll(".macro-recorder-transcript-row").length,
      documentMacros: [...panel.querySelectorAll("[data-role='document-list'] button")].map((button) => button.getAttribute("data-macro-name") || button.textContent.trim()),
      pageText: panel.querySelector("[data-role='transcript-page']")?.textContent?.trim() || "",
      statusText: panel.querySelector("[data-role='status']")?.textContent?.trim() || "",
      errors
    };
  }

  const selectableInput = rowReady.selectable.querySelector("input[data-transcript-index]");
  const selectableIndex = selectableInput.getAttribute("data-transcript-index") || "";
  selectableInput.click();
  await waitFor(() => (panel.querySelector("[data-role='transcript-selection']")?.textContent || "").includes("selected"), 3000);

  const details = rowReady.selectable.querySelector("details[data-transcript-detail-index]");
  if (details) {
    details.open = true;
    details.dispatchEvent(new Event("toggle", { bubbles: true }));
  }

  const nameInput = panel.querySelector("[data-role='name']");
  nameInput.value = "ui-regression-selection";
  nameInput.dispatchEvent(new Event("input", { bubbles: true }));
  panel.querySelector("[data-action='save-selected']")?.click();
  const savedButton = await waitFor(() =>
    [...panel.querySelectorAll("[data-role='document-list'] button")]
      .find((button) => button.getAttribute("data-macro-name") === "ui-regression-selection"),
    12000
  );

  seedButton.click();
  const contentLoaded = await waitFor(() => {
    const text = panel.querySelector("[data-role='content']")?.value || "";
    return text.includes("ui-regression-panel") && text.includes("operations:");
  }, 5000);
  const macroLaunchToast = [...document.querySelectorAll(".field-toast, .bubble")]
    .some((node) => String(node.textContent || "").includes("Macro 'ui-regression-panel' launch"));

  let macroPaletteHasBatch = false;
  if (typeof window.openAnywherePalette === "function") {
    window.openAnywherePalette();
    const paletteInput = await waitFor(() => document.querySelector(".afield-palette-input"), 3000);
    if (paletteInput) {
      paletteInput.value = "ui-regression-panel-batch";
      paletteInput.dispatchEvent(new Event("input", { bubbles: true }));
      macroPaletteHasBatch = !!(await waitFor(() =>
        [...document.querySelectorAll(".afield-palette-row")]
          .some((row) => String(row.textContent || "").includes("Run macro: ui-regression-panel-batch")),
        8000
      ));
      paletteInput.dispatchEvent(new KeyboardEvent("keydown", { key: "Escape", bubbles: true, cancelable: true }));
      await delay(150);
    }
  }

  const batchButtonForRun = await waitFor(() =>
    [...panel.querySelectorAll("[data-role='document-list'] button")]
      .find((button) => button.getAttribute("data-macro-name") === "ui-regression-panel-batch"),
    5000
  );
  if (batchButtonForRun) batchButtonForRun.click();
  const batchContentLoaded = await waitFor(() => {
    const text = panel.querySelector("[data-role='content']")?.value || "";
    return text.includes("ui-regression-panel-batch") && text.includes("Thread.sleep(1200)");
  }, 5000);
  const batchRunButton = panel.querySelector("[data-action='run']");
  const batchRunStart = Date.now();
  if (batchRunButton) batchRunButton.click();
  const batchMacroFeedbackAt = await waitFor(() => {
    const text = panel.querySelector("[data-role='feedback']")?.textContent?.trim() || "";
    return text.includes("Ran macro 'ui-regression-panel-batch'") ? Date.now() : 0;
  }, 8000);
  const batchMacroElapsedMs = batchMacroFeedbackAt ? batchMacroFeedbackAt - batchRunStart : -1;
  const batchMacroAwaitedLaunchGroup = batchMacroElapsedMs >= 900;

  const slowButtonForRun = await waitFor(() =>
    [...panel.querySelectorAll("[data-role='document-list'] button")]
      .find((button) => button.getAttribute("data-macro-name") === "ui-regression-panel-slow"),
    5000
  );
  if (slowButtonForRun) slowButtonForRun.click();
  const slowContentLoaded = await waitFor(() => {
    const text = panel.querySelector("[data-role='content']")?.value || "";
    return text.includes("ui-regression-panel-slow") && text.includes("millis: 3000");
  }, 5000);
  const runButton = panel.querySelector("[data-action='run']");
  const slowRunStart = Date.now();
  if (runButton) runButton.click();
  const slowMacroLaunchToastAt = await waitFor(() => {
    const hit = [...document.querySelectorAll(".field-toast, .bubble")]
      .some((node) => String(node.textContent || "").includes("Macro 'ui-regression-panel-slow' launch"));
    return hit ? Date.now() : 0;
  }, 1500);
  const slowMacroFeedbackAtToast = panel.querySelector("[data-role='feedback']")?.textContent?.trim() || "";
  const slowMacroLaunchToastQuick = !!slowMacroLaunchToastAt && slowMacroLaunchToastAt - slowRunStart < 1500;
  const slowMacroReplyPendingAtToast = !slowMacroFeedbackAtToast.includes("Ran macro 'ui-regression-panel-slow'");

  const rowSummaries = [...panel.querySelectorAll(".macro-recorder-transcript-summary")]
    .map((node) => String(node.textContent || "").trim())
    .filter(Boolean)
    .slice(0, 5);
  const pageText = panel.querySelector("[data-role='transcript-page']")?.textContent?.trim() || "";
  const selectionText = panel.querySelector("[data-role='transcript-selection']")?.textContent?.trim() || "";
  const feedbackText = panel.querySelector("[data-role='feedback']")?.textContent?.trim() || "";
  const contentText = panel.querySelector("[data-role='content']")?.value || "";
  const previewText = panel.querySelector("[data-role='preview']")?.textContent?.trim() || "";
  const documentMacros = [...panel.querySelectorAll("[data-role='document-list'] button")]
    .map((button) => button.getAttribute("data-macro-name") || button.textContent.trim());
  const rect = panel.getBoundingClientRect();

  panel.querySelector("[data-action='close']")?.click();
  await delay(250);
  const closed = !panel.classList.contains("is-visible");
  if (closed) {
    window.macroTranscriptPanel();
    await waitFor(() => panelVisible(panel), 3000);
  }
  const reopened = panelVisible(panel);

  const result = {
    uid,
    rowCount: panel.querySelectorAll(".macro-recorder-transcript-row").length,
    selectableIndex,
    rowSummaries,
    pageText,
    selectionText,
    feedbackText,
    documentMacros,
    contentLoaded: !!contentLoaded,
    contentHasFixture: contentText.includes("ui-regression-panel"),
    previewHasFixture: previewText.includes("ui-regression-panel") || previewText.includes("create"),
    macroLaunchToast,
    macroPaletteHasBatch,
    batchContentLoaded: !!batchContentLoaded,
    batchMacroElapsedMs,
    batchMacroAwaitedLaunchGroup,
    slowContentLoaded: !!slowContentLoaded,
    slowMacroLaunchToastQuick,
    slowMacroReplyPendingAtToast,
    slowMacroFeedbackAtToast,
    detailOpen: !!(details && details.open),
    savedSelectionMacro: !!savedButton,
    width: Math.round(rect.width || 0),
    height: Math.round(rect.height || 0),
    closed,
    reopened,
    errors,
  };
  result.ok = result.rowCount > 0 &&
    !!result.selectableIndex &&
    result.selectionText.includes("selected") &&
    result.savedSelectionMacro &&
    result.documentMacros.includes("ui-regression-panel") &&
    result.documentMacros.includes("ui-regression-selection") &&
    result.contentLoaded &&
    result.contentHasFixture &&
    result.previewHasFixture &&
    result.macroLaunchToast &&
    result.macroPaletteHasBatch &&
    result.batchContentLoaded &&
    result.batchMacroAwaitedLaunchGroup &&
    result.slowContentLoaded &&
    result.slowMacroLaunchToastQuick &&
    result.slowMacroReplyPendingAtToast &&
    result.width > 0 &&
    result.height > 0 &&
    result.closed &&
    result.reopened &&
    result.errors.length === 0;
  return result;
} finally {
  window.removeEventListener("error", onError);
}
"""

INLINE_VISUALIZERS_JS = r"""
const delay = (ms) => new Promise(resolve => setTimeout(resolve, ms));
const errors = [];
const onError = (event) => errors.push(String(event.error && (event.error.stack || event.error.message) || event.message || event));
window.addEventListener("error", onError);
const waitFor = async (predicate, timeoutMs = 15000) => {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    const value = predicate();
    if (value) return value;
    await delay(100);
  }
  return predicate();
};
try {
  const ed = window.globalEditor;
  const boxes = [...document.querySelectorAll(".box")];
  const box = (window.selectedList && window.selectedList[0]) ||
    boxes.find((candidate) => String(candidate.textContent || "").includes("InlineVisualizersRegression")) ||
    boxes.at(-1) ||
    null;
  const uid = box ? (box.uid || box.getAttribute("uid") || box.getAttribute("data-uid") || "") : "";
  if (!ed) return { ok: false, reason: "globalEditor missing", errors };
  if (!box || !uid) return { ok: false, reason: "box not found", uid, boxCount: boxes.length, errors };

  if (typeof window.selectBox === "function") window.selectBox(box);
  if (typeof window.selectionContextChanged === "function") window.selectionContextChanged();
  if (typeof window.switchToProperty === "function") window.switchToProperty(uid, "code");
  ed.focus();

  const ready = await waitFor(() => {
    const outputs = [...document.querySelectorAll(".execution_output")];
    const svgs = outputs.flatMap((node) => [...node.querySelectorAll("svg")]);
    const text = outputs.map((node) => String(node.textContent || "")).join("\n");
    const html = outputs.map((node) => String(node.innerHTML || "")).join("\n");
    const rects = outputs.map((node) => node.getBoundingClientRect());
    const visible = rects.filter((rect) => rect.width > 0 && rect.height > 0).length;
    const code = ed.getModel() ? String(ed.getModel().getValue() || "") : "";
    const hasHistogramCall = code.includes(".histogram(");
    const hasHistoryCall = code.includes(".history(");
    const hasStatsCall = code.includes(".stats(");
    const hasPianoRollCall = code.includes(".pianoRoll(");
    const hasPianoRollSvg = text.includes("Piano Roll") || html.includes("notes") || html.includes("Waiting for notes");
    const hasStatsText = /mean|median|std|count|min|max/i.test(text);
    const hasChartSvg = svgs.length >= 3;
    return hasHistogramCall && hasHistoryCall && hasStatsCall && hasPianoRollCall && hasChartSvg && visible >= 3
      ? { outputs, svgs, text, html, visible, hasPianoRollSvg, hasStatsText, code }
      : null;
  }, 20000);

  const outputs = [...document.querySelectorAll(".execution_output")];
  const svgs = outputs.flatMap((node) => [...node.querySelectorAll("svg")]);
  const rects = outputs.map((node) => node.getBoundingClientRect());
  const text = outputs.map((node) => String(node.textContent || "")).join("\n");
  const html = outputs.map((node) => String(node.innerHTML || "")).join("\n");
  const fieldErrors = [...document.querySelectorAll(".kotlin-error, .field-error, .error")]
    .map((node) => String(node.textContent || "").trim())
    .filter(Boolean)
    .slice(0, 6);
  const result = {
    uid,
    outputCount: outputs.length,
    svgCount: svgs.length,
    visibleCount: rects.filter((rect) => rect.width > 0 && rect.height > 0).length,
    widths: rects.map((rect) => Math.round(rect.width || 0)).slice(0, 6),
    heights: rects.map((rect) => Math.round(rect.height || 0)).slice(0, 6),
    hasHistogramCall: ed.getModel() ? ed.getModel().getValue().includes(".histogram(") : false,
    hasHistoryCall: ed.getModel() ? ed.getModel().getValue().includes(".history(") : false,
    hasStatsCall: ed.getModel() ? ed.getModel().getValue().includes(".stats(") : false,
    hasPianoRollCall: ed.getModel() ? ed.getModel().getValue().includes(".pianoRoll(") : false,
    hasPianoRollSvg: text.includes("Piano Roll") || html.includes("notes") || html.includes("Waiting for notes"),
    hasStatsText: /mean|median|std|count|min|max/i.test(text),
    editorFocus: ed.hasTextFocus(),
    fieldErrors,
    errors,
  };
  result.ok = !!ready &&
    result.svgCount >= 3 &&
    result.visibleCount >= 3 &&
    result.hasHistogramCall &&
    result.hasHistoryCall &&
    result.hasStatsCall &&
    result.hasPianoRollCall &&
    result.hasPianoRollSvg &&
    result.editorFocus &&
    result.fieldErrors.length === 0 &&
    result.errors.length === 0;
  return result;
} finally {
  window.removeEventListener("error", onError);
}
"""

FOCUS_KEYBOARD_CONTRACT_JS = r"""
const delay = (ms) => new Promise(resolve => setTimeout(resolve, ms));
const errors = [];
const onError = (event) => errors.push(String(event.error && (event.error.stack || event.error.message) || event.message || event));
window.addEventListener("error", onError);
const waitFor = async (predicate, timeoutMs = 5000) => {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    const value = predicate();
    if (value) return value;
    await delay(75);
  }
  return predicate();
};
const paletteVisible = () => {
  const palette = document.querySelector(".afield-palette");
  return palette ? getComputedStyle(palette).display !== "none" : false;
};
const activeRow = () => document.querySelector(".afield-palette-row.is-active");
const activeIndex = () => activeRow() ? Number(activeRow().getAttribute("data-index") || "-1") : -1;
const keyCodes = { ArrowDown: 40, ArrowUp: 38, Escape: 27, Enter: 13 };
const dispatchKey = (target, key) => {
  target.focus?.();
  const event = new KeyboardEvent("keydown", {
    key,
    code: key,
    keyCode: keyCodes[key] || 0,
    which: keyCodes[key] || 0,
    bubbles: true,
    cancelable: true,
    composed: true,
  });
  target.dispatchEvent(event);
  return event.defaultPrevented;
};
try {
  const ed = window.globalEditor;
  if (!ed) return { ok: false, reason: "globalEditor missing", errors };
  if (typeof window.openFieldCommandPalette !== "function") return { ok: false, reason: "openFieldCommandPalette missing", errors };

  ed.focus();
  window.__uiFocusKeyboardActionRan = false;
  window.__uiFocusKeyboardActionThis = "";
  ed.addAction({
    id: "ui-focus-keyboard-action",
    label: "UI Focus Keyboard Action",
    run: function(editorArg) {
      window.__uiFocusKeyboardActionRan = editorArg === ed;
      window.__uiFocusKeyboardActionThis = this && this.id || "";
      ed.focus();
    },
  });

  window.openFieldCommandPalette({ includeEditor: true, editor: ed });
  const input = await waitFor(() => document.querySelector(".afield-palette-input"), 3000);
  if (!input) return { ok: false, reason: "palette input missing", errors };
  await waitFor(() => document.querySelectorAll(".afield-palette-row").length >= 2, 5000);
  const startIndex = activeIndex();
  dispatchKey(input, "ArrowDown");
  await waitFor(() => activeIndex() === startIndex + 1, 2000);
  const downIndex = activeIndex();
  dispatchKey(input, "ArrowUp");
  await waitFor(() => activeIndex() === startIndex, 2000);
  const upIndex = activeIndex();
  const escapePrevented = dispatchKey(input, "Escape");
  await waitFor(() => !paletteVisible(), 3000);
  await waitFor(() => ed.hasTextFocus(), 3000);
  const escapeClosed = !paletteVisible();
  const focusAfterEscape = ed.hasTextFocus();

  window.openFieldCommandPalette({ includeEditor: true, editor: ed });
  const input2 = await waitFor(() => document.querySelector(".afield-palette-input"), 3000);
  input2.value = "ui focus keyboard action";
  input2.dispatchEvent(new Event("input", { bubbles: true }));
  await waitFor(() => {
    const row = activeRow();
    return row && String(row.textContent || "").toLowerCase().includes("ui focus keyboard action");
  }, 5000);
  const rowsBeforeEnter = [...document.querySelectorAll(".afield-palette-row")]
    .map((row) => String(row.textContent || "").trim())
    .slice(0, 5);
  const enterPrevented = dispatchKey(input2, "Enter");
  await waitFor(() => window.__uiFocusKeyboardActionRan && !paletteVisible() && ed.hasTextFocus(), 3000);

  const result = {
    startIndex,
    downIndex,
    upIndex,
    escapeClosed,
    escapePrevented,
    focusAfterEscape,
    rowsBeforeEnter,
    enterPrevented,
    enterRan: !!window.__uiFocusKeyboardActionRan,
    actionThis: window.__uiFocusKeyboardActionThis,
    paletteVisible: paletteVisible(),
    editorFocus: ed.hasTextFocus(),
    errors,
  };
  result.ok = result.startIndex === 0 &&
    result.downIndex === 1 &&
    result.upIndex === 0 &&
    result.escapeClosed &&
    result.escapePrevented &&
    result.focusAfterEscape &&
    result.enterRan &&
    result.enterPrevented &&
    result.actionThis === "ui-focus-keyboard-action" &&
    !result.paletteVisible &&
    result.editorFocus &&
    result.errors.length === 0;
  return result;
} finally {
  window.removeEventListener("error", onError);
}
"""

FOCUS_KEYBOARD_SETUP_JS = r"""
const delay = (ms) => new Promise(resolve => setTimeout(resolve, ms));
const errors = [];
const onError = (event) => errors.push(String(event.error && (event.error.stack || event.error.message) || event.message || event));
window.addEventListener("error", onError);
const waitFor = async (predicate, timeoutMs = 5000) => {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    const value = predicate();
    if (value) return value;
    await delay(75);
  }
  return predicate();
};
try {
  const ed = window.globalEditor;
  if (!ed) return { ok: false, reason: "globalEditor missing", errors };
  if (typeof window.openFieldCommandPalette !== "function") return { ok: false, reason: "openFieldCommandPalette missing", errors };
  ed.focus();
  window.__uiFocusKeyboardActionRan = false;
  window.__uiFocusKeyboardActionThis = "";
  ed.addAction({
    id: "ui-focus-keyboard-action",
    label: "UI Focus Keyboard Action",
    run: function(editorArg) {
      window.__uiFocusKeyboardActionRan = editorArg === ed;
      window.__uiFocusKeyboardActionThis = this && this.id || "";
      ed.focus();
    },
  });
  window.openFieldCommandPalette({ includeEditor: true, editor: ed });
  const input = await waitFor(() => document.querySelector(".afield-palette-input"), 3000);
  if (!input) return { ok: false, reason: "palette input missing", errors };
  input.focus();
  const rowCount = await waitFor(() => document.querySelectorAll(".afield-palette-row").length >= 2 ? document.querySelectorAll(".afield-palette-row").length : 0, 5000);
  const active = document.querySelector(".afield-palette-row.is-active");
  const result = {
    rowCount,
    activeIndex: active ? Number(active.getAttribute("data-index") || "-1") : -1,
    inputFocused: document.activeElement === input,
    errors,
  };
  result.ok = result.rowCount >= 2 && result.activeIndex === 0 && result.inputFocused && result.errors.length === 0;
  return result;
} finally {
  window.removeEventListener("error", onError);
}
"""

FOCUS_KEYBOARD_ACTIVE_JS = r"""
const expected = Number(window.__uiFocusExpectedActiveIndex);
const errors = [];
const onError = (event) => errors.push(String(event.error && (event.error.stack || event.error.message) || event.message || event));
window.addEventListener("error", onError);
try {
  const active = document.querySelector(".afield-palette-row.is-active");
  const input = document.querySelector(".afield-palette-input");
  const result = {
    expected,
    activeIndex: active ? Number(active.getAttribute("data-index") || "-1") : -1,
    activeText: active ? String(active.textContent || "").trim() : "",
    inputFocused: document.activeElement === input,
    paletteVisible: (() => {
      const palette = document.querySelector(".afield-palette");
      return palette ? getComputedStyle(palette).display !== "none" : false;
    })(),
    errors,
  };
  result.ok = Number.isFinite(result.expected) &&
    result.paletteVisible &&
    result.activeIndex === expected &&
    result.inputFocused &&
    result.errors.length === 0;
  return result;
} finally {
  window.removeEventListener("error", onError);
}
"""

FOCUS_KEYBOARD_ESCAPE_ASSERT_JS = r"""
const delay = (ms) => new Promise(resolve => setTimeout(resolve, ms));
const errors = [];
const onError = (event) => errors.push(String(event.error && (event.error.stack || event.error.message) || event.message || event));
window.addEventListener("error", onError);
const waitFor = async (predicate, timeoutMs = 3000) => {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    const value = predicate();
    if (value) return value;
    await delay(75);
  }
  return predicate();
};
try {
  const ed = window.globalEditor;
  await waitFor(() => {
    const palette = document.querySelector(".afield-palette");
    return palette && getComputedStyle(palette).display === "none";
  }, 3000);
  await waitFor(() => ed && ed.hasTextFocus && ed.hasTextFocus(), 3000);
  const palette = document.querySelector(".afield-palette");
  const result = {
    paletteVisible: palette ? getComputedStyle(palette).display !== "none" : null,
    editorFocus: ed && ed.hasTextFocus ? ed.hasTextFocus() : false,
    errors,
  };
  result.ok = result.paletteVisible === false && result.editorFocus && result.errors.length === 0;
  return result;
} finally {
  window.removeEventListener("error", onError);
}
"""

FOCUS_KEYBOARD_ENTER_SETUP_JS = r"""
const delay = (ms) => new Promise(resolve => setTimeout(resolve, ms));
const errors = [];
const onError = (event) => errors.push(String(event.error && (event.error.stack || event.error.message) || event.message || event));
window.addEventListener("error", onError);
const waitFor = async (predicate, timeoutMs = 5000) => {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    const value = predicate();
    if (value) return value;
    await delay(75);
  }
  return predicate();
};
try {
  const ed = window.globalEditor;
  if (!ed || typeof window.openFieldCommandPalette !== "function") return { ok: false, reason: "palette/editor missing", errors };
  window.__uiFocusKeyboardActionRan = false;
  window.__uiFocusKeyboardActionThis = "";
  ed.focus();
  window.openFieldCommandPalette({ includeEditor: true, editor: ed });
  const input = await waitFor(() => document.querySelector(".afield-palette-input"), 3000);
  if (!input) return { ok: false, reason: "palette input missing", errors };
  input.value = "ui focus keyboard action";
  input.dispatchEvent(new Event("input", { bubbles: true }));
  input.focus();
  const active = await waitFor(() => {
    const row = document.querySelector(".afield-palette-row.is-active");
    return row && String(row.textContent || "").toLowerCase().includes("ui focus keyboard action") ? row : null;
  }, 5000);
  const rows = [...document.querySelectorAll(".afield-palette-row")].map((row) => String(row.textContent || "").trim()).slice(0, 5);
  const result = {
    hasActive: !!active,
    rows,
    inputFocused: document.activeElement === input,
    errors,
  };
  result.ok = result.hasActive && result.inputFocused && result.errors.length === 0;
  return result;
} finally {
  window.removeEventListener("error", onError);
}
"""

FOCUS_KEYBOARD_ENTER_ASSERT_JS = r"""
const delay = (ms) => new Promise(resolve => setTimeout(resolve, ms));
const errors = [];
const onError = (event) => errors.push(String(event.error && (event.error.stack || event.error.message) || event.message || event));
window.addEventListener("error", onError);
const waitFor = async (predicate, timeoutMs = 3000) => {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    const value = predicate();
    if (value) return value;
    await delay(75);
  }
  return predicate();
};
try {
  const ed = window.globalEditor;
  await waitFor(() => window.__uiFocusKeyboardActionRan && ed && ed.hasTextFocus && ed.hasTextFocus(), 3000);
  const palette = document.querySelector(".afield-palette");
  const result = {
    enterRan: !!window.__uiFocusKeyboardActionRan,
    actionThis: window.__uiFocusKeyboardActionThis,
    paletteVisible: palette ? getComputedStyle(palette).display !== "none" : null,
    editorFocus: ed && ed.hasTextFocus ? ed.hasTextFocus() : false,
    errors,
  };
  result.ok = result.enterRan &&
    result.actionThis === "ui-focus-keyboard-action" &&
    result.paletteVisible === false &&
    result.editorFocus &&
    result.errors.length === 0;
  return result;
} finally {
  window.removeEventListener("error", onError);
}
"""


def shell_join(parts: list[str]) -> str:
    import shlex

    return shlex.join(parts)


def backdoor_command_prefix() -> list[str]:
    uv = shutil.which("uv")
    if uv:
        return [uv, "run", "--project", str(REPO_ROOT), "--group", "backdoor", "python"]
    return [sys.executable]


def write_generated_file(relative: str, text: str) -> Path:
    path = GENERATED_ROOT / relative
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text.rstrip() + "\n", encoding="utf-8")
    return path


def prepare_generated_files() -> dict[str, Path]:
    return {
        "stack_marker_snippet": write_generated_file("stack-marker.kt", STACK_MARKER_SNIPPET),
        "editable_lines_snippet": write_generated_file("editable-lines.kt", EDITABLE_LINES_SNIPPET),
        "static_lines_snippet": write_generated_file("static-lines.kt", STATIC_LINES_SNIPPET),
        "controller_control_group_snippet": write_generated_file("controller-control-group.kt", CONTROLLER_CONTROL_GROUP_SNIPPET),
        "selection_screenshot_snippet": write_generated_file("selection-screenshot.kt", SELECTION_SCREENSHOT_SNIPPET),
        "history_panel_snippet": write_generated_file("history-panel.kt", HISTORY_PANEL_SNIPPET),
        "macro_transcript_panel_snippet": write_generated_file("macro-transcript-panel.kt", MACRO_TRANSCRIPT_PANEL_SNIPPET),
        "macro_transcript_panel_macro": write_generated_file("macro-transcript-panel.yaml", MACRO_TRANSCRIPT_PANEL_MACRO),
        "macro_transcript_panel_slow_macro": write_generated_file("macro-transcript-panel-slow.yaml", MACRO_TRANSCRIPT_PANEL_SLOW_MACRO),
        "macro_transcript_panel_batch_macro": write_generated_file("macro-transcript-panel-batch.yaml", MACRO_TRANSCRIPT_PANEL_BATCH_MACRO),
        "inline_visualizers_snippet": write_generated_file("inline-visualizers.kt", INLINE_VISUALIZERS_SNIPPET),
        "editor_sync_initial_snippet": write_generated_file("editor-sync-initial.kt", EDITOR_SYNC_INITIAL_SNIPPET),
        "output_area_basics_snippet": write_generated_file("output-area-basics.kt", OUTPUT_AREA_BASICS_SNIPPET),
        "this_binding_js": write_generated_file("command-palette-this-binding.js", COMMAND_PALETTE_THIS_BINDING_JS),
        "monaco_context_menu_actions_js": write_generated_file("monaco-context-menu-actions.js", MONACO_CONTEXT_MENU_ACTIONS_JS),
        "palette_smoke_sweep_js": write_generated_file("command-palette-smoke-sweep.js", COMMAND_PALETTE_SMOKE_SWEEP_JS),
        "editor_open_edit_sync_js": write_generated_file("editor-open-edit-sync.js", EDITOR_OPEN_EDIT_SYNC_JS),
        "insert_stack_marker_js": write_generated_file("insert-stack-marker.js", INSERT_STACK_MARKER_JS),
        "output_area_basics_js": write_generated_file("output-area-basics.js", OUTPUT_AREA_BASICS_JS),
        "toast_restack_js": write_generated_file("toast-restack.js", TOAST_RESTACK_JS),
        "controllers_editable_lines_js": write_generated_file("controllers-editable-lines.js", CONTROLLERS_EDITABLE_LINES_JS),
        "controllers_static_lines_js": write_generated_file("controllers-static-lines.js", CONTROLLERS_STATIC_LINES_JS),
        "controllers_control_group_js": write_generated_file("controllers-control-group.js", CONTROLLERS_CONTROL_GROUP_JS),
        "controllers_mobile_workspace_js": write_generated_file("controllers-mobile-workspace.js", CONTROLLERS_MOBILE_WORKSPACE_JS),
        "selection_screenshot_backdoor_js": write_generated_file("selection-screenshot-backdoor.js", SELECTION_SCREENSHOT_BACKDOOR_JS),
        "selection_subtitle_box_js": write_generated_file("selection-subtitle-box.js", SELECTION_SUBTITLE_BOX_JS),
        "language_selection_customization_js": write_generated_file("language-selection-customization.js", LANGUAGE_SELECTION_CUSTOMIZATION_JS),
        "language_selection_box_js": write_generated_file("language-selection-box.js", LANGUAGE_SELECTION_BOX_JS),
        "history_panel_open_js": write_generated_file("history-panel-open.js", HISTORY_PANEL_OPEN_JS),
        "history_panel_close_js": write_generated_file("history-panel-close.js", HISTORY_PANEL_CLOSE_JS),
        "visionos_shared_history_panel_js": write_generated_file("visionos-shared-history-panel.js", VISIONOS_SHARED_HISTORY_PANEL_JS),
        "macro_transcript_panel_js": write_generated_file("macro-transcript-panel.js", MACRO_TRANSCRIPT_PANEL_JS),
        "inline_visualizers_js": write_generated_file("inline-visualizers.js", INLINE_VISUALIZERS_JS),
        "focus_keyboard_contract_js": write_generated_file("focus-keyboard-contract.js", FOCUS_KEYBOARD_CONTRACT_JS),
        "focus_keyboard_setup_js": write_generated_file("focus-keyboard-setup.js", FOCUS_KEYBOARD_SETUP_JS),
        "focus_keyboard_active_js": write_generated_file("focus-keyboard-active.js", FOCUS_KEYBOARD_ACTIVE_JS),
        "focus_keyboard_escape_assert_js": write_generated_file("focus-keyboard-escape-assert.js", FOCUS_KEYBOARD_ESCAPE_ASSERT_JS),
        "focus_keyboard_enter_setup_js": write_generated_file("focus-keyboard-enter-setup.js", FOCUS_KEYBOARD_ENTER_SETUP_JS),
        "focus_keyboard_enter_assert_js": write_generated_file("focus-keyboard-enter-assert.js", FOCUS_KEYBOARD_ENTER_ASSERT_JS),
    }


def tests(files: dict[str, Path], *, port: int, timeout: float) -> list[UiRegression]:
    pw_timeout = str(max(timeout, PLAYWRIGHT_TIMEOUT))
    timeout_text = str(timeout)
    return [
        UiRegression(
            id="command-palette-action-this-binding",
            description="Field command palette calls Monaco editor actions with their original this binding.",
            commands=[
                "new-box --name CommandPaletteBindingRegression",
                shell_join(["set", "@last", "code", "println(1)"]),
                "editor-open @last --property code",
                "sleep 0.5",
                shell_join(["page-eval", "--body", "--file", str(files["this_binding_js"]), "--timeout", pw_timeout]),
            ],
            screenshot=shell_join(["screenshot-window", "{screenshot}", "--uid", "@last", "--with-editor", "--no-pan", "--timeout", pw_timeout]),
        ),
        UiRegression(
            id="command-palette-smoke-sweep",
            description="Field command palette runs document, property, and editor command categories without console errors.",
            commands=[
                "new-box --name PaletteSmokeSeed",
                shell_join(["set", "@last", "code", "println(\"palette smoke\")"]),
                "editor-open @last --property code",
                "sleep 0.5",
                shell_join(["page-eval", "--body", "--file", str(files["palette_smoke_sweep_js"]), "--timeout", pw_timeout]),
            ],
            screenshot=shell_join(["screenshot-window", "{screenshot}", "--uid", "@last", "--with-editor", "--no-pan", "--timeout", pw_timeout]),
        ),
        UiRegression(
            id="monaco-context-menu-actions",
            description="Monaco context menu shows one copy of each Field editor action after repeated editor refreshes.",
            commands=[
                "new-box --name MonacoContextMenuRegression",
                shell_join(["set", "@last", "code", "println(\"context menu regression\")"]),
                "editor-open @last --property code",
                "sleep 0.5",
                shell_join(["page-eval", "--body", "const ed = window.globalEditor; if (!ed) return { ok: false, reason: 'globalEditor missing' }; for (let i = 0; i < 5; i++) { if (typeof window.selectionContextChanged === 'function') window.selectionContextChanged(); await new Promise(resolve => setTimeout(resolve, 80)); } ed.focus(); ed.setPosition({ lineNumber: 1, column: 1 }); return { ok: true };", "--timeout", pw_timeout]),
                shell_join(["page-eval", "--body", "--file", str(files["monaco_context_menu_actions_js"]), "--timeout", pw_timeout]),
            ],
            screenshot=shell_join(["screenshot-window", "{screenshot}", "--uid", "@last", "--with-editor", "--no-pan", "--timeout", pw_timeout]),
        ),
        UiRegression(
            id="editor-open-edit-sync",
            description="Monaco edits sync to the selected box and survive property switches.",
            commands=[
                "new-box --name EditorSyncRegression",
                shell_join(["set", "@last", "code", "--file", str(files["editor_sync_initial_snippet"])]),
                "editor-open @last --property code",
                "sleep 0.5",
                shell_join(["page-eval", "--body", "--file", str(files["editor_open_edit_sync_js"]), "--timeout", pw_timeout]),
            ],
            screenshot=shell_join(["screenshot-window", "{screenshot}", "--uid", "@last", "--with-editor", "--no-pan", "--timeout", pw_timeout]),
        ),
        UiRegression(
            id="insert-stack-marker-command",
            description="Insert Stack Mark works from the Field command palette and returns focus to Monaco.",
            commands=[
                "new-box --name StackMarkerRegression",
                shell_join(["set", "@last", "code", "--file", str(files["stack_marker_snippet"])]),
                "editor-open @last --property code",
                "sleep 0.5",
                shell_join(["page-eval", "--body", "--file", str(files["insert_stack_marker_js"]), "--timeout", pw_timeout]),
            ],
            screenshot=shell_join(["screenshot-window", "{screenshot}", "--uid", "@last", "--with-editor", "--no-pan", "--timeout", pw_timeout]),
        ),
        UiRegression(
            id="output-area-basics",
            description="display(...) creates and replaces output channel content, executes embedded scripts, and renders visible output.",
            commands=[
                "new-box --name OutputAreaBasicsRegression",
                shell_join(["run", "@last", "--file", str(files["output_area_basics_snippet"]), "--timeout", timeout_text]),
                "sleep 0.5",
                shell_join(["page-eval", "--body", "--file", str(files["output_area_basics_js"]), "--timeout", pw_timeout]),
            ],
            screenshot=shell_join(["screenshot-window", "{screenshot}", "--uid", "@last", "--open-outputs", "--no-pan", "--timeout", pw_timeout]),
        ),
        UiRegression(
            id="toast-restack",
            description="Notification toasts restack to the bottom-left after older toasts expire.",
            commands=[
                "sleep 0.5",
                shell_join(["page-eval", "--body", "--file", str(files["toast_restack_js"]), "--timeout", pw_timeout]),
            ],
            screenshot=shell_join(["screenshot-window", "{screenshot}", "--timeout", pw_timeout]),
        ),
        UiRegression(
            id="controllers-editable-lines-output",
            description="/controllers outputs render editable lines without resize/flicker feedback loops.",
            commands=[
                "new-box --name EditableLinesRegression",
                shell_join(["run", "@last", "--file", str(files["editable_lines_snippet"]), "--timeout", timeout_text]),
                "sleep 0.5",
                shell_join(["page-eval", "--body", "--file", str(files["controllers_editable_lines_js"]), "--timeout", pw_timeout]),
            ],
            screenshot=shell_join(["screenshot-window", "{screenshot}", "--timeout", pw_timeout]),
        ),
        UiRegression(
            id="controllers-static-lines-output",
            description="/controllers outputs render non-editable lines as an initialized, pannable SVG iframe.",
            commands=[
                "new-box --name StaticLinesRegression",
                shell_join(["run", "@last", "--file", str(files["static_lines_snippet"]), "--timeout", timeout_text]),
                "sleep 0.5",
                shell_join(["page-eval", "--body", "--file", str(files["controllers_static_lines_js"]), "--timeout", pw_timeout]),
            ],
            screenshot=shell_join(["screenshot-window", "{screenshot}", "--timeout", pw_timeout]),
        ),
        UiRegression(
            id="controllers-control-group",
            description="/controllers renders native controlGroup widgets on both controls and outputs pages.",
            commands=[
                "new-box --name ControllerControlsRegression",
                shell_join(["run", "@last", "--file", str(files["controller_control_group_snippet"]), "--timeout", timeout_text]),
                "sleep 0.5",
                shell_join(["page-eval", "--body", "--file", str(files["controllers_control_group_js"]), "--timeout", pw_timeout]),
            ],
            screenshot=shell_join(["screenshot-window", "{screenshot}", "--timeout", pw_timeout]),
        ),
        UiRegression(
            id="controllers-mobile-workspace",
            description="/controllers provides phone-sized AI4 chat, Open Anything selection, and Monaco editing with compact keyboard-height layout.",
            commands=[
                "new-box --name MobileControllerRegression",
                shell_join(["set", "@last", "code", "println(\"mobile controller editor\")"]),
                "editor-open @last --property code",
                "sleep 0.75",
                shell_join(["page-eval", "--body", "--file", str(files["controllers_mobile_workspace_js"]), "--timeout", pw_timeout]),
            ],
            screenshot=shell_join(["screenshot-window", "{screenshot}", "--timeout", pw_timeout]),
        ),
        UiRegression(
            id="selection-screenshot-backdoor",
            description="Selection state and box/editor/window screenshots agree on the active target box.",
            commands=[
                "new-box --name ScreenshotBackdoorSource",
                shell_join(["set", "@last", "code", "println(\"selection screenshot source\")"]),
                "new-box --name ScreenshotBackdoorTarget",
                shell_join(["run", "@last", "--file", str(files["selection_screenshot_snippet"]), "--timeout", timeout_text]),
                "editor-open @last --property code",
                "sleep 0.5",
                shell_join(["page-eval", "--body", "--file", str(files["selection_screenshot_backdoor_js"]), "--timeout", pw_timeout]),
                shell_join(["screenshot-box", "@last", str(BUILD_ROOT / "scratch" / "selection-screenshot-backdoor-box.png"), "--padding", "16", "--open-outputs", "--no-pan", "--timeout", pw_timeout]),
                shell_join(["screenshot-editor", "@last", str(BUILD_ROOT / "scratch" / "selection-screenshot-backdoor-editor.png"), "--padding", "10", "--no-pan", "--timeout", pw_timeout]),
            ],
            screenshot=shell_join(["screenshot-window", "{screenshot}", "--uid", "@last", "--with-editor", "--open-outputs", "--no-pan", "--timeout", pw_timeout]),
        ),
        UiRegression(
            id="selection-highlight-file-proxy",
            description="Selected file-proxy boxes keep the visible selection ring on the box outline even with a subtitle.",
            commands=[
                "new-box --name SelectionSubtitleRegression",
                shell_join(["set", "@last", "file_proxy_mode", "absolute"]),
                shell_join(["set", "@last", "file_proxy_path", "/tmp/selection-subtitle-regression.txt"]),
                "sleep 0.5",
                shell_join(["page-eval", "--body", "--file", str(files["selection_subtitle_box_js"]), "--timeout", pw_timeout]),
            ],
            screenshot=shell_join(["screenshot-box", "@last", "{screenshot}", "--padding", "16", "--timeout", pw_timeout]),
        ),
        UiRegression(
            id="selection-highlight-language-box",
            description="Selected non-Kotlin language boxes keep the normal selection border while showing the language label.",
            commands=[
                "new-box --name LanguageSelectionRegression",
                shell_join(["set", "@last", "customization", "--file", str(files["language_selection_customization_js"])]),
                "sleep 0.5",
                shell_join(["page-eval", "--body", "--file", str(files["language_selection_box_js"]), "--timeout", pw_timeout]),
            ],
            screenshot=shell_join(["screenshot-box", "@last", "{screenshot}", "--padding", "16", "--timeout", pw_timeout]),
        ),
        UiRegression(
            id="history-panel",
            description="Document history iframe loads versions, supports basic interactions, and closes back to the editor.",
            document=f"ui-regression-history-{int(time.time())}",
            commands=[
                "new-box --name HistoryPanelRegression",
                shell_join(["run", "@last", "--file", str(files["history_panel_snippet"]), "--timeout", timeout_text]),
                "editor-open @last --property code",
                "sleep 4.0",
                shell_join(["page-eval", "--body", "--file", str(files["history_panel_open_js"]), "--timeout", pw_timeout]),
                shell_join(["screenshot-window", str(BUILD_ROOT / "scratch" / "history-panel-history.png"), "--timeout", pw_timeout]),
                shell_join(["page-eval", "--body", "--file", str(files["history_panel_close_js"]), "--timeout", pw_timeout]),
            ],
            screenshot=shell_join(["screenshot-window", "{screenshot}", "--uid", "@last", "--with-editor", "--open-outputs", "--no-pan", "--timeout", pw_timeout]),
        ),
        UiRegression(
            id="visionos-shared-history-panel",
            description="An editor-only dock routes the History panel to its paired no-editor canvas dock.",
            document=f"ui-regression-visionos-history-{int(time.time())}",
            commands=[
                "new-box --name VisionOSSharedHistoryRegression",
                shell_join(["run", "@last", "--file", str(files["history_panel_snippet"]), "--timeout", timeout_text]),
                "sleep 4.0",
                shell_join(["page-eval", "--body", "--file", str(files["visionos_shared_history_panel_js"]), "--timeout", pw_timeout]),
            ],
            screenshot=shell_join(["screenshot-window", "{screenshot}", "--timeout", pw_timeout]),
        ),
        UiRegression(
            id="macro-transcript-panel",
            description="Macro recorder panel renders transcript rows, selection controls, macro browser state, feedback, and close/reopen behavior.",
            document=f"ui-regression-macro-{int(time.time())}",
            commands=[
                "new-box --name MacroTranscriptRegression",
                shell_join(["transcript", "start", "--label", "ui-regression-start", "--timeout", timeout_text]),
                shell_join(["run", "@last", "--file", str(files["macro_transcript_panel_snippet"]), "--timeout", timeout_text]),
                shell_join(["macro", "write", "ui-regression-panel", "--file", str(files["macro_transcript_panel_macro"]), "--scope", "document", "--timeout", timeout_text]),
                shell_join(["macro", "write", "ui-regression-panel-slow", "--file", str(files["macro_transcript_panel_slow_macro"]), "--scope", "document", "--timeout", timeout_text]),
                shell_join(["macro", "write", "ui-regression-panel-batch", "--file", str(files["macro_transcript_panel_batch_macro"]), "--scope", "document", "--timeout", timeout_text]),
                shell_join(["macro", "run", "ui-regression-panel", "--scope", "document", "--timeout", timeout_text]),
                "editor-open @last --property code",
                "sleep 1.0",
                shell_join(["page-eval", "--body", "--file", str(files["macro_transcript_panel_js"]), "--timeout", pw_timeout]),
            ],
            screenshot=shell_join(["screenshot-window", "{screenshot}", "--timeout", pw_timeout]),
        ),
        UiRegression(
            id="inline-visualizers-editor",
            description="Inline Monaco visualizers render histogram, history, stats, and piano-roll SVG view zones in the editor.",
            commands=[
                "new-box --name InlineVisualizersRegression",
                shell_join(["set", "@last", "code", "--file", str(files["inline_visualizers_snippet"])]),
                "editor-open @last --property code",
                shell_join(["launch", "@last", "--file", str(files["inline_visualizers_snippet"]), "--timeout", timeout_text]),
                "sleep 3.0",
                shell_join(["page-eval", "--body", "--file", str(files["inline_visualizers_js"]), "--timeout", pw_timeout]),
            ],
            screenshot=shell_join(["screenshot-editor", "@last", "{screenshot}", "--padding", "10", "--no-pan", "--timeout", pw_timeout]),
        ),
        UiRegression(
            id="focus-keyboard-contract",
            description="Palette arrows, Escape, and Enter preserve expected active row, close, accept, and editor-focus behavior.",
            commands=[
                "new-box --name FocusKeyboardRegression",
                shell_join(["set", "@last", "code", "println(\"focus keyboard regression\")"]),
                "editor-open @last --property code",
                "sleep 0.5",
                shell_join(["page-eval", "--body", "--file", str(files["focus_keyboard_setup_js"]), "--timeout", pw_timeout]),
                shell_join(["page-eval", "--body", "window.__uiFocusExpectedActiveIndex = 1; return { ok: true, expected: 1 };", "--timeout", pw_timeout]),
                "input-key press ArrowDown --selector .afield-palette-input --wait-ms 150",
                shell_join(["page-eval", "--body", "--file", str(files["focus_keyboard_active_js"]), "--timeout", pw_timeout]),
                shell_join(["page-eval", "--body", "window.__uiFocusExpectedActiveIndex = 0; return { ok: true, expected: 0 };", "--timeout", pw_timeout]),
                "input-key press ArrowUp --selector .afield-palette-input --wait-ms 150",
                shell_join(["page-eval", "--body", "--file", str(files["focus_keyboard_active_js"]), "--timeout", pw_timeout]),
                "input-key press Escape --selector .afield-palette-input --wait-ms 250",
                shell_join(["page-eval", "--body", "--file", str(files["focus_keyboard_escape_assert_js"]), "--timeout", pw_timeout]),
                shell_join(["page-eval", "--body", "--file", str(files["focus_keyboard_enter_setup_js"]), "--timeout", pw_timeout]),
                "input-key press Enter --selector .afield-palette-input --wait-ms 250",
                shell_join(["page-eval", "--body", "--file", str(files["focus_keyboard_enter_assert_js"]), "--timeout", pw_timeout]),
            ],
            screenshot=shell_join(["screenshot-window", "{screenshot}", "--uid", "@last", "--with-editor", "--no-pan", "--timeout", pw_timeout]),
        ),
    ]


def json_stream_loads(text: str, *, test_id: str) -> list[dict[str, Any]]:
    decoder = json.JSONDecoder()
    index = 0
    values: list[dict[str, Any]] = []
    while index < len(text):
        while index < len(text) and text[index].isspace():
            index += 1
        if index >= len(text):
            break
        value, index = decoder.raw_decode(text, index)
        if not isinstance(value, dict):
            raise UiRegressionError(f"{test_id}: backdoor returned non-object JSON")
        values.append(value)
    if not values:
        raise UiRegressionError(f"{test_id}: backdoor returned no JSON")
    return values


def backdoor_command(test: UiRegression, *, port: int, timeout: float, screenshot_path: Path) -> list[str]:
    command = [
        *backdoor_command_prefix(),
        str(BACKDOOR_PATH),
        "--json",
        "--port",
        str(port),
        "--timeout",
        str(timeout),
        "--with-playwright",
    ]
    if test.document:
        command.extend(["--document", test.document])
    for line in test.commands:
        command.extend(["--command", line])
    command.extend(["--command", test.screenshot.format(screenshot=str(screenshot_path))])
    return command


def page_eval_results(results: list[dict[str, Any]]) -> list[dict[str, Any]]:
    assertions: list[dict[str, Any]] = []
    for result in results:
        if str(result.get("command") or "").startswith("page-eval"):
            data = result.get("data")
            if isinstance(data, dict):
                payload = data.get("result")
                if isinstance(payload, dict):
                    assertions.append(payload)
    return assertions


def latest_page_eval_result(results: list[dict[str, Any]]) -> dict[str, Any] | None:
    for result in reversed(results):
        if str(result.get("command") or "").startswith("page-eval"):
            data = result.get("data")
            if isinstance(data, dict):
                payload = data.get("result")
                if isinstance(payload, dict):
                    return payload
    return None


def command_failures(results: list[dict[str, Any]]) -> list[str]:
    failures: list[str] = []
    for result in results:
        if result.get("ok"):
            continue
        command = str(result.get("command") or "<unknown>")
        data = result.get("data")
        message = ""
        if isinstance(data, dict):
            message = str(data.get("error") or data.get("message") or "")
        failures.append(f"{command}: {message or 'command failed'}")
    return failures


def evaluate(test: UiRegression, process: subprocess.CompletedProcess[str], results: list[dict[str, Any]]) -> tuple[bool, list[str], dict[str, Any] | None]:
    failures = command_failures(results)
    if process.returncode != 0 and not failures:
        failures.append(f"backdoor exited with code {process.returncode}")
    assertions = page_eval_results(results)
    assertion = assertions[-1] if assertions else None
    if not assertions:
        failures.append("missing page-eval assertion result")
    for item in assertions:
        if not bool(item.get("ok")):
            reason = item.get("reason")
            failures.append(str(reason or json.dumps(item, sort_keys=True)))
    return not failures, failures, assertion


def sanitize_test_id(test_id: str) -> str:
    return re.sub(r"[^A-Za-z0-9_.-]+", "-", test_id).strip("-") or "ui-regression"


def artifact_dir(test_id: str) -> Path:
    return ARTIFACT_ROOT / sanitize_test_id(test_id)


def timestamp() -> str:
    return time.strftime("%Y%m%d-%H%M%S")


def valid_png(path: Path) -> tuple[bool, str]:
    if not path.exists():
        return False, f"{path} missing"
    size = path.stat().st_size
    if size <= 0:
        return False, f"{path} is empty"
    with path.open("rb") as handle:
        signature = handle.read(8)
    if signature != b"\x89PNG\r\n\x1a\n":
        return False, f"{path} is not a PNG screenshot"
    return True, ""


def supplemental_screenshot_paths(test_id: str, scratch_dir: Path) -> list[Path]:
    prefix = f"{sanitize_test_id(test_id)}-"
    return sorted(path for path in scratch_dir.glob(f"{prefix}*.png") if path.is_file())


def validate_screenshots(paths: list[Path]) -> list[str]:
    failures: list[str] = []
    for path in paths:
        ok, reason = valid_png(path)
        if not ok:
            failures.append(reason)
    return failures


def retain_screenshot(test_id: str, screenshot_path: Path, *, ok: bool, supplemental_paths: list[Path] | None = None) -> dict[str, Any]:
    root = artifact_dir(test_id)
    root.mkdir(parents=True, exist_ok=True)
    retained: dict[str, Any] = {
        "scratch": str(screenshot_path),
        "retained": None,
        "latestPass": None,
        "latestFailure": None,
        "supplemental": [],
    }
    supplemental_paths = supplemental_paths or []
    if not screenshot_path.exists():
        return retained

    if ok:
        pass_dir = root / "passes"
        pass_dir.mkdir(parents=True, exist_ok=True)
        target = pass_dir / f"{timestamp()}.png"
        shutil.copy2(screenshot_path, target)
        latest = root / "latest-pass.png"
        shutil.copy2(screenshot_path, latest)
        retained["retained"] = str(target)
        retained["latestPass"] = str(latest)
        passes = sorted(pass_dir.glob("*.png"), key=lambda p: p.stat().st_mtime, reverse=True)
        for old in passes[PASS_SCREENSHOT_LIMIT:]:
            old.unlink(missing_ok=True)
    else:
        target = root / "latest-failure.png"
        shutil.copy2(screenshot_path, target)
        retained["retained"] = str(target)
        retained["latestFailure"] = str(target)
    prefix = f"{sanitize_test_id(test_id)}-"
    for extra in supplemental_paths:
        if not extra.exists():
            continue
        label = extra.stem
        if label.startswith(prefix):
            label = label[len(prefix):]
        target = root / (f"latest-{label}.png" if ok else f"latest-failure-{label}.png")
        shutil.copy2(extra, target)
        retained["supplemental"].append(str(target))
    return retained


def elapsed_text(seconds: float) -> str:
    if seconds < 60.0:
        return f"{seconds:.0f}s"
    minutes = int(seconds // 60)
    remainder = int(seconds % 60)
    return f"{minutes}m{remainder:02d}s"


def run_backdoor_process(
    command: list[str],
    *,
    telemetry_label: str | None = None,
    heartbeat_interval: float = 15.0,
) -> tuple[subprocess.CompletedProcess[str], int]:
    started = time.monotonic()
    process = subprocess.Popen(
        command,
        cwd=REPO_ROOT,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    next_heartbeat = started + max(1.0, heartbeat_interval)
    while True:
        now = time.monotonic()
        if telemetry_label and now >= next_heartbeat:
            print(f"  {telemetry_label}: still running after {elapsed_text(now - started)}", flush=True)
            next_heartbeat = now + max(1.0, heartbeat_interval)
        try:
            stdout, stderr = process.communicate(timeout=0.25)
            break
        except subprocess.TimeoutExpired:
            continue
    duration_ms = round((time.monotonic() - started) * 1000)
    return subprocess.CompletedProcess(command, process.returncode, stdout, stderr), duration_ms


def run_one(
    test: UiRegression,
    *,
    port: int,
    timeout: float,
    telemetry_label: str | None = None,
    heartbeat_interval: float = 15.0,
) -> dict[str, Any]:
    scratch_dir = BUILD_ROOT / "scratch"
    scratch_dir.mkdir(parents=True, exist_ok=True)
    screenshot_path = scratch_dir / f"{sanitize_test_id(test.id)}.png"
    screenshot_path.unlink(missing_ok=True)
    for old in supplemental_screenshot_paths(test.id, scratch_dir):
        old.unlink(missing_ok=True)

    command = backdoor_command(test, port=port, timeout=timeout, screenshot_path=screenshot_path)
    process, duration_ms = run_backdoor_process(
        command,
        telemetry_label=telemetry_label,
        heartbeat_interval=heartbeat_interval,
    )

    parsed_results: list[dict[str, Any]] = []
    parse_error = None
    try:
        parsed_results = json_stream_loads(process.stdout, test_id=test.id)
    except Exception as exc:
        parse_error = str(exc)

    if parse_error:
        ok = False
        failures = [parse_error]
        assertion = None
    else:
        ok, failures, assertion = evaluate(test, process, parsed_results)

    supplemental_paths = supplemental_screenshot_paths(test.id, scratch_dir)
    screenshot_failures = validate_screenshots([screenshot_path, *supplemental_paths])
    if screenshot_failures:
        ok = False
        failures = [*failures, *screenshot_failures]

    screenshot_artifacts = retain_screenshot(test.id, screenshot_path, ok=ok, supplemental_paths=supplemental_paths)
    return {
        "id": test.id,
        "description": test.description,
        "ok": ok,
        "durationMs": duration_ms,
        "failures": failures,
        "assertion": assertion,
        "command": command,
        "processExitCode": process.returncode,
        "stdout": process.stdout,
        "stderr": process.stderr,
        "backdoorResults": parsed_results,
        "screenshots": screenshot_artifacts,
    }


def retryable_infra_failure(result: dict[str, Any]) -> bool:
    if result.get("ok"):
        return False
    assertion = result.get("assertion")
    backdoor_results = result.get("backdoorResults")
    failures = [str(item) for item in result.get("failures") or []]
    if not backdoor_results:
        return True
    if assertion is None and backdoor_results:
        first = backdoor_results[0]
        command = str(first.get("command") or "")
        if command.startswith(("new-box", "open", "status")) and not first.get("ok"):
            return True
    if any("backdoor returned no JSON" in failure for failure in failures):
        return True
    return False


def run_with_retries(
    test: UiRegression,
    *,
    port: int,
    timeout: float,
    retries: int,
    telemetry: bool = False,
    progress_label: str = "",
    heartbeat_interval: float = 15.0,
) -> dict[str, Any]:
    attempts: list[dict[str, Any]] = []
    for attempt in range(retries + 1):
        label = f"{progress_label}{test.id} attempt {attempt + 1}"
        if telemetry:
            print(f"RUN {label} port={port}", flush=True)
        result = run_one(
            test,
            port=port,
            timeout=timeout,
            telemetry_label=label if telemetry else None,
            heartbeat_interval=heartbeat_interval,
        )
        attempts.append(result)
        if result["ok"] or not retryable_infra_failure(result):
            break
        if telemetry and attempt < retries:
            print(f"RETRY {test.id}: infrastructure failure before assertion", flush=True)
    result = attempts[-1]
    result["attempt"] = len(attempts)
    if len(attempts) > 1:
        result["previousAttempts"] = [
            {
                "ok": item["ok"],
                "durationMs": item["durationMs"],
                "failures": item["failures"],
                "processExitCode": item["processExitCode"],
            }
            for item in attempts[:-1]
        ]
    return result


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--id", action="append", dest="ids", help="Run only the named regression id.")
    parser.add_argument("--port", type=int, default=9490)
    parser.add_argument("--timeout", type=float, default=DEFAULT_TIMEOUT)
    parser.add_argument("--results", default=str(RESULTS_PATH))
    parser.add_argument("--fail-fast", action="store_true")
    parser.add_argument("--retries", type=int, default=1, help="Retry pre-assertion infrastructure failures.")
    parser.add_argument("--heartbeat-interval", type=float, default=15.0, help="Seconds between progress heartbeats while a regression is running.")
    parser.add_argument("--json", action="store_true")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    files = prepare_generated_files()
    selected = tests(files, port=args.port, timeout=args.timeout)
    if args.ids:
        wanted = set(args.ids)
        selected = [test for test in selected if test.id in wanted]
    if not selected:
        raise SystemExit("No UI regressions matched the requested filters.")

    results: list[dict[str, Any]] = []
    passed = 0
    failed = 0
    for index, test in enumerate(selected):
        result = run_with_retries(
            test,
            port=args.port + index,
            timeout=args.timeout,
            retries=max(0, args.retries),
            telemetry=not args.json,
            progress_label=f"[{index + 1}/{len(selected)}] ",
            heartbeat_interval=args.heartbeat_interval,
        )
        results.append(result)
        if result["ok"]:
            passed += 1
        else:
            failed += 1
        if not args.json:
            status = "PASS" if result["ok"] else "FAIL"
            attempt_text = f" attempt {result['attempt']}" if result.get("attempt", 1) > 1 else ""
            print(f"{status} {test.id} ({result['durationMs']} ms){attempt_text}")
            for failure in result["failures"]:
                print(f"  {failure}")
            retained = result.get("screenshots", {}).get("retained")
            if retained:
                print(f"  screenshot: {retained}")
        if failed and args.fail_fast:
            break

    report = {
        "selectedCount": len(selected),
        "passed": passed,
        "failed": failed,
        "passScreenshotLimit": PASS_SCREENSHOT_LIMIT,
        "artifactRoot": str(ARTIFACT_ROOT),
        "results": results,
    }
    results_path = Path(args.results)
    if not results_path.is_absolute():
        results_path = REPO_ROOT / results_path
    results_path.parent.mkdir(parents=True, exist_ok=True)
    results_path.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")

    if args.json:
        print(json.dumps(report, indent=2))
    else:
        print(f"Summary: {passed} passed, {failed} failed")
        print(f"Results: {results_path}")
        print(f"Artifacts: {ARTIFACT_ROOT}")
    return 0 if failed == 0 else 1


if __name__ == "__main__":
    raise SystemExit(main())
