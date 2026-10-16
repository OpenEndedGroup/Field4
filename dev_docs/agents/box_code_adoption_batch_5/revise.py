"""Apply reviewed, comment-only teaching corrections with exact-source assertions."""
from pathlib import Path
import json
ROOT=Path(__file__).resolve().parents[3]
CHANGES=[]
def edit(path,old,new,finding):
    p=ROOT/path;s=p.read_text();assert old in s,(path,old)
    p.write_text(s.replace(old,new))
    CHANGES.append({'path':path,'finding':finding,'old':old,'new':new})
edit('dev_docs/SLIDER_EXAMPLES.kt','// src/jvmMain/kotlin/afield/outputs.kt;','// src/jvmMain/kotlin/afield/ui/output/outputs.kt;','3e079fb5b468-01')
edit('dev_docs/agents/artifacts/datalog_aggregate_proofs/aggregate_proofs.yaml','// Before 2026-08-30 every one of these answered "no proof reconstructed".','// Compare sum/count contributors with a maximum joined back to its earner.','828653ef6c8f-01')
edit('dev_docs/agents/demos/alife_evolution/evolve_lenia_code_.kt','// The lineage, left to right.','// The record-holder sequence, left to right.','26600b760ed2-01')
p='dev_docs/agents/demos/alife_script/one_agent_code_.kt'
edit(p,'// hoisted: see the note at the bottom','// One fixed target, reused at each steering step.','e6ef02e482cb-01')
edit(p,'// Why `centre` is hoisted out of the steering block: `point()` belongs to the behaviour scope,\n// and inside `moveFor { }` the receiver is the steering builder, which does not expose it. Written\n// inline it fails to compile with "cannot be called in this context with an implicit receiver" —\n// so build targets where the program can see them, and pass them in.','// Keep the target outside the repeating block so its position is easy to edit.\n// Steering blocks also accept point(...); here every step reuses the same target.','e6ef02e482cb-01')
old="// The ticks are not this box's problem, and they are not the renderer's either:\n// `niceTicks` is one public copy of Heckbert's algorithm that five files each had\n// privately. The plot frame it landed on is readable too."
new='// Query readable axis ticks and the plot bounds when placing your own annotations.'
edit('dev_docs/agents/demos/chart_primitive/chart_one_call_code_.kt',old,new,'2faae815ec59-01')
p='dev_docs/agents/demos/chart_primitive/build.yaml';edit(p,old.replace('\n','\n          '),new,'2faae815ec59-01')
p='dev_docs/agents/demos/completion_rank_at_scale/classify_code_.kt'
s=(ROOT/p).read_text();start=s.index('// Last night');end=s.index('//\n// What you should notice',start)
edit(p,s[start:end],'// A candidate\'s category comes from its declaration container, so names shared by\n// unrelated types do not borrow each other\'s categories. Compare that result with\n// CommonMemberPrior.rankOf for the chosen receiver.\n','529867af68d6-02')
edit(p,"// that is the fix working:","// this is expected:",'529867af68d6-02')
edit('dev_docs/agents/demos/flow_grain/pressure.comp','    float sum =','    // One four-neighbor Jacobi pressure step; swap source/target between dispatches.\n    float sum =','5e4f699ab6f2-04')
edit('dev_docs/agents/demos/midi_clip/midi_write_code_.kt','// one cycle is one bar of 4/4 by default, so this is eight bars-worth of quarter notes\n// at 104 BPM. Tidal\'s octave convention applies: c5 is middle C, MIDI 60.','// beatsPerCycle = 8 makes two cycles span 16 beats: four bars of 4/4 at 104 BPM.\n// The lead has eight notes per cycle. Tidal\'s octave convention makes c5 MIDI 60.','5785aee81323-01')
p='dev_docs/agents/demos/path_tracer_instrument/pt_instrument_code_.kt';s=(ROOT/p).read_text();end=s.index('import ')
edit(p,s[:end],'// Re-run to inspect the live tracer, including its accumulation generation.\n// The up row appears when the declared camera up differs from the effective\n// right × forward basis used to make the image.\n','c6c74dbec00b-01')
p='dev_docs/agents/demos/path_tracer_instrument/pt_plot_code_.kt';s=(ROOT/p).read_text();end=s.index('import ')
edit(p,s[:end],'// Samples against wall seconds, with labelled rules where accumulation restarted.\n// Run the swap or restore box, then re-run this convergence box to see the reset.\n','c6c74dbec00b-02')
p='dev_docs/agents/demos/path_tracer_instrument/pt_restore_code_.kt';s=(ROOT/p).read_text();end=s.index('import ')
edit(p,s[:end],'// Restore the packaged integrator after the swap box changes the live pipeline.\n// The scene, camera and accumulation buffers are reused.\n','c6c74dbec00b-02')
edit(p,'// changing. Re-run box 2 to see the reset land on the convergence curve as a labelled rule.','// changing. Re-run the convergence box to see the reset as a labelled rule.','c6c74dbec00b-02')
edit('dev_docs/agents/demos/read_only_session/aff1dff8-9c85-4fc1-9c9a-de7cae2ce37b_code_.kt','// Before 2026-09-07 the read-only case wrote the file anyway — including on the very\n// first *read*, because with no file yet `saved {}` computes the default and writes it.','// With no file yet, the first read computes the default. Read-only mode leaves\n// that default unwritten too. The assignment below updates the live count.','7cf9b84db8a9-01')
edit('dev_docs/agents/demos/stackauto_conversation/polytope_source_vertex_.glsl','// .y shrinks it about its own centre (0 keeps it whole, 1 shrinks it to a point).','// .y shrinks about the face centre: 0 keeps it whole; values >= 0.95 leave 5% size.','38f740826e40-03')
(ROOT/'dev_docs/agents/box_code_adoption_batch_5/comment_changes.json').write_text(json.dumps(CHANGES,indent=2)+'\n')
print(len(CHANGES),'comment edits')
