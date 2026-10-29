You are the landing queue's fixer for the Field repository. The lander merged the
branches below onto main and the guard failed. You are in the lander's own worktree, on the exact
merged tree that failed; build output is warm, and nothing else is building here. AGENTS.md in this
directory is the repo guide.

Your job is a judgement call: is this failure a small, mechanical slip that one of these branches
obviously made (a count or number not updated, a citation pointing at something that moved or was
renamed, a KDoc in the wrong place, a missing entry in a list a guard checks, a typo in a reference)?

- If yes: fix it with the smallest edit that is plainly right, then re-run the narrowest command that
  reproduces the failing check (one test class, one checker script, one Gradle task) and confirm it
  passes. Do not run the whole guard; the lander does that afterwards.
- If the failure is about behaviour, timing, flakiness, design, or anything you would have to explain
  with "probably", or it needs more than a few lines, do not edit anything; say why. Your diagnosis
  goes into the bounce and is useful on its own.

Hard rules (the lander rejects a fix that breaks them):
- Do not run git commands that change anything: no commit, stash, checkout, reset, merge, or branch.
  The lander commits your edit itself.
- Do not edit dev_docs/testing/expected_*.json, tools/check_*.py, tools/land.py, tools/merge_regen.py,
  the git hooks, or the Gradle build files; do not delete files; do not disable or skip a test.
- At most 6 files and 60 changed lines.
- The fix must belong to ONE branch below: the one whose change the failure follows from.

End your reply with exactly one line, and nothing after it:
FIXER-VERDICT: {"verdict": "fix" or "decline", "culprit": "<branch name>" or null, "reason": "<one sentence>"}

main before this round: 9740a75a5d864bc232740e49e811daa13e96bec3

Branches in the round, each merged as its own commit:

## docs/language-p3-shared (3b412403e, by Marc)
handoff: Replace getPropertyRaw in kits.md's Rules box fence (correct body)
 dev_docs/handoffs/2026-10-15-add-a-hardware-midi-helper-for-pd-launches.md  | 12 ++++++++++++
 .../handoffs/2026-10-15-add-a-library-diag-for-ukf-covariance-matrices.md   | 14 ++++++++++++++
 dev_docs/handoffs/2026-10-15-add-a-timed-bundle-send-to-oscclient.md        | 12 ++++++++++++
 dev_docs/handoffs/2026-10-15-add-reactive-variants-of-rootprop.md           | 12 ++++++++++++
 ...26-10-15-add-the-two-time-domain-examples-to-the-spectral-examples-st.md | 12 ++++++++++++
 ...26-10-15-align-fieldsuggest-js-category-weights-with-the-server-s-cat.md | 12 ++++++++++++
 ...26-10-15-apply-standardrenderer-post-stack-edits-without-a-manual-re-.md | 14 ++++++++++++++
 .../2026-10-15-applytomesh-work-from-a-main-or-fail-with-a-clear-message.md | 10 ++++++++++
 .../2026-10-15-auto-ranged-colour-map-renders-a-constant-field-as-black.md  | 23 ++++++++++++++++++++++
 ...26-10-15-backfill-model-identity-into-saved-function-conversation-cha.md | 18 ++++++++++++++++++
 ...26-10-15-build-or-ship-the-field-d2-layout-helper-so-petrilayout-tala.md | 10 ++++++++++
 .../2026-10-15-capture-the-reactive-properties-slider-after-a-drag.md       | 12 ++++++++++++
 ...26-10-15-check-the-jep-native-library-version-against-the-shipped-jar.md | 12 ++++++++++++
 ...26-10-15-close-the-rectsl-fn-lowering-gaps-for-mod-and-bounce-easings.md |  8 ++++++++
 .../2026-10-15-colour-stacksl-in-slot-strings-without-a-stacksl-header.md   | 12 ++++++++++++
 ...26-10-15-compare-pattern-colours-by-identity-not-printed-name-in-petr.md | 10 ++++++++++
 .../handoffs/2026-10-15-count-live-csound-events-in-instr-line-notes.md     | 12 ++++++++++++
 .../2026-10-15-decide-the-fate-of-withchat-without-a-chat-backend.md        | 14 ++++++++++++++
 ...026-10-15-derive-sonicpiprogram-cycle-count-from-the-pattern-s-period.md | 14 ++++++++++++++
 .../2026-10-15-diagnose-stack-auto-slot-ordering-and-non-vec4-push-reads.md | 12 ++++++++++++
 dev_docs/handoffs/2026-10-15-diagnose-stacksl-marks-named-x-y-z-or-w.md     | 12 ++++++++++++
 .../handoffs/2026-10-15-docs-runner-must-not-shut-down-the-user-s-field.md  | 12 ++++++++++++
 ...26-10-15-docs-runner-refuse-instead-of-shutting-down-a-field-it-did-n.md | 10 ++++++++++
 ...26-10-15-drop-change-history-from-the-generated-mini-notation-card-pr.md | 25 ++++++++++++++++++++++++
 .../2026-10-15-enforce-non-suspending-profilescope-and-measure-blocks.md    | 12 ++++++++++++
 .../2026-10-15-fingerprint-the-launched-property-in-profiling-captures.md   | 12 ++++++++++++
 ...26-10-15-finish-and-validate-windows-linux-browser-texture-handle-tra.md | 12 ++++++++++++
 ...26-10-15-fix-fan-labelling-in-the-lambda-front-end-so-sharing-heavy-t.md | 12 ++++++++++++
 ...26-10-15-fix-k2-box-codegen-failures-for-local-funs-and-implicit-rece.md | 14 ++++++++++++++
 ...26-10-15-fix-keyframe-module-pointer-targets-to-address-any-uniform-m.md | 12 ++++++++++++
 .../2026-10-15-fix-misleading-under-and-param-rewrite-selector-aliases.md   | 13 +++++++++++++
 dev_docs/handoffs/2026-10-15-fix-textlayout-tofline-garbling-filled-text.md | 12 ++++++++++++
 .../handoffs/2026-10-15-fix-the-self-correcting-kdoc-on-img-asheightsdf.md  |  8 ++++++++
 ...26-10-15-flush-clojure-output-per-form-so-print-is-attributed-to-its-.md | 19 +++++++++++++++++++
 .../handoffs/2026-10-15-forward-facts-from-showmeshgrid-to-threegrid.md     | 12 ++++++++++++
 .../2026-10-15-give-cloth-curvaturestrength-a-default-that-is-visible.md    | 14 ++++++++++++++
 .../2026-10-15-give-computemodulepointer-reload-a-tri-state-result.md       | 12 ++++++++++++
 .../2026-10-15-give-cube-unit-circumradius-like-the-other-platonic-seeds.md | 17 +++++++++++++++++
 .../handoffs/2026-10-15-give-kotlin-morpho-and-nano-boxes-line-notes.md     | 17 +++++++++++++++++
 ...26-10-15-give-mds-a-real-fit-quality-figure-instead-of-a-proportion-t.md | 14 ++++++++++++++
 ...26-10-15-give-showmeshgrid-a-facts-option-so-panels-carry-their-numbe.md | 12 ++++++++++++
 ...26-10-15-give-the-history-lens-a-shortcut-settle-the-history-name-cla.md | 14 ++++++++++++++
 .../2026-10-15-give-the-rectsl-cpu-rasterizer-a-half-open-edge-rule.md      | 12 ++++++++++++
 dev_docs/handoffs/2026-10-15-give-the-two-lathes-one-axis-convention.md     | 13 +++++++++++++
 .../2026-10-15-guard-cloth-index-ranges-against-removals-below-them.md      | 12 ++++++++++++
 .../2026-10-15-have-blendwith-check-both-fields-share-a-training-seed.md    | 12 ++++++++++++
 .../2026-10-15-have-the-docs-runner-rebuild-chart-gallery-captures.md       | 12 ++++++++++++
 ...26-10-15-iframe-bridge-screenshot-should-reveal-an-offscreen-output-a.md | 12 ++++++++++++
 ...2026-10-15-importance-sample-texture-only-emitters-in-the-path-tracer.md | 12 ++++++++++++
 ...26-10-15-include-scene-bridges-like-meshfromdisk-kt-in-the-docs-captu.md | 12 ++++++++++++
 .../2026-10-15-keep-gltf-node-transform-when-a-loop-sets-modelview.md       | 12 ++++++++++++
 .../2026-10-15-keep-the-native-window-drawing-during-a-propup-pull.md       | 12 ++++++++++++
 .../2026-10-15-keep-unsubmitted-klecks-strokes-across-a-box-re-run.md       | 12 ++++++++++++
 ...26-10-15-label-sample-vs-population-conventions-on-the-summary-stats-.md | 12 ++++++++++++
 ...26-10-15-let-a-box-s-named-fun-capture-the-implicit-box-receiver-unde.md | 15 +++++++++++++++
 ...26-10-15-let-a-gpu-buffer-tlas-change-instance-count-or-flags-without.md | 12 ++++++++++++
 ...26-10-15-let-a-propup-reader-compile-without-repeating-the-declaratio.md | 12 ++++++++++++
 .../2026-10-15-let-a-saved-sourceblock-be-called-from-its-own-box.md        | 13 +++++++++++++
 .../2026-10-15-let-corpus-microscope-neighbours-be-placed-on-a-timeline.md  | 17 +++++++++++++++++
 ...26-10-15-let-mesh-follow-the-last-panel-and-allow-pick-on-contact-she.md | 13 +++++++++++++
 ...26-10-15-let-pathtracedreconstruction-adopt-a-pathtracinghere-tracer-.md | 17 +++++++++++++++++
 .../handoffs/2026-10-15-let-showmeshgrid-share-one-scale-across-panels.md   | 12 ++++++++++++
 .../2026-10-15-let-stack-auto-slots-be-set-on-the-mesh-or-say-why-not.md    | 12 ++++++++++++
 ...26-10-15-let-streaming-diffusion-backend-path-change-without-code-edi.md | 12 ++++++++++++
 ...26-10-15-let-the-dlss-adapter-accept-field-s-direct-lighting-motion-c.md | 12 ++++++++++++
 ...26-10-15-make-a-kotlin-code-blended-block-safe-to-call-from-its-own-b.md | 12 ++++++++++++
 ...2026-10-15-make-an-active-venv-win-over-the-interpreter-welcome-saved.md | 12 ++++++++++++
 .../2026-10-15-make-an-unbalanced-stacksl-definition-a-compile-error.md     | 12 ++++++++++++
 .../2026-10-15-make-boidsettings-defaults-flock-without-retuning.md         | 15 +++++++++++++++
 .../2026-10-15-make-c-x-constants-stop-clipping-an-oscillator-s-domain.md   | 12 ++++++++++++
 .../handoffs/2026-10-15-make-cached-work-for-classes-declared-in-the-box.md | 12 ++++++++++++
 ...026-10-15-make-computemesh-unassigned-binding-error-say-what-happened.md | 12 ++++++++++++
 ...26-10-15-make-directlightingdemo-release-its-previous-instance-on-re-.md | 12 ++++++++++++
 ...26-10-15-make-docs-capture-digests-cover-the-stdlib-source-a-fence-ex.md | 12 ++++++++++++
 ...26-10-15-make-doughaudiosink-lossless-at-buffer-sizes-not-a-multiple-.md | 12 ++++++++++++
 dev_docs/handoffs/2026-10-15-make-faust-button-a-momentary-control.md       | 12 ++++++++++++
 ...26-10-15-make-fieldprobe-read-back-throttling-independent-of-display-.md |  9 +++++++++
 .../2026-10-15-make-in-correct-for-a-wrapped-polar-rangeslider-band.md      | 12 ++++++++++++
 ...26-10-15-make-keyframe-apply-symmetric-with-capture-and-clipboard-cop.md | 14 ++++++++++++++
 .../2026-10-15-make-keyframescope-interpolate-keep-a-lane-s-numeric-type.md | 14 ++++++++++++++
 ...026-10-15-make-lambdaterms-normalise-read-back-free-variables-by-name.md | 10 ++++++++++
 ...26-10-15-make-loaded-persistent-property-names-per-document-not-proce.md | 12 ++++++++++++
 ...26-10-15-make-marfspace-user-instances-unable-to-rewrite-bank-materia.md | 12 ++++++++++++
 ...26-10-15-make-meshfromfile-throw-on-unparseable-contents-instead-of-r.md | 12 ++++++++++++
 ...26-10-15-make-negated-provenance-selectors-safe-on-a-bare-polyhedralo.md | 19 +++++++++++++++++++
 ...26-10-15-make-options-profiling-switchable-without-a-renderer-restart.md | 12 ++++++++++++
 .../2026-10-15-make-osc-port-fail-when-another-program-holds-the-port.md    | 12 ++++++++++++
 dev_docs/handoffs/2026-10-15-make-parallel-marching-cubes-deterministic.md  | 13 +++++++++++++
 .../2026-10-15-make-pathtracescene-triangle-tolerate-zero-area-triangles.md | 12 ++++++++++++
 .../handoffs/2026-10-15-make-patrenderstereo-frame-exact-with-patsynth.md   | 14 ++++++++++++++
 ...26-10-15-make-polytope-canonical-diagnostics-catch-negative-volume-an.md | 14 ++++++++++++++
 ...26-10-15-make-rectsl-fitgradient-safe-on-tracks-or-remove-it-from-the.md | 12 ++++++++++++
 ...26-10-15-make-rectsl-fitparameters-defaults-converge-or-say-they-did-.md | 12 ++++++++++++
 ...26-10-15-make-screenshot-box-fail-or-open-outputs-instead-of-returnin.md | 22 +++++++++++++++++++++
 ...26-10-15-make-search-documentation-code-search-docs-or-refuse-unsourc.md | 12 ++++++++++++
 .../2026-10-15-make-setpropertyraw-on-a-propdown-property-cascade.md        | 12 ++++++++++++
 .../2026-10-15-make-showmesh-take-meshview-height-on-the-three-js-path.md   | 12 ++++++++++++
 .../2026-10-15-make-showmesh-topology-fact-survive-flatshaded-meshes.md     | 12 ++++++++++++
 ...26-10-15-make-simdverlet-agree-with-the-renderer-about-which-way-is-u.md | 13 +++++++++++++
 .../2026-10-15-make-simdverlet-step-use-the-published-constraint-set.md     | 12 ++++++++++++
 ...26-10-15-make-simplify-keep-shared-key-times-across-linked-keyframe-l.md | 14 ++++++++++++++
 dev_docs/handoffs/2026-10-15-make-slugbuffer-teardown-order-independent.md  | 18 ++++++++++++++++++
 ...26-10-15-make-solid-levelset-parallel-true-deterministic-or-remove-it.md | 12 ++++++++++++
 ...26-10-15-make-solidfit-deadparameters-catch-parameters-that-never-mov.md | 13 +++++++++++++
 ...26-10-15-make-springangle-return-a-continuous-angle-instead-of-jumpin.md | 15 +++++++++++++++
 ...26-10-15-make-statespace-reduction-stubborn-report-when-it-declines-t.md | 10 ++++++++++
 ...26-10-15-make-string-string-a-compile-error-instead-of-an-empty-paths.md | 18 ++++++++++++++++++
 .../2026-10-15-make-tetgentest-crash-guard-exercise-the-fatal-catch-path.md | 14 ++++++++++++++
 ...26-10-15-make-the-browser-texture-work-under-the-default-kosmickrisp-.md | 10 ++++++++++
 ...2026-10-15-make-the-docs-capture-digest-cover-the-stdlib-a-page-calls.md | 12 ++++++++++++
 ...26-10-15-make-the-keyframe-camera-target-reject-values-it-cannot-appl.md | 12 ++++++++++++
 ...26-10-15-make-the-obvious-interaction-net-termination-check-correct-r.md | 12 ++++++++++++
 dev_docs/handoffs/2026-10-15-make-the-path-tracer-honour-camera-up.md       | 12 ++++++++++++
 .../2026-10-15-make-the-three-js-viewer-honour-threecameraspec-distance.md  | 12 ++++++++++++
 ...26-10-15-make-tosolid-reject-inside-out-meshes-instead-of-documenting.md | 20 +++++++++++++++++++
 ...26-10-15-make-trimesh-copy-normals-null-drop-normals-or-rename-the-se.md | 12 ++++++++++++
 ...26-10-15-make-vec3urquhartgraph-honest-instead-of-silently-returning-.md | 18 ++++++++++++++++++
 ...26-10-15-make-viewport-and-onviewportchange-pane-aware-on-a-split-can.md | 20 +++++++++++++++++++
 ...26-10-15-make-vision-geometry-preview-safe-to-write-captures-into-the.md | 13 +++++++++++++
 ...26-10-15-mangle-stackauto-slot-function-names-so-metal-reserved-names.md | 12 ++++++++++++
 .../2026-10-15-move-gpu-state-backdoor-recipe-into-an-agents-aside.md       | 12 ++++++++++++
 .../2026-10-15-move-neural-particles-smoke-test-section-to-dev-docs.md      | 12 ++++++++++++
 ...26-10-15-move-remotejs-head-test-and-vendoring-notes-into-agents-asid.md |  8 ++++++++
 ...26-10-15-move-the-keybindings-table-s-regenerate-note-into-an-agents-.md | 12 ++++++++++++
 .../2026-10-15-native-window-capture-name-the-stage-that-timed-out.md       | 10 ++++++++++
 .../2026-10-15-normalise-stacksl-fbm-and-flag-2d-fbm-on-3d-input.md         | 10 ++++++++++
 .../2026-10-15-order-syphon-followongpu-after-its-target-automatically.md   |  9 +++++++++
 .../2026-10-15-orient-the-imgui-vr-panel-so-it-is-not-mirrored-from-z.md    | 13 +++++++++++++
 ...26-10-15-petri-tala-layout-cache-should-notice-a-newly-built-d2-helpe.md | 14 ++++++++++++++
 .../2026-10-15-prune-branchstate-snapshots-that-no-branch-can-reach.md      | 12 ++++++++++++
 .../2026-10-15-prune-or-bound-ai4-studio-canvas-branch-state-blobs.md       | 14 ++++++++++++++
 ...26-10-15-re-record-stacksl-conversation-turns-that-predate-stored-val.md | 21 ++++++++++++++++++++
 ...26-10-15-re-run-dependent-passes-when-a-renderer-or-texture-is-rebuil.md | 16 ++++++++++++++++
 ...26-10-15-rebind-child-textures-when-an-optical-flow-parent-relaunches.md | 12 ++++++++++++
 ...26-10-15-recapture-forth-output-channels-and-drop-the-staleness-note-.md |  8 ++++++++
 .../2026-10-15-reclaim-or-reap-an-abandoned-sonic-pi-daemon-at-boot.md      | 13 +++++++++++++
 ...26-10-15-regenerate-function-conversation-downloads-in-current-list-l.md | 19 +++++++++++++++++++
 ...26-10-15-register-a-study-s-source-property-as-persistent-automatical.md | 16 ++++++++++++++++
 ...26-10-15-reject-capture-native-window-with-video-instead-of-recording.md | 10 ++++++++++
 ...2026-10-15-reject-or-derive-neighbour-grid-cell-size-from-boid-radius.md | 14 ++++++++++++++
 .../2026-10-15-reject-or-fix-momentum-plus-rho-in-flowmatchoptions.md       | 12 ++++++++++++
 .../2026-10-15-release-osc-handlers-when-their-box-stops-or-re-runs.md      | 14 ++++++++++++++
 ...26-10-15-remove-getpropertyraw-and-manual-close-from-opendlssnr-study.md | 16 ++++++++++++++++
 .../2026-10-15-remove-patspatial-awaitready-and-openxr-pose-timer-traps.md  | 14 ++++++++++++++
 ...26-10-15-remove-stale-remote-js-sidecar-mapping-and-bound-metadata-ba.md | 14 ++++++++++++++
 .../handoffs/2026-10-15-remove-the-unused-endedexception-from-phased-kt.md  | 15 +++++++++++++++
 ...26-10-15-rename-code-use-telemetry-in-ui-strings-to-local-code-use-hi.md | 12 ++++++++++++
 dev_docs/handoffs/2026-10-15-rename-hardware-only-opticalflow-class.md      | 12 ++++++++++++
 .../2026-10-15-replace-getpropertyraw-in-kits-md-s-rules-box-fence.md       | 18 ++++++++++++++++++
 .../2026-10-15-report-inactive-tensorrt-in-streaming-diffusion-status.md    | 12 ++++++++++++
 .../2026-10-15-report-patf0frame-time-at-the-samples-yin-compared.md        | 14 ++++++++++++++
 ...26-10-15-report-python-code-typed-into-a-python-root-instead-of-compi.md | 12 ++++++++++++
 ...026-10-15-report-simdverlet-divergence-instead-of-going-silent-on-nan.md | 12 ++++++++++++
 .../2026-10-15-report-the-ending-phase-once-for-a-b-frame-programs.md       | 16 ++++++++++++++++
 .../2026-10-15-reset-sample-count-synchronously-in-reloadintegrator.md      | 12 ++++++++++++
 .../2026-10-15-resolve-slideshow-templates-after-the-declaring-fragment.md  | 12 ++++++++++++
 dev_docs/handoffs/2026-10-15-return-tool-images-over-the-mcp-bridge.md      | 12 ++++++++++++
 .../2026-10-15-run-a-kit-starting-point-s-roles-when-it-is-opened.md        | 18 ++++++++++++++++++
 ...26-10-15-run-field-tests-py-reject-or-ignore-timeout-instead-of-warni.md | 10 ++++++++++
 .../2026-10-15-save-patchy-s-own-save-save-as-into-the-field-document.md    | 12 ++++++++++++
 ...26-10-15-schedule-dlss-source-with-evaluate-refuse-double-pretargets-.md | 12 ++++++++++++
 ...26-10-15-screenshot-box-wait-for-depth-of-field-to-settle-on-the-sele.md | 10 ++++++++++
 .../handoffs/2026-10-15-separate-osc-default-port-from-the-osc-editor-s.md  | 14 ++++++++++++++
 ...26-10-15-set-java-awt-headless-automatically-under-xstartonfirstthrea.md | 12 ++++++++++++
 ...26-10-15-show-code-given-notation-colours-as-given-under-excalidraw-s.md | 13 +++++++++++++
 .../2026-10-15-silence-smile-t-sne-info-logging-during-smiletsne-fits.md    | 12 ++++++++++++
 .../2026-10-15-stdoutlog-render-lblock-indent-as-depth-not-raw-uuids.md     | 10 ++++++++++
 ...26-10-15-stop-a-window-filling-output-panel-from-trapping-canvas-pan-.md | 18 ++++++++++++++++++
 ...26-10-15-stop-animateevolution-s-recorder-falling-behind-under-a-geom.md | 19 +++++++++++++++++++
 ...26-10-15-stop-asdensityfield-smoothing-a-sparse-automaton-to-an-empty.md | 13 +++++++++++++
 ...26-10-15-stop-filewatchmanager-silently-dropping-external-metadata-ed.md | 14 ++++++++++++++
 ...26-10-15-stop-keyframe-saves-silently-dropping-booleans-lists-and-nul.md | 14 ++++++++++++++
 ...26-10-15-stop-modulehere-silently-using-default-shaders-before-once-v.md | 14 ++++++++++++++
 ...26-10-15-stop-renderer-children-automatically-when-the-renderer-stops.md | 14 ++++++++++++++
 .../2026-10-15-stop-screenshot-prep-flooring-iframe-panels-at-220px.md      | 12 ++++++++++++
 ...26-10-15-stop-sonifying-event-kinds-closed-lambda-terms-can-never-emi.md | 12 ++++++++++++
 .../2026-10-15-stop-split-normal-trimesh-from-reporting-a-wrong-genus.md    | 12 ++++++++++++
 .../2026-10-15-stop-tetgen-dumping-skipped-facet-files-into-the-cwd.md      | 12 ++++++++++++
 ...2026-10-15-stop-the-docs-runner-expecting-svg-from-lines-inside-three.md | 12 ++++++++++++
 .../2026-10-15-stop-the-docs-runner-stripping-a-bare-inside-code.md         | 12 ++++++++++++
 .../2026-10-15-stop-uniform-block-classes-shadowing-imported-types.md       | 12 ++++++++++++
 ...026-10-15-stop-user-words-from-silently-shadowing-forthfield-builtins.md | 12 ++++++++++++
 dev_docs/handoffs/2026-10-15-support-scale-log-on-faust-sliders.md          | 12 ++++++++++++
 .../2026-10-15-support-the-gltf-extensions-the-toycar-showcase-uses.md      | 12 ++++++++++++
 ...26-10-15-surface-personal-templates-shadowed-by-a-bundled-template-of.md | 12 ++++++++++++
 ...26-10-15-sync-standardrenderer-lights-and-transparent-sort-automatica.md | 12 ++++++++++++
 ...26-10-15-type-fieldml-callback-parameters-in-named-records-and-arity-.md | 21 ++++++++++++++++++++
 ...26-10-15-unify-log-channel-matching-across-mute-pin-and-logstream-fil.md | 10 ++++++++++
 .../2026-10-15-unify-or-rename-the-two-shader-include-mechanisms.md         | 12 ++++++++++++
 ...26-10-15-unify-the-two-isfillwidthiframe-tests-and-cover-lazy-value-v.md | 14 ++++++++++++++
 .../2026-10-15-verify-faust-on-mobile-controllers-linux-and-windows.md      | 14 ++++++++++++++
 .../2026-10-15-verify-setup-in-launch-receipts-for-non-kotlin-macros.md     | 12 ++++++++++++
 .../handoffs/2026-10-15-warn-when-debugprintfext-runs-without-validation.md | 12 ++++++++++++
 ...026-10-15-warn-when-tetgen-targetedgelength-is-coarser-than-the-input.md | 12 ++++++++++++
 public/docs/afield_four_graphics.md                                         |  6 ++++--
 public/docs/aiFeatures.md                                                   |  5 +++--
 public/docs/animation_time_model.md                                         | 11 +++++++----
 public/docs/audio_ai_muscriptor.md                                          |  6 ++++--
 public/docs/autodiff_examples.md                                            |  2 +-
 public/docs/building_and_testing.md                                         |  2 +-
 public/docs/capture_limits.md                                               | 38 ++++++++++++++++++-------------------
 public/docs/chain_drawn_morph.md                                            |  2 +-
 public/docs/chart.md                                                        |  2 +-
 public/docs/clojure.md                                                      |  2 --
 public/docs/completion_ranking.md                                           |  2 +-
 public/docs/csound.md                                                       |  2 +-
 public/docs/dataframe.md                                                    |  2 +-
 public/docs/docs_notes.md                                                   |  2 +-
 public/docs/dynamic_graph_systems_examples.md                               |  2 +-
 public/docs/faust.md                                                        | 11 +++++++----
 public/docs/fieldml_host_bridge.md                                          |  2 +-
 public/docs/fn_intervals.md                                                 |  2 +-
 public/docs/four_in_output_areas.md                                         |  4 ++--
 public/docs/geometry_from_disk.md                                           | 11 ++++++-----
 public/docs/gpu_state.md                                                    |  2 +-
 public/docs/graph_query.md                                                  |  4 ++--
 public/docs/how_field_is_built.md                                           |  2 +-
 public/docs/imaging_examples.md                                             |  2 +-
 public/docs/installation.md                                                 |  2 +-
 public/docs/keyframe_autosweep.md                                           |  7 ++++---
 public/docs/keyframer_examples.md                                           |  6 +++---
 public/docs/kotlin_in_the_head.md                                           |  7 +++++--
 public/docs/logging.md                                                      |  2 +-
 public/docs/mesh_examples.md                                                |  4 ++--
 public/docs/morpho_examples.md                                              |  2 +-
 public/docs/morpho_search.md                                                |  2 +-
 public/docs/neural_particles.md                                             | 14 +++++++-------
 public/docs/onnx.md                                                         |  5 +++--
 public/docs/open_dlss_nr.md                                                 |  2 +-
 public/docs/output_panel_sizing.md                                          |  4 ++--
 public/docs/paint_sweeps.md                                                 |  4 ++--
 public/docs/particle_video.md                                               |  4 ++--
 public/docs/pat_spatial.md                                                  |  4 ++--
 public/docs/profiling_timeline.md                                           |  2 +-
 public/docs/pure_data.md                                                    |  2 +-
 public/docs/python.md                                                       |  2 +-
 public/docs/ray_tracing_examples.md                                         |  2 +-
 public/docs/reactive_properties.md                                          | 16 ++++++++--------
 public/docs/rectsl_cheatsheet.md                                            |  2 +-
 public/docs/rectsl_examples.md                                              |  4 ++--
 public/docs/sound.md                                                        |  6 +++---
 public/docs/sound_paths.md                                                  |  2 +-
 public/docs/spatial_geometry_examples.md                                    |  7 ++++---
 public/docs/stackauto_conversation.md                                       |  7 ++++---
 public/docs/stacksl_cheatsheet.md                                           |  2 +-
 public/docs/stacksl_conversation.md                                         |  2 +-
 public/docs/stacksl_evolve.md                                               |  3 ++-
 public/docs/stacksl_intervals.md                                            |  2 +-
 public/docs/statistics_examples.md                                          |  9 ++++++---
 public/docs/streaming_diffusion.md                                          |  2 +-
 public/docs/studies.md                                                      |  2 +-
 public/docs/three_output.md                                                 | 31 +++++++++++++++---------------
 public/docs/vision.md                                                       |  2 +-
 public/docs/vision_geometry.md                                              |  2 +-
 public/docs/visualizers.md                                                  |  2 +-
 public/docs/wfc_examples.md                                                 | 18 ++++++++++--------
 256 files changed, 2706 insertions(+), 150 deletions(-)

Failing tests (first): ImpossibilityClaimContractTest.everyImpossibilityClaimInDevDocsCarriesItsEvidence, ImpossibilityClaimContractTest.plantingUnmarkedClaimsBesideTheRealCorpusIsDetected
## ImpossibilityClaimContractTest.everyImpossibilityClaimInDevDocsCarriesItsEvidence
java.lang.IllegalStateException: 1 impossibility claim(s) in dev_docs carry no evidence:

  dev_docs/handoffs/2026-10-15-make-the-keyframe-camera-target-reject-values-it-cannot-appl.md:1 [VERDICT via \bcannot\b]
    # Make the keyframe camera target reject values it cannot apply
    -> no reproducer, citation, conjecture marker or explicit waiver in the claim unit (lines 1-6)
      Add ONE of these, inside the claim's own paragraph/bullet/table row:
        (reproducer: `SomeTest.kt#fun theRefusalIsObserved`)   a @Test or runnable example that demonstrates it
        `SomeFile.kt:120#theSymbol`                            the code that makes it so
        (conjecture, 2026-10-15)                             reasoned, not measured -- and honest about it
        (not a limitation: <why>)                              the detector misfired; say why
      See dev_docs/impossibility_claims.md.

## ImpossibilityClaimContractTest.plantingUnmarkedClaimsBesideTheRealCorpusIsDetected
java.lang.AssertionError: the baseline half of the counterfactual must be clean, or the comparison proves nothing expected:<0> but was:<1>



The end of the landing log:
}
    .end {
        // .end { } runs on cancellation — which end() has just caused. Two frames of it, so
        // the red state is definitely painted before the job goes away.
        repeat(2) {
            draw(AnimationClock.nowSeconds() - started, true)
            frame()
        }
    }
[90m[3m[info,kotlin,compile][0m compile=776ms
[90m[3m[info,kotlin,evaluate][0m evaluate=32.0ms
[90m[3m[info,operation-transcript][0m macro launch name=frames_lifecycle launchIndex=3/3 op=7/8 box=frames_lifecycle_rerun property=code strand=default
[90m[3m[debug,kotlin][0m >|> rewriting AST analysis - 'b63d9a25-75c9-42cf-aa65-dc29162ae44a'
[90m[3m[debug,kotlin][0m b63d9a25-75c9-42cf-aa65-dc29162ae44a	child (named): REFERENCE_EXPRESSION nanoTime class org.jetbrains.kotlin.psi.KtNameReferenceExpression 353 -> 361 null
[90m[3m[debug,kotlin][0m b63d9a25-75c9-42cf-aa65-dc29162ae44a	child (named): REFERENCE_EXPRESSION toInt class org.jetbrains.kotlin.psi.KtNameReferenceExpression 386 -> 391 null
[90m[3m[debug,kotlin][0m b63d9a25-75c9-42cf-aa65-dc29162ae44a	child (named): REFERENCE_EXPRESSION nowSeconds class org.jetbrains.kotlin.psi.KtNameReferenceExpression 456 -> 466 null
[90m[3m[debug,kotlin][0m b63d9a25-75c9-42cf-aa65-dc29162ae44a	child (named): REFERENCE_EXPRESSION format class org.jetbrains.kotlin.psi.KtNameReferenceExpression 675 -> 681 null
[90m[3m[debug,kotlin][0m b63d9a25-75c9-42cf-aa65-dc29162ae44a	child (named): REFERENCE_EXPRESSION format class org.jetbrains.kotlin.psi.KtNameReferenceExpression 893 -> 899 null
[90m[3m[debug,kotlin][0m b63d9a25-75c9-42cf-aa65-dc29162ae44a	child (named): REFERENCE_EXPRESSION format class org.jetbrains.kotlin.psi.KtNameReferenceExpression 937 -> 943 null
[90m[3m[debug,kotlin][0m b63d9a25-75c9-42cf-aa65-dc29162ae44a	child (named): REFERENCE_EXPRESSION showRow class org.jetbrains.kotlin.psi.KtNameReferenceExpression 716 -> 723 null
[90m[3m[debug,kotlin][0m b63d9a25-75c9-42cf-aa65-dc29162ae44a	child (named): REFERENCE_EXPRESSION nowSeconds class org.jetbrains.kotlin.psi.KtNameReferenceExpression 1079 -> 1089 null
[90m[3m[debug,kotlin][0m b63d9a25-75c9-42cf-aa65-dc29162ae44a	child (named): REFERENCE_EXPRESSION show class org.jetbrains.kotlin.psi.KtNameReferenceExpression 1141 -> 1145 null
[90m[3m[debug,kotlin][0m b63d9a25-75c9-42cf-aa65-dc29162ae44a	child (named): REFERENCE_EXPRESSION animate class org.jetbrains.kotlin.psi.KtNameReferenceExpression 1036 -> 1043 null
[90m[3m[debug,kotlin][0m b63d9a25-75c9-42cf-aa65-dc29162ae44a	final string is:
/*
Run twice without stopping: the counter restarts, the colour changes, and one loop
remains. The old run cancels at its next frame(), after its suspended body resumes.
Compare frameNumber with elapsed time: the counter measures work, not seconds.
A time-derived hue makes the new launch visible even with a fresh script context.
*/
val hue = ((System.nanoTime() / 1_000_000L) % 360L).toInt()
val swatch = "hsl($hue 70% 60%)"
val started = AnimationClock.nowSeconds()
var nextDraw = 0.0

// Keep this launch's swatch beside its frame count and elapsed-time readout.
val show: (Int, Double) -> Unit = { n, elapsed ->
    val rate = if (elapsed > 0.5) "%,.0f frames a second".format(n / elapsed) else "measuring"
    showRow(
        "main",
        "<span style='display:inline-block;width:58px;height:58px;border-radius:6px;background:$swatch'></span>",
        """
        ### frame ${"%,d".format(n)}

        {muted:$rate · ${"%.1f".format(elapsed)} s elapsed}

        {muted:this launch's colour is $swatch}
        """,
    )
}

animate {
    val elapsed = AnimationClock.nowSeconds() - started
    if (elapsed >= nextDraw) {
        show(frameNumber, elapsed)
        nextDraw = elapsed + 0.1
    }
}
[90m[3m[debug,kotlin][0m <|< - 'b63d9a25-75c9-42cf-aa65-dc29162ae44a'
[90m[3m[info,attic][0m checkAtticAndExecute for '3 · re-run replaces, it does not stack' (frames_lifecycle_rerun): empty=true, same=false, currentLen=0, wasLen=-1
[90m[3m[info,attic][0m attic is empty for '3 · re-run replaces, it does not stack' — skipping
[90m[3m[info,kotlin,perf][0m 		 compile request engine=KotlinInterface2 filePrefix=frames_lifecycle_rerun source=frames_lifecycle_rerun__code_$1 kind=user-code length=1208 preview=/* | Run twice without stopping: the counter restarts, the colour changes, and one loop | remains. The old run cancels a...
[90m[3m[info,kotlin,perf][0m 		 compile code /*
Run twice without stopping: the counter restarts, the colour changes, and one loop
remains. The old run cancels at its next frame(), after its suspended body resumes.
Compare frameNumber with elapsed time: the counter measures work, not seconds.
A time-derived hue makes the new launch visible even with a fresh script context.
*/
val hue = ((System.nanoTime() / 1_000_000L) % 360L).toInt()
val swatch = "hsl($hue 70% 60%)"
val started = AnimationClock.nowSeconds()
var nextDraw = 0.0

// Keep this launch's swatch beside its frame count and elapsed-time readout.
val show: (Int, Double) -> Unit = { n, elapsed ->
    val rate = if (elapsed > 0.5) "%,.0f frames a second".format(n / elapsed) else "measuring"
    showRow(
        "main",
        "<span style='display:inline-block;width:58px;height:58px;border-radius:6px;background:$swatch'></span>",
        """
        ### frame ${"%,d".format(n)}

        {muted:$rate · ${"%.1f".format(elapsed)} s elapsed}

        {muted:this launch's colour is $swatch}
        """,
    )
}

animate {
    val elapsed = AnimationClock.nowSeconds() - started
    if (elapsed >= nextDraw) {
        show(frameNumber, elapsed)
        nextDraw = elapsed + 0.1
    }
}
[90m[3m[info,kotlin,compile][0m compile=442ms
[90m[3m[info,kotlin,evaluate][0m evaluate=13.1ms
[execute] PASS frames_lifecycle (applied=8, output lines=3)
BoxCodeCompileGuard ok — 2 transcript(s) compiled

> Task :kotlinKotlinNpmCachesSetup
> Task :kotlinNodeJsSetup UP-TO-DATE
> Task :kotlinRestoreYarnLock UP-TO-DATE
> Task :kotlinYarnSetup UP-TO-DATE
> Task :jsPackageJson UP-TO-DATE
> Task :jsTestPackageJson UP-TO-DATE
> Task :jsPublicPackageJson UP-TO-DATE
> Task :jsTestPublicPackageJson UP-TO-DATE
> Task :kotlinPackageJsonUmbrella UP-TO-DATE
> Task :rootPackageJson UP-TO-DATE
> Task :kotlinNpmInstall UP-TO-DATE
> Task :compileKotlinJs UP-TO-DATE
> Task :jsProcessResources UP-TO-DATE
> Task :jsMainClasses UP-TO-DATE
> Task :compileDevelopmentExecutableKotlinJs UP-TO-DATE
> Task :jsDevelopmentExecutableCompileSync UP-TO-DATE
> Task :kotlinStoreYarnLock UP-TO-DATE
> Task :jsBrowserDevelopmentWebpack UP-TO-DATE
> Task :jvmJar UP-TO-DATE
> Task :compileTestKotlinJvm UP-TO-DATE
> Task :compileJvmTestJava NO-SOURCE
> Task :jvmTestProcessResources UP-TO-DATE
> Task :processJvmTestResources SKIPPED
> Task :jvmTestClasses UP-TO-DATE

> Task :recordTestTreeBaseline
warning: `VIRTUAL_ENV=/Users/marc/field4/.venv` does not match the project environment path `.venv` and will be ignored; use `--active` to target the active environment instead
tree-write guard: baseline of 10934 tracked file(s), 0 already dirty -> /Users/marc/field4-worktrees/lander/build/test-tree-writes/baseline.json

> Task :citationGates
WARNING: Using incubator modules: jdk.incubator.vector
citationGates: running ServerSurfaceLedgerTest
citationGates: [1/14] ServerSurfaceLedgerTest SUCCESS in 5.3s
citationGates: running CloseNodeCitationDriftTest
citationGates: [2/14] CloseNodeCitationDriftTest SUCCESS in 3.6s
citationGates: running CitationContractTest
citationGates: [3/14] CitationContractTest SUCCESS in 6.1s
citationGates: running DerivedArithmeticContractTest
citationGates: [4/14] DerivedArithmeticContractTest SUCCESS in 9.2s
citationGates: running DocsFenceTreatmentTest
citationGates: [5/14] DocsFenceTreatmentTest SUCCESS in 0.4s
citationGates: running DocsGatesWiringTest
citationGates: [6/14] DocsGatesWiringTest SUCCESS in 0.1s
citationGates: running DocumentedApiEvaluationTest
citationGates: [7/14] DocumentedApiEvaluationTest SUCCESS in 12.4s
citationGates: running GateInputDeclarationTest
citationGates: [8/14] GateInputDeclarationTest SUCCESS in 0.1s
citationGates: running ImpossibilityClaimContractTest

ImpossibilityClaimContractTest > everyImpossibilityClaimInDevDocsCarriesItsEvidence FAILED
    java.lang.IllegalStateException at ImpossibilityClaimContractTest.kt:175

ImpossibilityClaimContractTest > plantingUnmarkedClaimsBesideTheRealCorpusIsDetected FAILED
    java.lang.AssertionError at ImpossibilityClaimContractTest.kt:516
citationGates: [9/14] ImpossibilityClaimContractTest FAILURE in 8.4s
citationGates: running LanguagesCategoryTest
citationGates: [10/14] LanguagesCategoryTest SUCCESS in 2.6s
citationGates: running PersonalBoundaryTest
citationGates: [11/14] PersonalBoundaryTest SUCCESS in 11.1s
citationGates: running ProseNumeralContractTest
citationGates: [12/14] ProseNumeralContractTest SUCCESS in 16.9s
citationGates: running SmallLanguageAnatomyTest
citationGates: [13/14] SmallLanguageAnatomyTest SUCCESS in 1.2s
citationGates: running TranscriptStackAutoCompileTest
citationGates: [14/14] TranscriptStackAutoCompileTest SUCCESS in 9.8s

113 tests completed, 2 failed

> Task :citationGates FAILED

> Task :checkTestTreeWrites
warning: `VIRTUAL_ENV=/Users/marc/field4/.venv` does not match the project environment path `.venv` and will be ignored; use `--active` to target the active environment instead
tree-write guard: citationGates left the working tree as it found it.

FAILURE: Build failed with an exception.

* What went wrong:
Execution failed for task ':citationGates'.
> There were failing tests. See the report at: file:///Users/marc/field4-worktrees/lander/build/reports/tests/citationGates/index.html

* Try:
> Run with --scan to get full insights from a Build Scan (powered by Develocity).

BUILD FAILED in 13m 14s
36 actionable tasks: 13 executed, 23 up-to-date

  A generated artifact is stale after this merge.
  It was not your commit that broke it and no hook ran on the clone that did:
  .git/hooks is per-clone and Git never transports it.

  Reproduce:  ./gradlew checkKDocAttachment checkAgentTranscriptIndex checkAgentTranscriptLayouts checkAgentDemos checkPythonTools checkBoxIdioms checkBoxCodeCompiles citationGates
  The failing task names the artifact and the command that regenerates it.
  Regenerate and commit that, or `main` is red until someone else finds it.

  Set FIELD_SKIP_MERGE_GUARD=1 to skip this check.

merge_regen: a guard failed on the batch

land: 2026-10-15T18:12:46+02:00 red; asking the fixer (up to 15 min) about codex/box-code-revision-batch-2, claude/reference-loc-hero, fix/demo-build-scripts, docs/language-p3-shared

What the fixer did on recent reds (for calibration):
- fixed: GesturePaintReplayTest.kt put @Test and the fun on one line in two tests, dropping their KDoc; splitting the annotation onto its own line makes checkKDocAttachment pass.
- fixed: The new fieldAgent function was inserted between terminalExecutable and its KDoc, which stacked two KDoc blocks; moving that KDoc back above terminalExecutable makes checkKDocAttachment pass.
- fixed: terminal.kt put @Volatile and the var on one line, which made the KDoc guard drop the block above it; moving the annotation to its own line makes checkKDocAttachment pass.
- fixed: The branch put @Synchronized on the same line as fun setParameter, added smokeSolidCraft without declaring it manual, and its transcript now runs setParameter, which made a COMPILE_ONLY row stale; all three are mechanical one-line fixes and the checks now pass.
- declined: The rename broke three Python checks: the -Dafield.rectsl.headingfigure property was renamed to afield.lang.rectsl.headingfigure in tools/check_test_tree_writes.py (which I may not edit) but not in RectSLHeadingFigureTest.kt, and the function and stackauto conversation pages need their packagers re-
- declined: The branch's rename touched function_conversation.md and stackauto_conversation.md, so checkBoxIdioms now checks their recorded-conversation fences, which use listOf; fixing that means updating the recorded conversation sources and re-running the packagers, not editing the pages by hand.
- declined: no verdict line
- declined: no verdict line

This replay's checkout cannot build: the `gradle` command is not available, so verify by reading the check's source and rerunning any `tools/` Python check that covers it.
