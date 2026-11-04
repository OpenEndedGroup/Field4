# Auto-register pre-existing WiVRn Tailscale listener

- **Handoff:** `16619686-b436-41a3-bafd-7bc507c445b3` (project)
- **Filed from:** https://openendedgroup.com/field4/field_vr_ide/
- **Filed by:** human, 2026-11-02 10:30
- **Close with:** `python tools/docs_notes.py handoff-close 16619686 "what happened"`

Rule 25 sharp edge in public/docs/field_vr_ide.md, section "A remote browser on the same document":

> If you installed the helper before hostname registration was added, register the existing private
> listener as your ordinary user, then restart Field. This does not change the listener:
> `python3 tools/setup_wivrn_input.py --register-serve-host 8443`

This is a migration workaround for installs predating hostname registration. Fix in code: have
Field (or `setup_wivrn_input.py` on any invocation) detect an existing private Tailscale Serve
listener that is not registered with the browser-origin guard and register it, or report the
exact fix in the toolbar/startup status. Delete the paragraph and its fence in the same change.
