# Workspace media in slideshow notes

Slideshow HTML/Markdown can refer to a file beneath the configured workspace as
`/project/<relative-path>`:

```markdown
![Diagram](/project/images/diagram.png)
```

The route is implemented beside `route("/project")` in
`src/jvmMain/kotlin/afield/Server.kt`. It resolves the request relative to `home`, canonicalizes
both paths, requires a regular file, and rejects a canonical path outside the workspace. Ktor's
`respondFile` supplies the response; the route does not maintain an image-format allowlist.

The slideshow-specific example is `Box.slideShowWithImagesExample()` in
`src/jvmMain/kotlin/afield/examples/slideshow_example.kt`. General slideshow authoring belongs in
`public/docs/interactive_guides.md` and `dev_docs/SLIDESHOW_FEATURE.md`.

Verification should cover a root file, a nested file, a missing file, `..` traversal, a sibling
directory whose name begins with the workspace name, and a symlink escaping the workspace. Browser
layout, alt text, and sizing are ordinary HTML concerns and are not part of the server contract.
