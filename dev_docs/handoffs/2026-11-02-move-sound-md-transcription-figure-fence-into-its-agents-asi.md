# Move sound.md transcription-figure fence into its Agents aside

- **Handoff:** `c260d58e-fd63-4d08-baed-378c38c4cc32` (project)
- **Filed from:** https://openendedgroup.com/field4/sound/
- **Filed by:** human, 2026-11-02 10:32
- **Close with:** `python tools/docs_notes.py handoff-close c260d58e "what happened"`

sound.md, last section: the aside

    ??? aside "Agents: regenerating the transcription figures"
        The three figures above come from the `synth.pat.transcription` smoke test:

is followed by a code fence at column 0, so the fence falls out of the collapsed aside and renders in the body text. Rule 26 says text for agents goes in the aside, but the fence is byte-pinned, so it cannot be indented in a prose pass. Indent the fence four spaces into the aside (updating any pinned fence bytes/captures), or replace the fence with inline code in the aside.
