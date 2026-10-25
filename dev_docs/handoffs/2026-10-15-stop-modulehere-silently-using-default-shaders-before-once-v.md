# Stop moduleHere silently using default shaders before once.vertex

- **Handoff:** `55fa7d2d-f732-4882-af6f-3d0b6eeee962` (project)
- **Filed from:** https://openendedgroup.com/field4/running_code/
- **Filed by:** human, 2026-10-15 13:41
- **Close with:** `python tools/docs_notes.py handoff-close 55fa7d2d "what happened"`

`public/docs/running_code.md` (sections "Generated GLSL accessors" and "The `//%-` fragment break") explains an ordering trap:

> **Without** the `//%-`, both are one compilation unit. All of its macros expand first, then it runs. So `moduleHere` expands — reading the box's shaders, finding none, seeding the **default `in vec3 pos`** shader and generating accessors for *that* — *before* `once.vertex` has ever run. The result: the module uses the wrong shader ...

and "`once.vertex` runs at *runtime*, though, and `moduleHere` reads the property at *expansion time*."

A silent wrong result (rule 25). Fix in code: have the `moduleHere` macro recognise `once.vertex { }` / `once.fragment { }` earlier in the same fragment (split automatically, or evaluate them first), or at minimum report an error when it seeds defaults while such a call precedes it. Then reduce the page's explanation to the general use of `//%-` (the DataFrame case) and delete the trap description in the same change.
