# Make the keyframe editor's Simplify slider keep linked lanes' key times

- **Handoff:** `f86b4a67-f74d-46c7-9682-e3e7b044f337` (project)
- **Filed from:** https://openendedgroup.com/field4/keyframe_editor/
- **Filed by:** human, 2026-10-16 02:37
- **Close with:** `python tools/docs_notes.py handoff-close f86b4a67 "what happened"`

Split from 94893ad9-35c4-46c7-8449-4da7ca8bd622, 2026-10-16. Code has `KeyframeEvaluator.simplifyTogether(curves, tolerance)`: the union of kept times, iterated until every lane keeps the same set, covered by `KeyframeEvaluatorTest.simplifyTogetherKeepsLinkedLanesOnTheSameKeyTimes`.

Still per lane: the editor's curve-menu Simplify slider (`simplifyKeys` / `planSimplify` in `src/commonMain/resources/keyframe_editor.js`). Each editor instance shows one curve, so a joint simplify needs the editors of linked lanes to coordinate: for example, lanes baked by one `bakeGesture` call could share a link id, and the slider would plan with the union of kept times pinned (`keep`/`asIs`) across the linked editors. Fix that, then say on `keyframe_editor.md` that the slider keeps linked lanes aligned.
