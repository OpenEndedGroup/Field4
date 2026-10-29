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

main before this round: 1eff3d30d24e9974b1b521348b66c819fe9d317f

Branches in the round, each merged as its own commit:

## docs/kdoc-symbol-links-2 (437fc2239, by Marc)
Lander fixup: The KDoc rewrite of DataFramePluginSpike.kt removed three citation anchors used by dev_docs/DATAFRAME_PLUGIN.md, so I pointed them at the matching lines of the new KDoc and CitationContractTest passes.
 dev_docs/DATAFRAME_PLUGIN.md                                                |     6 +-
 dev_docs/KDOC_SYMBOL_LINKS_PLAN.md                                          |   110 +
 dev_docs/kdoc_worklist.md                                                   |   307 +-
 public/docs/assets/function_conversation/FunctionStudyTools.kt              |    13 +-
 public/docs/reference/alife.md                                              |  1572 ++--
 public/docs/reference/audio-ai.md                                           |  1068 +--
 public/docs/reference/audio.md                                              |  4392 +++++-----
 public/docs/reference/data.md                                               |  1992 ++---
 public/docs/reference/examples.md                                           |   994 +--
 public/docs/reference/execution.md                                          |    26 +-
 public/docs/reference/function.md                                           |  1238 +--
 public/docs/reference/graphics.md                                           | 15483 +++++++++++++++++++---------------
 public/docs/reference/imaging.md                                            |  1650 ++--
 public/docs/reference/index.md                                              |  1230 +--
 public/docs/reference/internal.md                                           |   395 +-
 public/docs/reference/languages.md                                          |  5725 +++++++------
 public/docs/reference/lighting.md                                           |   387 +-
 public/docs/reference/machine-learning.md                                   |  5318 +++++++-----
 public/docs/reference/math.md                                               |  3580 ++++----
 public/docs/reference/networking.md                                         |   849 +-
 public/docs/reference/patterns.md                                           |  2569 +++---
 public/docs/reference/polytopes.md                                          |  1048 +--
 public/docs/reference/renderers.md                                          |  1370 +--
 public/docs/reference/search.md                                             |   718 +-
 public/docs/reference/simulation.md                                         |  1949 +++--
 public/docs/reference/solvers.md                                            |  1367 +--
 public/docs/reference/synthesis.md                                          |  2826 ++++---
 public/docs/reference/video.md                                              |   313 +-
 public/docs/reference/visualization.md                                      |  3067 +++----
 public/docs/reference/workspace.md                                          |  7435 +++++++++-------
 src/jvmMain/kotlin/afield/ai/agent/AgentLessons.kt                          |     4 +
 src/jvmMain/kotlin/afield/ai/agent/AgentTask.kt                             |     8 +
 src/jvmMain/kotlin/afield/ai/agent/AgentTaskTranscript.kt                   |     4 +
 src/jvmMain/kotlin/afield/ai/agent/BoxOutputCapture.kt                      |     7 +
 src/jvmMain/kotlin/afield/ai/agent/FieldGraphTools.kt                       |    26 +-
 src/jvmMain/kotlin/afield/ai/agent/GeometryReviewTools.kt                   |     8 +
 src/jvmMain/kotlin/afield/ai/agent/InFieldAgentEvals.kt                     |     4 +-
 src/jvmMain/kotlin/afield/ai/agent/KitTools.kt                              |     7 +
 src/jvmMain/kotlin/afield/ai/agent/StudyBuildAwaits.kt                      |     6 +
 src/jvmMain/kotlin/afield/ai/agent/StudyBuildTools.kt                       |     8 +
 src/jvmMain/kotlin/afield/ai/compat/AI4.kt                                  |    15 +
 src/jvmMain/kotlin/afield/ai/completion/Ai4CompletionDoc.kt                 |    14 +
 src/jvmMain/kotlin/afield/ai/completion/Ai4GlslAutocomplete.kt              |    13 +
 src/jvmMain/kotlin/afield/ai/completion/Ai4KotlinAutocomplete.kt            |    11 +
 src/jvmMain/kotlin/afield/ai/conversation/AiBoxConversation.kt              |     8 +
 src/jvmMain/kotlin/afield/ai/conversation/AiSessionTree.kt                  |     9 +
 src/jvmMain/kotlin/afield/ai/conversation/AiStatePruning.kt                 |     7 +
 src/jvmMain/kotlin/afield/ai/conversation/AiTurnInbox.kt                    |     7 +
 src/jvmMain/kotlin/afield/ai/conversation/AiWorldUndo.kt                    |     8 +-
 src/jvmMain/kotlin/afield/ai/conversation/ContextPipeline.kt                |    11 +
 src/jvmMain/kotlin/afield/ai/conversation/IdeChatHelpers.kt                 |    10 +
 src/jvmMain/kotlin/afield/ai/conversation/Instructions.kt                   |    10 +
 src/jvmMain/kotlin/afield/ai/conversation/TurnSuspension.kt                 |     8 +
 src/jvmMain/kotlin/afield/ai/conversation/chatlog/ChatArchive.kt            |     4 +-
 src/jvmMain/kotlin/afield/ai/conversation/chatlog/ChatArchiveCli.kt         |     8 +
 src/jvmMain/kotlin/afield/ai/conversation/chatlog/ChatLensViewer.kt         |     5 +-
 src/jvmMain/kotlin/afield/ai/conversation/chatlog/ChatLogViews.kt           |     4 +-
 src/jvmMain/kotlin/afield/ai/conversation/chatlog/ChatlogTools.kt           |     6 +-
 src/jvmMain/kotlin/afield/ai/diagnostics/Logging.kt                         |    25 +
 src/jvmMain/kotlin/afield/ai/inference/onnx/Clip.kt                         |    18 +-
 src/jvmMain/kotlin/afield/ai/inference/onnx/DepthAnything.kt                |    27 +-
 src/jvmMain/kotlin/afield/ai/inference/onnx/DepthAnythingWorker.kt          |    16 +-
 src/jvmMain/kotlin/afield/ai/inference/onnx/Lpips.kt                        |    13 +-
 src/jvmMain/kotlin/afield/ai/inference/onnx/OnnxRuntime.kt                  |    29 +-
 src/jvmMain/kotlin/afield/ai/knowledge/AI4FilestoreCli.kt                   |    11 +
 src/jvmMain/kotlin/afield/ai/knowledge/AI4_filestore.kt                     |    15 +
 src/jvmMain/kotlin/afield/ai/knowledge/FilestoreDir.kt                      |    41 +-
 src/jvmMain/kotlin/afield/ai/models/Ai4Installer.kt                         |    11 +
 src/jvmMain/kotlin/afield/ai/models/AnthropicBridge.kt                      |     8 +
 src/jvmMain/kotlin/afield/ai/models/OpenAiCredentials.kt                    |    13 +
 src/jvmMain/kotlin/afield/ai/models/OpenAiIdeCodexBridge.kt                 |    10 +
 src/jvmMain/kotlin/afield/ai/models/ScriptedLlmBridge.kt                    |     7 +
 src/jvmMain/kotlin/afield/ai/retrieval/RelatedContextDiscovery.kt           |    12 +
 src/jvmMain/kotlin/afield/ai/retrieval/SemanticRetrievalService.kt          |    24 +
 src/jvmMain/kotlin/afield/ai/tools/AgentExtensions.kt                       |     7 +-
 src/jvmMain/kotlin/afield/ai/tools/AgentToolExtensions.kt                   |     8 +
 src/jvmMain/kotlin/afield/ai/tools/AiToolUseImprovementMonitor.kt           |     7 +
 src/jvmMain/kotlin/afield/ai/tools/BoxAiTools.kt                            |     8 +-
 src/jvmMain/kotlin/afield/ai/tools/DocsSearchBridge.kt                      |     7 +
 src/jvmMain/kotlin/afield/ai/tools/DocumentGuideTools.kt                    |    11 +
 src/jvmMain/kotlin/afield/ai/tools/DocumentSourceTools.kt                   |    10 +
 src/jvmMain/kotlin/afield/ai/tools/GlslIncludeLibraryIndex.kt               |    12 +
 src/jvmMain/kotlin/afield/ai/tools/StateTools.kt                            |     8 +
 src/jvmMain/kotlin/afield/ai/tools/StateTreeBrowser.kt                      |     7 +
 src/jvmMain/kotlin/afield/ai/tools/ToolMeta.kt                              |    12 +-
 src/jvmMain/kotlin/afield/ai/tools/ToolPool.kt                              |    10 +
 src/jvmMain/kotlin/afield/ai/tools/Tools.kt                                 |    17 +
 src/jvmMain/kotlin/afield/ai/tools/WorkspaceSearch.kt                       |    11 +
 src/jvmMain/kotlin/afield/ai/tools/program/ToolEvidence.kt                  |     2 +
 src/jvmMain/kotlin/afield/ai/tools/program/ToolProgramTools.kt              |     4 +-
 src/jvmMain/kotlin/afield/ai/tools/tooluse/AiToolUseImprovementCli.kt       |    13 +
 src/jvmMain/kotlin/afield/animation/keyframes/keyframes.kt                  |    19 +
 src/jvmMain/kotlin/afield/app/commands/extensions.kt                        |    12 +
 src/jvmMain/kotlin/afield/app/diagnostics/BoxOutputLog.kt                   |     8 +
 src/jvmMain/kotlin/afield/app/diagnostics/CodeUseTelemetryService.kt        |    10 +
 src/jvmMain/kotlin/afield/app/diagnostics/CoroutineProfiling.kt             |     9 +
 src/jvmMain/kotlin/afield/app/diagnostics/FrameProfile.kt                   |     8 +
 src/jvmMain/kotlin/afield/app/diagnostics/debug/JdiProbeHelper.kt           |     8 +
 src/jvmMain/kotlin/afield/app/diagnostics/debug/LiveProbeClient.kt          |     5 +
 src/jvmMain/kotlin/afield/app/diagnostics/debug/RuntimeDebugProbeService.kt |     9 +
 src/jvmMain/kotlin/afield/app/diagnostics/debug/RuntimeProbeProtocol.kt     |     7 +
 src/jvmMain/kotlin/afield/app/diagnostics/logging/SaveReloadLog.kt          |     9 +
 src/jvmMain/kotlin/afield/app/diagnostics/logging/SessionErrorLog.kt        |    10 +
 src/jvmMain/kotlin/afield/app/diagnostics/logging/StatsHeatmap.kt           |     7 +
 src/jvmMain/kotlin/afield/app/diagnostics/logging/logger.kt                 |    10 +
 src/jvmMain/kotlin/afield/app/server/CustomMarking.kt                       |     9 +
 src/jvmMain/kotlin/afield/app/server/InterruptSignal.kt                     |     4 +
 src/jvmMain/kotlin/afield/app/server/NetStats.kt                            |     4 +
 src/jvmMain/kotlin/afield/app/server/OscEditorBackdoor.kt                   |    10 +
 src/jvmMain/kotlin/afield/app/server/PatchyWeb.kt                           |    10 +
 src/jvmMain/kotlin/afield/app/server/Server.kt                              |    12 +
 src/jvmMain/kotlin/afield/app/server/UserThemeManager.kt                    |     9 +
 src/jvmMain/kotlin/afield/app/server/arguments.kt                           |     7 +
 src/jvmMain/kotlin/afield/app/server/browsing.kt                            |    10 +
 src/jvmMain/kotlin/afield/app/server/controllers.kt                         |    10 +
 src/jvmMain/kotlin/afield/app/server/dynamicRouting.kt                      |     8 +
 src/jvmMain/kotlin/afield/app/server/fileproxy.kt                           |    10 +
 src/jvmMain/kotlin/afield/app/server/importing.kt                           |     9 +
 src/jvmMain/kotlin/afield/app/server/inspecting.kt                          |    11 +
 src/jvmMain/kotlin/afield/app/server/logstream.kt                           |     8 +
 src/jvmMain/kotlin/afield/app/settings/PersonalLayer.kt                     |     8 +
 src/jvmMain/kotlin/afield/app/settings/PersonalLayerSmokeTest.kt            |     1 +
 src/jvmMain/kotlin/afield/app/settings/UserKeybindingsManager.kt            |     8 +
 src/jvmMain/kotlin/afield/app/settings/util_settings.kt                     |    14 +
 src/jvmMain/kotlin/afield/audio/ai/CorpusClip.kt                            |    10 +
 src/jvmMain/kotlin/afield/audio/analysis/loris/Loris.kt                     |    22 +
 src/jvmMain/kotlin/afield/audio/analysis/loris/LorisDocs.kt                 |     9 +
 src/jvmMain/kotlin/afield/audio/analysis/loris/LorisTransforms.kt           |    10 +-
 src/jvmMain/kotlin/afield/audio/analysis/loris/LorisVisualizer.kt           |    10 +
 src/jvmMain/kotlin/afield/audio/csound/CsdDocument.kt                       |     5 +
 src/jvmMain/kotlin/afield/audio/csound/CsoundInstance.kt                    |     5 +
 src/jvmMain/kotlin/afield/audio/csound/CsoundLanguage.kt                    |     5 +
 src/jvmMain/kotlin/afield/audio/csound/CsoundNative.kt                      |     5 +
 src/jvmMain/kotlin/afield/audio/csound/CsoundVocabulary.kt                  |     5 +
 src/jvmMain/kotlin/afield/audio/engine/OscillatorBankArrays.kt              |     4 +
 src/jvmMain/kotlin/afield/audio/engine/OscillatorModels.kt                  |     6 +
 src/jvmMain/kotlin/afield/audio/engine/ResonatorBank.kt                     |     6 +
 src/jvmMain/kotlin/afield/audio/engine/SimpleSpatialAudio.kt                |     6 +
 src/jvmMain/kotlin/afield/audio/engine/TrackingResonatorAnalysis.kt         |     6 +
 src/jvmMain/kotlin/afield/audio/engine/WavFloatArrayLoader.kt               |     6 +
 src/jvmMain/kotlin/afield/audio/engine/WavFloatArrayWriter.kt               |     4 +
 src/jvmMain/kotlin/afield/audio/pd/PdInstance.kt                            |     4 +
 src/jvmMain/kotlin/afield/audio/pd/PdLanguage.kt                            |     4 +
 src/jvmMain/kotlin/afield/audio/pd/PdNative.kt                              |     4 +
 src/jvmMain/kotlin/afield/audio/pd/PdObjects.kt                             |     4 +
 src/jvmMain/kotlin/afield/audio/pd/PdPatchGraph.kt                          |     4 +
 src/jvmMain/kotlin/afield/audio/spatial/steamaudio/AmbisonicsEffects.kt     |     7 +
 src/jvmMain/kotlin/afield/audio/spatial/steamaudio/AudioBuffer.kt           |     8 +
 src/jvmMain/kotlin/afield/audio/spatial/steamaudio/AudioEffect.kt           |     5 +
 src/jvmMain/kotlin/afield/audio/spatial/steamaudio/Baking.kt                |     8 +
 src/jvmMain/kotlin/afield/audio/spatial/steamaudio/BinauralEffect.kt        |     5 +
 src/jvmMain/kotlin/afield/audio/spatial/steamaudio/Callbacks.kt             |     6 +
 src/jvmMain/kotlin/afield/audio/spatial/steamaudio/Devices.kt               |     6 +
 src/jvmMain/kotlin/afield/audio/spatial/steamaudio/DirectEffect.kt          |     7 +
 src/jvmMain/kotlin/afield/audio/spatial/steamaudio/EnergyField.kt           |     7 +
 src/jvmMain/kotlin/afield/audio/spatial/steamaudio/Enums.kt                 |     6 +
 src/jvmMain/kotlin/afield/audio/spatial/steamaudio/Geometry2D.kt            |     6 +
 src/jvmMain/kotlin/afield/audio/spatial/steamaudio/Handles.kt               |     6 +
 src/jvmMain/kotlin/afield/audio/spatial/steamaudio/Hrtf.kt                  |     5 +
 src/jvmMain/kotlin/afield/audio/spatial/steamaudio/Models.kt                |     5 +
 src/jvmMain/kotlin/afield/audio/spatial/steamaudio/PathEffect.kt            |     7 +
 src/jvmMain/kotlin/afield/audio/spatial/steamaudio/Probes.kt                |     7 +
 src/jvmMain/kotlin/afield/audio/spatial/steamaudio/QualityPreset.kt         |     5 +
 src/jvmMain/kotlin/afield/audio/spatial/steamaudio/ReflectionEffect.kt      |     8 +
 src/jvmMain/kotlin/afield/audio/spatial/steamaudio/Scene.kt                 |     7 +
 src/jvmMain/kotlin/afield/audio/spatial/steamaudio/Serialization.kt         |     5 +
 src/jvmMain/kotlin/afield/audio/spatial/steamaudio/Simulation.kt            |     6 +
 src/jvmMain/kotlin/afield/audio/spatial/steamaudio/SteamAudio.kt            |     5 +
 src/jvmMain/kotlin/afield/audio/spatial/steamaudio/SteamAudioContext.kt     |     5 +
 src/jvmMain/kotlin/afield/audio/spatial/steamaudio/SteamAudioException.kt   |     5 +
 src/jvmMain/kotlin/afield/audio/spatial/steamaudio/SteamAudioNatives.kt     |     4 +
 src/jvmMain/kotlin/afield/audio/synth/AbletonSetSmokeTest.kt                |     8 +
 src/jvmMain/kotlin/afield/audio/synth/BooleanNetworkSoundDocs.kt            |     8 +
 src/jvmMain/kotlin/afield/audio/synth/DocsAudioProvenance.kt                |     8 +
 src/jvmMain/kotlin/afield/audio/synth/DoughDspPlotGallery.kt                |     9 +
 src/jvmMain/kotlin/afield/audio/synth/DoughDspSmokeTest.kt                  |     4 +
 src/jvmMain/kotlin/afield/audio/synth/DrawnMorphDocs.kt                     |     8 +
 src/jvmMain/kotlin/afield/audio/synth/DrawnScoreDocs.kt                     |     2 +
 src/jvmMain/kotlin/afield/audio/synth/FaustPolySmokeTest.kt                 |     4 +
 src/jvmMain/kotlin/afield/audio/synth/FaustSmokeTest.kt                     |     1 +
 src/jvmMain/kotlin/afield/audio/synth/FnSignal.kt                           |     7 +
 src/jvmMain/kotlin/afield/audio/synth/FnSoundDocs.kt                        |     8 +-
 src/jvmMain/kotlin/afield/audio/synth/HarmonicPhaseTracker.kt               |     8 +
 src/jvmMain/kotlin/afield/audio/synth/NotationPatterns.kt                   |     4 +
 src/jvmMain/kotlin/afield/audio/synth/Orca.kt                               |     5 +
 src/jvmMain/kotlin/afield/audio/synth/OrcaHeadless.kt                       |     4 +
 src/jvmMain/kotlin/afield/audio/synth/OrcaSoundDocs.kt                      |     6 +-
 src/jvmMain/kotlin/afield/audio/synth/PatSpatialHeadTrackedDocs.kt          |     4 +
 src/jvmMain/kotlin/afield/audio/synth/PetriSparksDocs.kt                    |     8 +
 src/jvmMain/kotlin/afield/audio/synth/SonicPiPatternsSmokeTest.kt           |     1 +
 src/jvmMain/kotlin/afield/audio/synth/SonicPiSmokeTest.kt                   |     4 +
 src/jvmMain/kotlin/afield/audio/synth/SoundRenderPathDocs.kt                |     7 +
 src/jvmMain/kotlin/afield/audio/synth/Subharmonic.kt                        |     5 +
 src/jvmMain/kotlin/afield/audio/synth/SubharmonicSoundDocs.kt               |     2 +
 src/jvmMain/kotlin/afield/audio/synth/TrackedPhase.kt                       |    22 +-
 src/jvmMain/kotlin/afield/audio/synth/TrackedPhaseBank.kt                   |     8 +
 src/jvmMain/kotlin/afield/audio/synth/TrackedPhaseSignal.kt                 |     8 +
 src/jvmMain/kotlin/afield/audio/tone/ToneKotlinJs.kt                        |    38 +-
 src/jvmMain/kotlin/afield/data/query/datalog/DatalogLineNotes.kt            |     2 +
 src/jvmMain/kotlin/afield/data/query/datalog/DatalogSmokeTest.kt            |    13 +-
 src/jvmMain/kotlin/afield/data/query/graph/GraphQuerySmokeTest.kt           |    16 +-
 src/jvmMain/kotlin/afield/data/query/graph/PathOperator.kt                  |    11 +-
 src/jvmMain/kotlin/afield/data/table/spreadsheet.kt                         |    15 +
 src/jvmMain/kotlin/afield/document/context/ContextResolution.kt             |    11 +
 src/jvmMain/kotlin/afield/document/context/ContextWhy.kt                    |     8 +
 src/jvmMain/kotlin/afield/document/context/ContextWhyTools.kt               |     8 +
 src/jvmMain/kotlin/afield/document/context/WhyAnswer.kt                     |     8 +
 src/jvmMain/kotlin/afield/document/context/why.kt                           |     8 +
 src/jvmMain/kotlin/afield/document/graph/Boxes.kt                           |    14 +
 src/jvmMain/kotlin/afield/document/graph/nesting.kt                         |    10 +
 src/jvmMain/kotlin/afield/document/history/HistoryMetrics.kt                |    14 +
 src/jvmMain/kotlin/afield/document/history/history.kt                       |    12 +-
 src/jvmMain/kotlin/afield/document/persistence/DocumentShutdownFlush.kt     |     4 +
 src/jvmMain/kotlin/afield/document/persistence/FileWatchManager.kt          |     8 +
 src/jvmMain/kotlin/afield/document/persistence/LinalgSerialization.kt       |     8 +
 src/jvmMain/kotlin/afield/document/persistence/SafeSaveAtomicSmokeTest.kt   |     3 +-
 src/jvmMain/kotlin/afield/document/persistence/edn.kt                       |     8 +
 src/jvmMain/kotlin/afield/document/persistence/saving.kt                    |    10 +
 src/jvmMain/kotlin/afield/document/properties/PropertyBag.kt                |     8 +
 src/jvmMain/kotlin/afield/document/properties/PropertyDependencies.kt       |     8 +
 src/jvmMain/kotlin/afield/document/properties/binding.kt                    |    14 +
 src/jvmMain/kotlin/afield/document/workspace/KitFirstOpen.kt                |     2 +
 src/jvmMain/kotlin/afield/document/workspace/Kits.kt                        |     7 +-
 src/jvmMain/kotlin/afield/document/workspace/VirtualFileSystem.kt           |    14 +
 src/jvmMain/kotlin/afield/document/workspace/copy.kt                        |    16 +-
 src/jvmMain/kotlin/afield/document/workspace/dir.kt                         |    13 +
 src/jvmMain/kotlin/afield/document/workspace/metadata.kt                    |    11 +
 src/jvmMain/kotlin/afield/document/workspace/provisional.kt                 |    14 +
 src/jvmMain/kotlin/afield/geometry/mesh/MeasurementHelpers.kt               |     8 +
 src/jvmMain/kotlin/afield/geometry/path/FLine.kt                            |     8 +
 src/jvmMain/kotlin/afield/geometry/path/FLineEditing.kt                     |     8 +
 src/jvmMain/kotlin/afield/geometry/path/FLineGaussianProcess.kt             |    10 +
 src/jvmMain/kotlin/afield/geometry/path/FLineGeometrySmile.kt               |     8 +
 src/jvmMain/kotlin/afield/geometry/path/FLineInstructionTrajectories.kt     |     7 +
 src/jvmMain/kotlin/afield/geometry/path/FLineInterpolation.kt               |     8 +
 src/jvmMain/kotlin/afield/geometry/path/FLineSampling.kt                    |     8 +
 src/jvmMain/kotlin/afield/geometry/path/FLineSerialization.kt               |     9 +
 src/jvmMain/kotlin/afield/geometry/path/FLineThickening.kt                  |     9 +
 src/jvmMain/kotlin/afield/geometry/path/FLineWiggle.kt                      |     8 +
 src/jvmMain/kotlin/afield/geometry/path/LowSegmentFLineFitting.kt           |     8 +
 src/jvmMain/kotlin/afield/geometry/path/PathFlattener.kt                    |     7 +
 src/jvmMain/kotlin/afield/geometry/path/PlanarTessellation.kt               |     9 +
 src/jvmMain/kotlin/afield/geometry/path/PolynomialTreeFitting.kt            |    11 +
 src/jvmMain/kotlin/afield/geometry/path/java2d/FLinesAndJavaShapes.kt       |    11 +
 src/jvmMain/kotlin/afield/geometry/path/planning/CubicBezier3D.kt           |    10 +
 src/jvmMain/kotlin/afield/geometry/path/planning/Point3D.kt                 |     9 +
 src/jvmMain/kotlin/afield/geometry/solid/manifold/ManifoldNative.kt         |     7 +
 src/jvmMain/kotlin/afield/geometry/solid/manifold/PlanarContact.kt          |     9 +
 src/jvmMain/kotlin/afield/geometry/solid/manifold/Section.kt                |     7 +
 src/jvmMain/kotlin/afield/geometry/solid/manifold/Solid.kt                  |     9 +
 src/jvmMain/kotlin/afield/geometry/solid/manifold/SolidPlanarMesh.kt        |     8 +
 src/jvmMain/kotlin/afield/geometry/solid/solidcraft/SolidCraft.kt           |     8 +
 src/jvmMain/kotlin/afield/geometry/spatial/stippletransport.kt              |    13 +
 src/jvmMain/kotlin/afield/graphics/paint/GestureReplayMain.kt               |     8 +
 src/jvmMain/kotlin/afield/graphics/paint/Klecks.kt                          |     5 +
 src/jvmMain/kotlin/afield/graphics/paint/PaintCanvas.kt                     |     8 +
 src/jvmMain/kotlin/afield/graphics/paint/PaintNative.kt                     |     5 +
 src/jvmMain/kotlin/afield/graphics/paint/PaintNoise.kt                      |     5 +
 src/jvmMain/kotlin/afield/graphics/paint/PaintToolbox.kt                    |     5 +
 src/jvmMain/kotlin/afield/graphics/paint/PaintTools.kt                      |    11 +
 src/jvmMain/kotlin/afield/graphics/paint/StereoWarp.kt                      |     5 +
 src/jvmMain/kotlin/afield/graphics/paint/TimedGesture.kt                    |     5 +
 src/jvmMain/kotlin/afield/graphics/paint/WaterMedia.kt                      |     8 +
 src/jvmMain/kotlin/afield/graphics/render/AccelerationStructure.kt          |    11 +
 src/jvmMain/kotlin/afield/graphics/render/ClusterGeometry.kt                |     7 +
 src/jvmMain/kotlin/afield/graphics/render/ClusterGeometryDemo.kt            |     7 +
 src/jvmMain/kotlin/afield/graphics/render/ComputeMesh.kt                    |    11 +
 src/jvmMain/kotlin/afield/graphics/render/ComputeModule.kt                  |     8 +
 src/jvmMain/kotlin/afield/graphics/render/FLineUpload.kt                    |     7 +
 src/jvmMain/kotlin/afield/graphics/render/FourVisualizers.kt                |    12 +
 src/jvmMain/kotlin/afield/graphics/render/GeometryAgentTools.kt             |    21 +-
 src/jvmMain/kotlin/afield/graphics/render/Mesh.kt                           |     8 +
 src/jvmMain/kotlin/afield/graphics/render/MeshMesh.kt                       |     7 +
 src/jvmMain/kotlin/afield/graphics/render/MeshModule.kt                     |     8 +
 src/jvmMain/kotlin/afield/graphics/render/Module.kt                         |    17 +
 src/jvmMain/kotlin/afield/graphics/render/ModuleRebuild.kt                  |    11 +-
 src/jvmMain/kotlin/afield/graphics/render/RayTracingModule.kt               |    13 +
 src/jvmMain/kotlin/afield/graphics/render/SplatCloudEdit.kt                 |     7 +
 src/jvmMain/kotlin/afield/graphics/render/SplatCloudEditTest.kt             |    10 +-
 src/jvmMain/kotlin/afield/graphics/render/SplatFusedPackTest.kt             |     6 +-
 src/jvmMain/kotlin/afield/graphics/render/SplatRaster.kt                    |     7 +
 src/jvmMain/kotlin/afield/graphics/render/SplatRasterTest.kt                |    10 +-
 src/jvmMain/kotlin/afield/graphics/render/SplatTools.kt                     |     9 +-
 src/jvmMain/kotlin/afield/graphics/render/TileResidency.kt                  |     7 +
 src/jvmMain/kotlin/afield/graphics/render/groups.kt                         |    10 +
 src/jvmMain/kotlin/afield/graphics/render/lighting/DirectLighting.kt        |    13 +
 src/jvmMain/kotlin/afield/graphics/render/lighting/DirectLightingDemo.kt    |    10 +
 src/jvmMain/kotlin/afield/graphics/render/lighting/OpaqueRayScene.kt        |    10 +
 src/jvmMain/kotlin/afield/graphics/render/lighting/RtxdiSettings.kt         |     9 +
 src/jvmMain/kotlin/afield/graphics/render/neural/OpenDlssNrImage.kt         |     8 +
 src/jvmMain/kotlin/afield/graphics/render/neural/OpenDlssNrPass.kt          |    10 +
 src/jvmMain/kotlin/afield/graphics/render/neural/OpenDlssNrStudy.kt         |     9 +
 src/jvmMain/kotlin/afield/graphics/render/neural/OpenDlssNrSupport.kt       |    10 +
 src/jvmMain/kotlin/afield/graphics/shader/Introspection.kt                  |    12 +
 src/jvmMain/kotlin/afield/graphics/shader/MetalShaderErrors.kt              |     4 +
 src/jvmMain/kotlin/afield/graphics/shader/MslEditorLanguage.kt              |     4 +
 src/jvmMain/kotlin/afield/graphics/shader/ShaderAbi.kt                      |     8 +
 src/jvmMain/kotlin/afield/graphics/shader/ShaderInterfaceTransition.kt      |    10 +
 src/jvmMain/kotlin/afield/graphics/shader/ShaderSPIRVUtils.kt               |     8 +
 src/jvmMain/kotlin/afield/graphics/shader/SlangCompiler.kt                  |     8 +
 src/jvmMain/kotlin/afield/graphics/shader/learning/GpuShaderLearningView.kt |     6 +
 src/jvmMain/kotlin/afield/graphics/shader/learning/GpuShaderTraining.kt     |     6 +
 src/jvmMain/kotlin/afield/graphics/shader/learning/LearnedShader.kt         |     6 +
 src/jvmMain/kotlin/afield/graphics/shader/learning/ShaderLearningChecks.kt  |     6 +
 src/jvmMain/kotlin/afield/graphics/shader/learning/ShaderLearningView.kt    |     6 +
 src/jvmMain/kotlin/afield/graphics/shader/learning/ShaderTraining.kt        |     6 +
 src/jvmMain/kotlin/afield/graphics/text/slug/SlugBuffer.kt                  |     9 +
 src/jvmMain/kotlin/afield/graphics/text/slug/SlugPath.kt                    |     9 +
 src/jvmMain/kotlin/afield/graphics/texture/IOSurfaceHostBuffer.kt           |     4 +
 src/jvmMain/kotlin/afield/graphics/texture/Image.kt                         |    10 +
 src/jvmMain/kotlin/afield/graphics/texture/ImageView.kt                     |     8 +
 src/jvmMain/kotlin/afield/graphics/texture/LinuxDmaBufExport.kt             |     9 +
 src/jvmMain/kotlin/afield/graphics/texture/MetalIOSurfaceExport.kt          |     7 +
 src/jvmMain/kotlin/afield/graphics/texture/TextureSampler.kt                |     4 +
 src/jvmMain/kotlin/afield/graphics/texture/UnixFdPass.kt                    |     7 +
 src/jvmMain/kotlin/afield/graphics/texture/VulkanExternalMemory.kt          |    42 +-
 src/jvmMain/kotlin/afield/graphics/texture/mega/MegaTexture.kt              |     9 +
 src/jvmMain/kotlin/afield/graphics/texture/mega/MegaTextureCatalog.kt       |     8 +
 src/jvmMain/kotlin/afield/graphics/texture/mega/MegaTextureDecoder.kt       |     8 +
 src/jvmMain/kotlin/afield/graphics/texture/mega/MegaTextureLayout.kt        |     7 +
 src/jvmMain/kotlin/afield/graphics/texture/mega/megaTextureShader.kt        |     7 +
 src/jvmMain/kotlin/afield/graphics/texture/textures.kt                      |    11 +
 src/jvmMain/kotlin/afield/graphics/texture/video/AudioDecoder.kt            |     4 +
 src/jvmMain/kotlin/afield/graphics/texture/video/AudioPlayer.kt             |     5 +
 src/jvmMain/kotlin/afield/graphics/texture/video/FrameSetBarrier.kt         |     8 +
 src/jvmMain/kotlin/afield/graphics/texture/video/FrameSetPacer.kt           |     9 +
 src/jvmMain/kotlin/afield/graphics/texture/video/PlaybackClock.kt           |     6 +
 src/jvmMain/kotlin/afield/graphics/texture/video/VideoDecoder.kt            |     8 +
 src/jvmMain/kotlin/afield/graphics/texture/video/VideoFrameIndex.kt         |    11 +
 src/jvmMain/kotlin/afield/graphics/texture/video/VideoTexture.kt            |     9 +
 src/jvmMain/kotlin/afield/graphics/texture/video/WebcamTexture.kt           |     7 +
 src/jvmMain/kotlin/afield/graphics/three/Three.kt                           |    35 +-
 src/jvmMain/kotlin/afield/graphics/three/ThreeJsInspector.kt                |    51 +-
 src/jvmMain/kotlin/afield/graphics/three/ThreeJsViewer.kt                   |    12 +
 src/jvmMain/kotlin/afield/graphics/three/ThreeScene.kt                      |    38 +-
 src/jvmMain/kotlin/afield/graphics/visionos/VisionGeometryPreview.kt        |     7 +-
 src/jvmMain/kotlin/afield/graphics/visionos/VisionMetal.kt                  |    16 +
 src/jvmMain/kotlin/afield/graphics/visionos/VisionMetalKotlinJs.kt          |     7 +-
 src/jvmMain/kotlin/afield/graphics/visionos/VisionMetalProgramCheck.kt      |     9 +-
 src/jvmMain/kotlin/afield/graphics/visionos/VisionPose.kt                   |    13 +-
 src/jvmMain/kotlin/afield/graphics/visionos/VisionPoseLatency.kt            |    10 +-
 src/jvmMain/kotlin/afield/graphics/visionos/VisionPoseStereoSource.kt       |     1 +
 src/jvmMain/kotlin/afield/graphics/vulkan/ModuleDestinations.kt             |     6 +
 src/jvmMain/kotlin/afield/graphics/vulkan/Renderer.kt                       |    15 +
 src/jvmMain/kotlin/afield/graphics/vulkan/buffers/FrameBufferGroup.kt       |    14 +
 src/jvmMain/kotlin/afield/graphics/vulkan/buffers/GpuSchema.kt              |    10 +
 src/jvmMain/kotlin/afield/graphics/vulkan/buffers/ParallelGeometryPool.kt   |     7 +
 src/jvmMain/kotlin/afield/graphics/vulkan/buffers/Structure.kt              |    14 +
 src/jvmMain/kotlin/afield/graphics/vulkan/buffers/VBuffer.kt                |     8 +
 src/jvmMain/kotlin/afield/graphics/vulkan/buffers/magic.kt                  |    14 +
 src/jvmMain/kotlin/afield/graphics/vulkan/device/Attachment.kt              |     8 +
 src/jvmMain/kotlin/afield/graphics/vulkan/device/CommandBuffer.kt           |     6 +
 src/jvmMain/kotlin/afield/graphics/vulkan/device/CommandPool.kt             |     7 +
 src/jvmMain/kotlin/afield/graphics/vulkan/device/DescriptorSetLayout.kt     |     6 +
 src/jvmMain/kotlin/afield/graphics/vulkan/device/Device.kt                  |     6 +
 src/jvmMain/kotlin/afield/graphics/vulkan/device/Fence.kt                   |     8 +
 src/jvmMain/kotlin/afield/graphics/vulkan/device/ImageLayouts.kt            |     8 +
 src/jvmMain/kotlin/afield/graphics/vulkan/device/Instance.kt                |    11 +
 src/jvmMain/kotlin/afield/graphics/vulkan/device/PhysicalDevice.kt          |     4 +
 src/jvmMain/kotlin/afield/graphics/vulkan/device/PipelineCache.kt           |     7 +
 src/jvmMain/kotlin/afield/graphics/vulkan/device/Queue.kt                   |     6 +
 src/jvmMain/kotlin/afield/graphics/vulkan/device/RayDeviceSupport.kt        |     6 +
 src/jvmMain/kotlin/afield/graphics/vulkan/device/Semaphore.kt               |     9 +
 src/jvmMain/kotlin/afield/graphics/vulkan/device/Surface.kt                 |     7 +
 src/jvmMain/kotlin/afield/graphics/vulkan/device/SwapChain.kt               |     6 +
 src/jvmMain/kotlin/afield/graphics/vulkan/device/VulkanNames.kt             |    11 +
 src/jvmMain/kotlin/afield/graphics/vulkan/device/timing.kt                  |     6 +
 src/jvmMain/kotlin/afield/graphics/vulkan/imgui/ImGuiPanel.kt               |     9 +
 src/jvmMain/kotlin/afield/graphics/vulkan/imgui/ImGuiRenderToTexture.kt     |    10 +
 .../kotlin/afield/graphics/vulkan/targets/BinocularGazeIntersection.kt      |     8 +
 src/jvmMain/kotlin/afield/graphics/vulkan/targets/ComputeTarget.kt          |     8 +
 src/jvmMain/kotlin/afield/graphics/vulkan/targets/FrameHistory.kt           |     8 +
 src/jvmMain/kotlin/afield/graphics/vulkan/targets/MotionItems.kt            |     9 +
 .../kotlin/afield/graphics/vulkan/targets/MultiPingPongRenderToTexture.kt   |    10 +
 src/jvmMain/kotlin/afield/graphics/vulkan/targets/OpenXREyeTracking.kt      |     8 +
 src/jvmMain/kotlin/afield/graphics/vulkan/targets/OpenXRHmdTarget.kt        |     9 +
 .../kotlin/afield/graphics/vulkan/targets/PingPongRenderToTexture.kt        |    10 +
 src/jvmMain/kotlin/afield/graphics/vulkan/targets/RenderTarget.kt           |     9 +
 src/jvmMain/kotlin/afield/graphics/vulkan/targets/RenderToTexture.kt        |    12 +
 src/jvmMain/kotlin/afield/graphics/vulkan/targets/RollingCapture.kt         |     8 +
 src/jvmMain/kotlin/afield/graphics/vulkan/targets/SideBySideStereoTarget.kt |     9 +
 src/jvmMain/kotlin/afield/graphics/vulkan/targets/StereoPoseSource.kt       |     8 +
 src/jvmMain/kotlin/afield/graphics/vulkan/util/16bits.kt                    |     6 +
 src/jvmMain/kotlin/afield/graphics/vulkan/util/AutoDisposer.kt              |    11 +
 src/jvmMain/kotlin/afield/graphics/vulkan/util/Camera.kt                    |     6 +
 src/jvmMain/kotlin/afield/graphics/vulkan/util/CameraPose.kt                |     6 +
 src/jvmMain/kotlin/afield/graphics/vulkan/util/FieldProbe.kt                |    11 +
 src/jvmMain/kotlin/afield/graphics/vulkan/util/FieldStats.kt                |    11 +
 src/jvmMain/kotlin/afield/graphics/vulkan/util/FramePresentationLog.kt      |     6 +
 src/jvmMain/kotlin/afield/graphics/vulkan/util/GpuNeighbourGrid.kt          |     4 +
 src/jvmMain/kotlin/afield/graphics/vulkan/util/GpuProbe.kt                  |     7 +
 src/jvmMain/kotlin/afield/graphics/vulkan/util/GpuRadixSort.kt              |     6 +
 src/jvmMain/kotlin/afield/graphics/vulkan/util/ImageReducer.kt              |     6 +
 src/jvmMain/kotlin/afield/graphics/vulkan/util/KeyCamera.kt                 |     6 +
 src/jvmMain/kotlin/afield/graphics/vulkan/util/KeyEvents.kt                 |     6 +
 src/jvmMain/kotlin/afield/graphics/vulkan/util/MapOfSequences.kt            |     6 +
 src/jvmMain/kotlin/afield/graphics/vulkan/util/MouseEvents.kt               |     6 +
 src/jvmMain/kotlin/afield/graphics/vulkan/util/Options.kt                   |     9 +
 src/jvmMain/kotlin/afield/graphics/vulkan/util/OrbitControls.kt             |     4 +
 src/jvmMain/kotlin/afield/graphics/vulkan/util/Projection.kt                |     8 +
 src/jvmMain/kotlin/afield/graphics/vulkan/util/VulkanProbe.kt               |     6 +
 src/jvmMain/kotlin/afield/graphics/vulkan/util/Window.kt                    |     9 +
 src/jvmMain/kotlin/afield/graphics/vulkan/util/fmchirp.kt                   |     6 +
 src/jvmMain/kotlin/afield/graphics/vulkan/util/util.kt                      |     6 +
 src/jvmMain/kotlin/afield/io/dmx/DmxVisual.kt                               |    12 +
 src/jvmMain/kotlin/afield/io/midi/rtp/RtpMidiBox.kt                         |     9 +
 src/jvmMain/kotlin/afield/io/midi/rtp/RtpMidiCodec.kt                       |     7 +
 src/jvmMain/kotlin/afield/io/midi/rtp/RtpMidiSession.kt                     |     8 +
 src/jvmMain/kotlin/afield/io/mqtt/MqttBox.kt                                |     9 +
 src/jvmMain/kotlin/afield/io/mqtt/MqttBroker.kt                             |     8 +
 src/jvmMain/kotlin/afield/io/mqtt/MqttClient.kt                             |     9 +
 src/jvmMain/kotlin/afield/io/mqtt/MqttCodec.kt                              |     8 +
 src/jvmMain/kotlin/afield/io/osc/query/Mdns.kt                              |     8 +
 src/jvmMain/kotlin/afield/io/osc/query/OscQueryBox.kt                       |     9 +
 src/jvmMain/kotlin/afield/io/osc/query/OscQueryCodec.kt                     |     7 +
 src/jvmMain/kotlin/afield/io/osc/query/OscQueryRemote.kt                    |     9 +
 src/jvmMain/kotlin/afield/io/osc/query/OscQueryServer.kt                    |     9 +
 src/jvmMain/kotlin/afield/io/syphon/Syphon.kt                               |     2 +
 src/jvmMain/kotlin/afield/io/syphon/SyphonGpu.kt                            |     2 +
 src/jvmMain/kotlin/afield/io/syphon/SyphonNative.kt                         |     9 +-
 src/jvmMain/kotlin/afield/io/syphon/SyphonTexture.kt                        |     6 +-
 src/jvmMain/kotlin/afield/io/timecode/Ltc.kt                                |    10 +
 src/jvmMain/kotlin/afield/io/timecode/Mtc.kt                                |     9 +
 src/jvmMain/kotlin/afield/io/timecode/Timecode.kt                           |     7 +
 src/jvmMain/kotlin/afield/io/timecode/TimecodeClock.kt                      |     5 +
 src/jvmMain/kotlin/afield/lang/brief/Brief.kt                               |    69 +-
 src/jvmMain/kotlin/afield/lang/brief/BriefEditorExtension.kt                |    15 +-
 src/jvmMain/kotlin/afield/lang/lua/LuaCatalog.kt                            |     4 +
 src/jvmMain/kotlin/afield/lang/lua/LuaLanguage.kt                           |     6 +
 src/jvmMain/kotlin/afield/lang/lua/LuaNative.kt                             |     4 +
 src/jvmMain/kotlin/afield/lang/lua/LuaState.kt                              |     6 +
 src/jvmMain/kotlin/afield/lang/python/NumpyShared.kt                        |     8 +
 src/jvmMain/kotlin/afield/lang/python/PythonErrors.kt                       |     7 +
 src/jvmMain/kotlin/afield/lang/python/PythonLineNotes.kt                    |     8 +
 src/jvmMain/kotlin/afield/lang/rectsl/RectSLFitFigure.kt                    |    22 +-
 src/jvmMain/kotlin/afield/lang/rectsl/RectSLImg.kt                          |     4 +
 src/jvmMain/kotlin/afield/lang/rectsl/RectSLSoftRasterFigure.kt             |    18 +-
 src/jvmMain/kotlin/afield/lang/rectsl/RectSLVocabulary.kt                   |     9 +-
 src/jvmMain/kotlin/afield/lang/rectsl/study/EmbeddedRectSL.kt               |     5 +
 src/jvmMain/kotlin/afield/lang/rectsl/study/RectSLPrograms.kt               |     9 +
 src/jvmMain/kotlin/afield/lang/rectsl/study/RectSLStudy.kt                  |     8 +
 src/jvmMain/kotlin/afield/lang/rectsl/study/RectSLStudyRender.kt            |     6 +
 src/jvmMain/kotlin/afield/lang/rectsl/study/RectSLStudyViewer.kt            |     5 +
 src/jvmMain/kotlin/afield/lang/rectsl/study/RectSLWindowLinks.kt            |     5 +
 src/jvmMain/kotlin/afield/lang/remotejs/RemoteArKotlinJs.kt                 |    11 +
 src/jvmMain/kotlin/afield/lang/remotejs/RemoteLifecycleKotlinJs.kt          |     8 +
 src/jvmMain/kotlin/afield/lang/solidsl/SolidExpr.kt                         |    20 +
 src/jvmMain/kotlin/afield/lang/stacksl/PhaseNoise.kt                        |     2 +
 src/jvmMain/kotlin/afield/lang/stacksl/StackSLVocabulary.kt                 |     9 +
 src/jvmMain/kotlin/afield/lang/stacksl/cpu/StackSLCpuGallery.kt             |    46 +-
 src/jvmMain/kotlin/afield/lang/stacksl/cpu/StackSLIntervals.kt              |     8 +
 src/jvmMain/kotlin/afield/lang/stacksl/cpu/StackSLLpipsLoss.kt              |     8 +
 src/jvmMain/kotlin/afield/lang/stacksl/evolution/StackSLEvolution.kt        |     9 +
 src/jvmMain/kotlin/afield/lang/stacksl/evolution/StackSLEvolveStudio.kt     |     7 +
 src/jvmMain/kotlin/afield/lang/stacksl/evolution/StackSLWatchmaker.kt       |     8 +
 src/jvmMain/kotlin/afield/lang/stacksl/stackAutoNumbers.kt                  |     8 +
 src/jvmMain/kotlin/afield/lang/stacksl/study/EmbeddedStackSL.kt             |     8 +
 src/jvmMain/kotlin/afield/lang/stacksl/study/StackAutoCard.kt               |     9 +
 src/jvmMain/kotlin/afield/lang/stacksl/study/StackAutoStudy.kt              |     8 +
 src/jvmMain/kotlin/afield/lang/stacksl/study/StackSLGpu.kt                  |     9 +
 src/jvmMain/kotlin/afield/lang/stacksl/study/StackSLPrograms.kt             |     9 +
 src/jvmMain/kotlin/afield/lang/stacksl/study/StackSLStudy.kt                |     9 +
 src/jvmMain/kotlin/afield/lang/stacksl/study/StackSLStudyShader.kt          |     8 +
 src/jvmMain/kotlin/afield/lang/stacksl/study/StackSLStudyViewer.kt          |     8 +
 src/jvmMain/kotlin/afield/math/functions/DenseDoubleOps.kt                  |     5 +
 src/jvmMain/kotlin/afield/math/functions/FunctionStudy.kt                   |    10 +
 src/jvmMain/kotlin/afield/math/functions/FunctionStudyTools.kt              |    13 +-
 src/jvmMain/kotlin/afield/math/functions/PatternRewriteSmokeTest.kt         |    20 +-
 src/jvmMain/kotlin/afield/math/functions/RbfField.kt                        |     5 +
 src/jvmMain/kotlin/afield/math/gp/GaussianProcess.kt                        |    11 +
 src/jvmMain/kotlin/afield/math/gp/GpFields.kt                               |     8 +
 src/jvmMain/kotlin/afield/math/gp/GpFit.kt                                  |    10 +
 src/jvmMain/kotlin/afield/math/gp/GpJoint.kt                                |     8 +
 src/jvmMain/kotlin/afield/math/gp/GpKernel.kt                               |    10 +
 src/jvmMain/kotlin/afield/math/gp/GpOptimizer.kt                            |    14 +
 src/jvmMain/kotlin/afield/math/gp/GpPaths.kt                                |     9 +
 src/jvmMain/kotlin/afield/math/gp/GpPreference.kt                           |     8 +
 src/jvmMain/kotlin/afield/math/gp/GpSmile.kt                                |     9 +
 src/jvmMain/kotlin/afield/math/gp/GpVectorProcess.kt                        |    10 +
 src/jvmMain/kotlin/afield/math/linalg/DualQuaternionf.kt                    |    14 +
 src/jvmMain/kotlin/afield/math/linalg/Geometry.kt                           |    12 +
 src/jvmMain/kotlin/afield/math/linalg/arena/ArenaGeometry.kt                |     8 +
 src/jvmMain/kotlin/afield/math/linalg/arena/FrameArena.kt                   |    12 +
 src/jvmMain/kotlin/afield/math/matrixprofile/MatrixProfile.kt               |    11 +
 src/jvmMain/kotlin/afield/math/matrixprofile/MultiDimensional.kt            |    10 +
 src/jvmMain/kotlin/afield/math/optimization/ceres/FfmCeresBackend.kt        |    17 +
 src/jvmMain/kotlin/afield/media/browser/BitmapBrowserSurface.kt             |     4 +
 src/jvmMain/kotlin/afield/media/browser/CopiedExternalBrowserSurface.kt     |     4 +
 src/jvmMain/kotlin/afield/media/browser/ExternalBrowserSurface.kt           |    10 +
 src/jvmMain/kotlin/afield/media/browser/FieldUiBrowserTexture.kt            |     8 +
 src/jvmMain/kotlin/afield/media/browser/HeadlessPuppetBrowser.kt            |     8 +
 src/jvmMain/kotlin/afield/media/browser/LinuxExternalBrowserSurface.kt      |     4 +
 src/jvmMain/kotlin/afield/media/browser/MacExternalBrowserSurface.kt        |     4 +
 src/jvmMain/kotlin/afield/media/browser/PuppetBrowser.kt                    |    10 +
 src/jvmMain/kotlin/afield/media/browser/PuppetBrowserInteraction.kt         |     9 +
 src/jvmMain/kotlin/afield/media/browser/PuppetBrowserKaleidoscope.kt        |     4 +
 src/jvmMain/kotlin/afield/media/browser/PuppetBrowserPage.kt                |    33 +-
 src/jvmMain/kotlin/afield/media/browser/PuppetBrowserVrPanel.kt             |     4 +
 src/jvmMain/kotlin/afield/media/browser/PuppetBrowserWindowPanel.kt         |     4 +
 src/jvmMain/kotlin/afield/media/browser/WindowsExternalBrowserSurface.kt    |    16 +-
 src/jvmMain/kotlin/afield/media/image/util/NodeTool.kt                      |    45 +-
 src/jvmMain/kotlin/afield/media/image/util/PngEncoder.kt                    |    17 +-
 src/jvmMain/kotlin/afield/media/reconstruction/DlssReconstruction.kt        |    10 +
 src/jvmMain/kotlin/afield/media/reconstruction/NgxSupport.kt                |     8 +
 src/jvmMain/kotlin/afield/media/reconstruction/PathTracedReconstruction.kt  |     8 +
 src/jvmMain/kotlin/afield/media/reconstruction/RayReconstructionDemo.kt     |     8 +
 src/jvmMain/kotlin/afield/media/reconstruction/ReconstructionDemo.kt        |     9 +
 src/jvmMain/kotlin/afield/media/stream/ElectronRTTView.kt                   |     9 +
 src/jvmMain/kotlin/afield/media/stream/FieldEncodeProcess.kt                |     8 +
 src/jvmMain/kotlin/afield/media/stream/FieldRTTStreamer.kt                  |     8 +
 src/jvmMain/kotlin/afield/media/video/diffusion/SpinningDiffusionDemo.kt    |     8 +
 src/jvmMain/kotlin/afield/media/video/diffusion/StreamDiffusionConfig.kt    |     9 +
 src/jvmMain/kotlin/afield/media/video/diffusion/StreamDiffusionMotion.kt    |     9 +
 src/jvmMain/kotlin/afield/media/video/diffusion/StreamDiffusionSession.kt   |     9 +
 src/jvmMain/kotlin/afield/media/video/diffusion/StreamDiffusionView.kt      |    11 +
 src/jvmMain/kotlin/afield/media/video/particletracker.kt                    |    11 +
 src/jvmMain/kotlin/afield/media/video/particlevideo.kt                      |    15 +
 src/jvmMain/kotlin/afield/media/video/particlevideoview.kt                  |     9 +
 src/jvmMain/kotlin/afield/media/vision/HardwareOpticalFlow.kt               |     6 +
 src/jvmMain/kotlin/afield/media/vision/OpticalFlowDemo.kt                   |     8 +
 src/jvmMain/kotlin/afield/media/vision/OpticalFlowOperation.kt              |     9 +
 src/jvmMain/kotlin/afield/media/vision/PyramidOpticalFlow.kt                |     8 +
 src/jvmMain/kotlin/afield/runtime/LanguageRuntimeSupport.kt                 |    12 +
 src/jvmMain/kotlin/afield/runtime/MainLanguageRegistry.kt                   |    12 +
 src/jvmMain/kotlin/afield/runtime/completion/AaCompletionClient.kt          |     8 +
 src/jvmMain/kotlin/afield/runtime/completion/CommonMemberPrior.kt           |     8 +
 src/jvmMain/kotlin/afield/runtime/completion/CompletionCorpus.kt            |     8 +
 src/jvmMain/kotlin/afield/runtime/completion/CompletionDiffK1Probe.kt       |     9 +
 src/jvmMain/kotlin/afield/runtime/completion/GlslCompletionSupport.kt       |    11 +
 src/jvmMain/kotlin/afield/runtime/completion/KotlinSourceIndex.kt           |    12 +
 src/jvmMain/kotlin/afield/runtime/execution/BoxBodyRun.kt                   |     7 +
 src/jvmMain/kotlin/afield/runtime/execution/ReturnValueRegistry.kt          |    12 +
 src/jvmMain/kotlin/afield/runtime/execution/actors.kt                       |    17 +
 src/jvmMain/kotlin/afield/runtime/execution/execution.kt                    |    13 +
 src/jvmMain/kotlin/afield/runtime/kotlin/CompiledSnippetTest.kt             |    10 +-
 src/jvmMain/kotlin/afield/runtime/kotlin/DataFramePluginSpike.kt            |    54 +-
 src/jvmMain/kotlin/afield/runtime/kotlin/ExportedTypeNames.kt               |    51 +-
 src/jvmMain/kotlin/afield/runtime/kotlin/ExportedTypeNamesSmokeTest.kt      |     4 +-
 src/jvmMain/kotlin/afield/runtime/kotlin/Kotlin24SyntaxSmokeTest.kt         |    10 +
 src/jvmMain/kotlin/afield/runtime/kotlin/KotlinInterface.kt                 |     7 +
 src/jvmMain/kotlin/afield/runtime/kotlin/KotlinInterface2.kt                |     8 +
 src/jvmMain/kotlin/afield/runtime/kotlin/KotlinScriptEngine.kt              |    10 +
 src/jvmMain/kotlin/afield/runtime/kotlin/MavenClasspathManager.kt           |     8 +
 src/jvmMain/kotlin/afield/runtime/kotlin/ReplBootstrapGateTest.kt           |    36 +-
 src/jvmMain/kotlin/afield/runtime/kotlin/ReplRerunCompileCacheSmokeTest.kt  |    12 +-
 .../kotlin/afield/runtime/kotlin/ReplSnippetDeclarationsSmokeTest.kt        |    61 +-
 src/jvmMain/kotlin/afield/runtime/kotlin/SharedTypes.kt                     |     9 +
 src/jvmMain/kotlin/afield/runtime/kotlin/SharedTypesClasspathSpike.kt       |    38 +-
 src/jvmMain/kotlin/afield/runtime/kotlin/SharedTypesSmokeTest.kt            |     9 +-
 src/jvmMain/kotlin/afield/runtime/kotlin/WitherSmokeTest.kt                 |     9 +-
 src/jvmMain/kotlin/afield/runtime/kotlin/formatter.kt                       |     8 +
 src/jvmMain/kotlin/afield/runtime/kotlin/importHelp.kt                      |     9 +
 src/jvmMain/kotlin/afield/runtime/languages/LanguageRoots.kt                |     2 +
 src/jvmMain/kotlin/afield/runtime/languages/LanguageSpecs.kt                |    18 +
 src/jvmMain/kotlin/afield/runtime/lifecycle/AsyncOperation.kt               |     8 +
 src/jvmMain/kotlin/afield/runtime/lifecycle/LifecycleLedger.kt              |     9 +
 src/jvmMain/kotlin/afield/runtime/lifecycle/Phased.kt                       |     9 +
 src/jvmMain/kotlin/afield/runtime/lifecycle/ReactiveRuns.kt                 |     8 +
 src/jvmMain/kotlin/afield/runtime/lifecycle/RunScope.kt                     |     8 +
 src/jvmMain/kotlin/afield/runtime/lifecycle/Runloop.kt                      |    11 +
 src/jvmMain/kotlin/afield/runtime/lifecycle/RuntimeIdentity.kt              |     5 +
 src/jvmMain/kotlin/afield/runtime/lifecycle/SimulationClock.kt              |     7 +
 src/jvmMain/kotlin/afield/runtime/lifecycle/SimulationReset.kt              |     5 +
 src/jvmMain/kotlin/afield/runtime/lifecycle/cached.kt                       |     5 +
 src/jvmMain/kotlin/afield/runtime/lifecycle/di.kt                           |    14 +-
 src/jvmMain/kotlin/afield/runtime/lifecycle/util_lifecycle.kt               |     8 +
 src/jvmMain/kotlin/afield/runtime/macros/LabelledArguments.kt               |     8 +
 src/jvmMain/kotlin/afield/runtime/macros/PatternRewrite.kt                  |     9 +
 src/jvmMain/kotlin/afield/runtime/macros/ai_macro.kt                        |    11 +
 src/jvmMain/kotlin/afield/runtime/macros/macros.kt                          |    13 +
 src/jvmMain/kotlin/afield/simulation/flow/FlowShaders.kt                    |    10 +
 src/jvmMain/kotlin/afield/simulation/flow/TileAtlasLayout.kt                |    13 +
 src/jvmMain/kotlin/afield/simulation/petri/PetriEditorExtension.kt          |    10 +
 src/jvmMain/kotlin/afield/simulation/petri/PetriMusic.kt                    |    82 +-
 src/jvmMain/kotlin/afield/simulation/petri/PetriSmokeTest.kt                |     2 +
 src/jvmMain/kotlin/afield/simulation/petri/PetriVisual.kt                   |    71 +-
 src/jvmMain/kotlin/afield/simulation/physics/pressure/Mechanics.kt          |    10 +
 src/jvmMain/kotlin/afield/simulation/physics/pressure/MinimumSearch.kt      |    10 +
 src/jvmMain/kotlin/afield/simulation/physics/pressure/Model.kt              |     9 +
 src/jvmMain/kotlin/afield/simulation/physics/pressure/ShapeAudit.kt         |     8 +
 src/jvmMain/kotlin/afield/simulation/physics/pressure/Solver.kt             |    11 +
 src/jvmMain/kotlin/afield/study/build/BoxStudyBuilds.kt                     |     9 +
 src/jvmMain/kotlin/afield/study/build/StudyBuildHelpers.kt                  |     9 +
 src/jvmMain/kotlin/afield/study/build/StudyBuilds.kt                        |    13 +
 src/jvmMain/kotlin/afield/study/evidence/StudyEvidence.kt                   |    10 +
 src/jvmMain/kotlin/afield/study/evidence/StudyReport.kt                     |     7 +
 src/jvmMain/kotlin/afield/study/search/StudyVariants.kt                     |     9 +
 src/jvmMain/kotlin/afield/study/source/LiveStudy.kt                         |    10 +
 src/jvmMain/kotlin/afield/study/source/SourceBackedBlock.kt                 |    11 +
 src/jvmMain/kotlin/afield/study/source/SourceFramesStudy.kt                 |    10 +
 src/jvmMain/kotlin/afield/study/source/StudyFrames.kt                       |     8 +
 src/jvmMain/kotlin/afield/study/ui/StudyKnob.kt                             |    12 +
 src/jvmMain/kotlin/afield/study/ui/StudyReportView.kt                       |    10 +
 src/jvmMain/kotlin/afield/tools/automation/AgentWorktreeManager.kt          |    10 +
 src/jvmMain/kotlin/afield/tools/automation/operation_transcript.kt          |    11 +
 src/jvmMain/kotlin/afield/tools/automation/transcript.kt                    |    10 +
 src/jvmMain/kotlin/afield/tools/docs/AgentTranscriptLayoutGuard.kt          |    22 +-
 src/jvmMain/kotlin/afield/tools/docs/BoxOutputChannelGuard.kt               |    22 +-
 src/jvmMain/kotlin/afield/tools/docs/DocsSearch.kt                          |     6 +-
 src/jvmMain/kotlin/afield/tools/docs/ElectronCaptureBrowser.kt              |     2 +
 src/jvmMain/kotlin/afield/tools/docs/KDocAttachmentGuard.kt                 |     8 +
 src/jvmMain/kotlin/afield/tools/docs/KotlinSourcePartition.kt               |     6 +-
 src/jvmMain/kotlin/afield/tools/docs/ShaderBindingDocs.kt                   |     7 +
 src/jvmMain/kotlin/afield/tools/docs/SourcePageGenerator.kt                 |    10 +-
 src/jvmMain/kotlin/afield/tools/docs/StdlibReferenceGenerator.kt            |   220 +-
 src/jvmMain/kotlin/afield/tools/docs/doc.kt                                 |    16 +-
 src/jvmMain/kotlin/afield/tools/docs/index/KDocExtractor.kt                 |    10 +
 src/jvmMain/kotlin/afield/tools/docs/screenshot.kt                          |    15 +
 src/jvmMain/kotlin/afield/ui/controls/ControlLaw.kt                         |     9 +
 src/jvmMain/kotlin/afield/ui/controls/EffectiveParameter.kt                 |    10 +
 src/jvmMain/kotlin/afield/ui/layout/BoxLayout.kt                            |     2 +
 src/jvmMain/kotlin/afield/ui/layout/D2Layout.kt                             |     2 +
 src/jvmMain/kotlin/afield/ui/layout/alignment.kt                            |    26 +-
 src/jvmMain/kotlin/afield/ui/notation/DarkThemeColour.kt                    |     6 +
 src/jvmMain/kotlin/afield/ui/notation/Notation.kt                           |    12 +
 src/jvmMain/kotlin/afield/ui/notation/NotationScene.kt                      |    10 +
 src/jvmMain/kotlin/afield/ui/notation/NotationServer.kt                     |    10 +
 src/jvmMain/kotlin/afield/ui/output/LastRunNotes.kt                         |     4 +
 src/jvmMain/kotlin/afield/ui/output/LineNotePictures.kt                     |     4 +
 src/jvmMain/kotlin/afield/ui/output/LineNoteTools.kt                        |     7 +
 src/jvmMain/kotlin/afield/ui/output/LineNotes.kt                            |     6 +
 src/jvmMain/kotlin/afield/ui/output/LiveOutputIframe.kt                     |     7 +
 src/jvmMain/kotlin/afield/ui/output/MarkdownOutput.kt                       |     8 +
 src/jvmMain/kotlin/afield/ui/output/NumericFormatting.kt                    |     6 +
 src/jvmMain/kotlin/afield/ui/output/OutputRequests.kt                       |     4 +
 src/jvmMain/kotlin/afield/ui/output/ShaderTransitionNotes.kt                |     4 +
 src/jvmMain/kotlin/afield/ui/output/callbacks.kt                            |     6 +
 src/jvmMain/kotlin/afield/ui/output/decorations.kt                          |     7 +
 src/jvmMain/kotlin/afield/ui/output/outputs.kt                              |    11 +
 src/jvmMain/kotlin/afield/ui/output/templates/replacements.kt               |     8 +
 src/jvmMain/kotlin/afield/ui/output/terminalOutput.kt                       |     7 +
 src/jvmMain/kotlin/afield/ui/visualization/DataFrameVisualizerSmokeTest.kt  |     1 +
 src/jvmMain/kotlin/afield/ui/visualization/GeometryFinding.kt               |     5 +
 src/jvmMain/kotlin/afield/ui/visualization/GeometryReviewSurface.kt         |     5 +
 src/jvmMain/kotlin/afield/ui/visualization/SignalViewer.kt                  |    10 +
 src/jvmMain/kotlin/afield/ui/visualization/intervalPicture.kt               |     6 +
 src/jvmMain/kotlin/afield/ui/visualization/someSkiko.kt                     |     8 +
 src/jvmMain/kotlin/afield/ui/visualization/visualizers.kt                   |    11 +-
 src/jvmTest/kotlin/afield/tools/docs/SymbolAnchorTest.kt                    |   137 +
 639 files changed, 42754 insertions(+), 32509 deletions(-)

The end of the landing log:

OK

=== tools.test_verification_runtime
...................
----------------------------------------------------------------------
Ran 19 tests in 1.358s

OK

=== tools.test_whisper_worker
..
----------------------------------------------------------------------
Ran 2 tests in 0.196s

OK

Python tool test suites:
  tools.test_agent_check_parse                       2 passed /   2 run
  tools.test_agents_md                               4 passed /   4 run
  tools.test_ai_chat_persistence                     0 passed /   0 run
  tools.test_ai_tool_usage                           5 passed /   5 run
  tools.test_ai_usage                                3 passed /   3 run
  tools.test_automation_workspace                   10 passed /  10 run
  tools.test_backdoor_managed_startup               20 passed /  20 run
  tools.test_build_dependency_rules                  3 passed /   3 run
  tools.test_build_zensical_run                     13 passed /  13 run
  tools.test_build_zensical_workspace_markdown       6 passed /   6 run
  tools.test_canonical_graphics_index                9 passed /   9 run
  tools.test_capture_transaction                    13 passed /  13 run
  tools.test_check_box_idioms                       22 passed /  22 run
  tools.test_check_doc_assets                       19 passed /  19 run
  tools.test_check_doc_offline_links                16 passed /  16 run
  tools.test_check_doc_site_links                   44 passed /  45 run, 1 skipped
  tools.test_check_module_imports                   14 passed /  14 run
  tools.test_check_native_selfcontained             20 passed /  20 run
  tools.test_check_offline_assets                   13 passed /  13 run
  tools.test_check_staged_generated_artifacts       41 passed /  41 run
  tools.test_check_study_tools                       7 passed /   7 run
  tools.test_check_test_tree_writes                 32 passed /  32 run
  tools.test_completion_sidecar                     13 passed /  13 run
  tools.test_conversation_demos                      9 passed /   9 run
  tools.test_conversation_packagers                  4 passed /   4 run
  tools.test_demo_build_scripts                      3 passed /   3 run
  tools.test_discover_field_tests                   16 passed /  16 run
  tools.test_docs_capture_audit                     34 passed /  34 run
  tools.test_docs_duplicate_embeds                   3 passed /   3 run
  tools.test_docs_hooks_capture_sources              3 passed /   3 run
  tools.test_docs_hooks_citations                   14 passed /  14 run
  tools.test_docs_hooks_fence_markers                5 passed /   5 run
  tools.test_docs_hooks_inline_code                  9 passed /   9 run
  tools.test_docs_hooks_repo_source_links           12 passed /  12 run
  tools.test_docs_native_prerequisites              36 passed /  36 run
  tools.test_docs_notes                             66 passed /  66 run
  tools.test_docs_runner_completion_backend         19 passed /  19 run
  tools.test_docs_runner_failure_reporting          27 passed /  27 run
  tools.test_docs_runner_graphics_launch             7 passed /   7 run
  tools.test_docs_runner_ui_clip                     5 passed /   5 run
  tools.test_docs_runner_window_outputs              4 passed /   4 run
  tools.test_docs_voice_detectors                   14 passed /  14 run
  tools.test_execution_receipts                      4 passed /   4 run
  tools.test_expected_test_failures                  9 passed /   9 run
  tools.test_export_particle_video_docs              2 passed /   2 run
  tools.test_field_agent                             3 passed /   3 run
  tools.test_field_backdoor                        117 passed / 117 run
  tools.test_field_inspection                        5 passed /   5 run
  tools.test_field_kit                              17 passed /  17 run
  tools.test_field_live_session_reaping              4 passed /   4 run
  tools.test_field_python_env                        4 passed /   4 run
  tools.test_field_sh_launcher                      38 passed /  39 run, 1 skipped
  tools.test_fieldcv                                15 passed /  15 run
  tools.test_guard_corpora                           5 passed /   5 run
  tools.test_inventory_box_code                     12 passed /  12 run
  tools.test_land                                   31 passed /  31 run
  tools.test_learned_shader_build                    7 passed /   7 run
  tools.test_loc_history                            22 passed /  24 run, 2 skipped
  tools.test_merge_regen                             6 passed /   6 run
  tools.test_native_download_cache                   3 passed /   3 run
  tools.test_neural_docs_gating                      6 passed /   6 run
  tools.test_open_dlss_nr                            6 passed /   6 run
  tools.test_oscquery_docs_ports                     3 passed /   3 run
  tools.test_particle_tracker                        4 passed /   4 run
  tools.test_prune_gradle_daemon_logs                2 passed /   2 run
  tools.test_ray_tracing_docs                       10 passed /  10 run
  tools.test_reap_merged_worktrees                  73 passed /  73 run
  tools.test_refactor                               57 passed /  57 run
  tools.test_refactor_pass                          20 passed /  20 run
  tools.test_refactor_session                       11 passed /  11 run
  tools.test_refactor_symbols                       15 passed /  15 run
  tools.test_repository_root_inventory               4 passed /   4 run
  tools.test_run_field_tests_native_prerequisites    2 passed /   2 run
  tools.test_run_field_tests_ordering               24 passed /  24 run
  tools.test_run_field_tests_output                  8 passed /   8 run
  tools.test_run_field_tests_port_ownership         14 passed /  14 run
  tools.test_run_field_tests_reaper                 34 passed /  34 run
  tools.test_runner_fence_image_widths               2 passed /   2 run
  tools.test_studies_page                            3 passed /   3 run
  tools.test_sync_field_audio_docs                   2 passed /   2 run
  tools.test_verification_environment               37 passed /  37 run
  tools.test_verification_lock_gradle                7 passed /   7 run
  tools.test_verification_runtime                   19 passed /  19 run
  tools.test_whisper_worker                          2 passed /   2 run
  TOTAL                                            1288 passed / 1292 run, 4 skipped

> Task :checkBoxIdioms
warning: `VIRTUAL_ENV=/Users/marc/field4/.venv` does not match the project environment path `.venv` and will be ignored; use `--active` to target the active environment instead
check_box_idioms: clean (27 file(s))

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
tree-write guard: baseline of 11536 tracked file(s), 0 already dirty -> /Users/marc/field4-worktrees/lander/build/test-tree-writes/baseline.json

> Task :citationGates
WARNING: Using incubator modules: jdk.incubator.vector
citationGates: running ServerSurfaceLedgerTest
citationGates: [1/14] ServerSurfaceLedgerTest SUCCESS in 3.3s
citationGates: running CloseNodeCitationDriftTest
citationGates: [2/14] CloseNodeCitationDriftTest SUCCESS in 1.6s
citationGates: running CitationContractTest
citationGates: [3/14] CitationContractTest SUCCESS in 3.3s
citationGates: running DerivedArithmeticContractTest
citationGates: [4/14] DerivedArithmeticContractTest SUCCESS in 4.4s
citationGates: running DocsFenceTreatmentTest
citationGates: [5/14] DocsFenceTreatmentTest SUCCESS in 0.3s
citationGates: running DocsGatesWiringTest
citationGates: [6/14] DocsGatesWiringTest SUCCESS in 0.1s
citationGates: running DocumentedApiEvaluationTest
citationGates: [7/14] DocumentedApiEvaluationTest SUCCESS in 8.6s
citationGates: running GateInputDeclarationTest
citationGates: [8/14] GateInputDeclarationTest SUCCESS in 0.1s
citationGates: running ImpossibilityClaimContractTest
citationGates: [9/14] ImpossibilityClaimContractTest SUCCESS in 8.0s
citationGates: running LanguagesCategoryTest
citationGates: [10/14] LanguagesCategoryTest SUCCESS in 1.0s
citationGates: running PersonalBoundaryTest
citationGates: [11/14] PersonalBoundaryTest SUCCESS in 6.1s
citationGates: running ProseNumeralContractTest
citationGates: [12/14] ProseNumeralContractTest SUCCESS in 4.6s
citationGates: running SmallLanguageAnatomyTest
citationGates: [13/14] SmallLanguageAnatomyTest SUCCESS in 0.6s
citationGates: running TranscriptStackAutoCompileTest
citationGates: [14/14] TranscriptStackAutoCompileTest SUCCESS in 0.4s

> Task :checkTestTreeWrites
warning: `VIRTUAL_ENV=/Users/marc/field4/.venv` does not match the project environment path `.venv` and will be ignored; use `--active` to target the active environment instead
tree-write guard: citationGates left the working tree as it found it.

FAILURE: Build failed with an exception.

* What went wrong:
Execution failed for task ':checkKDocAttachment' (registered in build file 'build.gradle.kts').
> Process 'command '/Users/marc/.field4/toolchains/graalvm-community-25.4.4.1.1+1.1/Contents/Home/bin/java'' finished with non-zero exit value 1

* Try:
> Run with --stacktrace option to get the stack trace.
> Run with --info or --debug option to get more log output.
> Run with --scan to get full insights from a Build Scan (powered by Develocity).
> Get more help at https://help.gradle.org.

BUILD FAILED in 7m 33s
32 actionable tasks: 9 executed, 23 up-to-date

  A generated artifact is stale after this merge.
  It was not your commit that broke it and no hook ran on the clone that did:
  .git/hooks is per-clone and Git never transports it.

  Reproduce:  ./gradlew checkKDocAttachment checkPythonTools checkBoxIdioms citationGates
  The failing task names the artifact and the command that regenerates it.
  Regenerate and commit that, or `main` is red until someone else finds it.

  Set FIELD_SKIP_MERGE_GUARD=1 to skip this check.

merge_regen: a guard failed on the batch

land: 2026-10-18T04:05:32+02:00 red; asking the fixer (up to 15 min) about docs/kdoc-symbol-links-2

What the fixer did on recent reds (for calibration):
- declined: The branch rewrote rp_renderer/rp_chain/rp_modes code in frame_history_reprojection/build.yaml without updating their *_code_.kt sidecars, and resyncing them is about 168 changed lines, over the fixer's 60-line limit.
- declined: The branch rewrote box code in frame_history_reprojection and pattern_condense build.yaml without regenerating five box sidecars, and resyncing them is well over the 60-line limit and needs the build scripts re-run.
- fixed: The branch's KDoc rewrite removed three citation anchors in DataFramePluginSpike.kt and left the Function conversation copy stale; I re-anchored the citations and re-ran the packager, and both tests pass, but the missing tools/check_kdoc_mirror.py red was already on main and is not fixed.
- declined: checkPythonTools fails only because tools/check_kdoc_mirror.py is missing, a red already on main that neither box-code-readability branch touches.
- fixed: The KDoc rewrite of DataFramePluginSpike.kt removed three citation anchors used by dev_docs/DATAFRAME_PLUGIN.md, so I pointed them at the matching lines of the new KDoc and CitationContractTest passes.
- declined: checkPythonTools fails on two stale conversation pages from different branches, stackauto from box-code-readability-33/34 and the Function page from docs/kdoc-blast's FunctionStudyTools.kt KDoc, so no single-branch fix turns it green.
- declined: no verdict line
- declined: The red has two causes from two branches: checkKDocAttachment flags a stacked KDoc in FabricatePassGraph.kt (codex/box-code-readability-41) and one in StdlibReferenceGenerator.kt (docs/kdoc-symbol-links-2), while docs/kdoc-symbol-links-2 also removed three citation anchors from DataFramePluginSpike.

This replay's checkout cannot build: the `gradle` command is not available, so verify by reading the check's source and rerunning any `tools/` Python check that covers it.
