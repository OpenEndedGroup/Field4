# Box copy and paste through the browser clipboard

The clipboard implementation is owned by
[`commands.kt`](../src/jsMain/kotlin/afield/commands.kt):

- `copySelectedBoxesToClipboard()` asks the server to serialize the selection and
  always writes EDN as `text/plain`.
- If the browser reports support for `application/edn` or
  `application/octet-stream`, the clipboard item also carries a `File` named
  `metadata.edn`.
- Any richer-clipboard failure falls back to `clipboard.writeText(...)`.
- `pasteBoxesFromClipboard()` prefers `application/edn`, then `text/plain`, then
  `readText()`.
- Pasted operation-transcript YAML runs as a macro; other text is sent to the
  server's `paste-boxes` handler as EDN.

The precise fallback and routing contract is documented as KDoc beside those
functions. Serialization and id remapping live in the `copy-boxes` and `paste-boxes`
handlers in [`Server.kt`](../src/jvmMain/kotlin/afield/Server.kt).

## What must be tested manually

Browser clipboard MIME support and desktop file-manager interoperability vary by
browser and operating system. Field therefore does not guarantee that copying a
selection will create a pasteable desktop file merely because `ClipboardItem` accepted
the `File` representation.

For a release check:

1. Copy selected boxes and paste them back into Field. Confirm the new ids are selected
   and the serialized properties survive.
2. Copy transcript YAML and paste it into Field. Confirm it is recognized and run rather
   than parsed as EDN.
3. On each supported browser/OS pair, attempt to paste into the desktop file manager.
   If `metadata.edn` appears, inspect its contents; if not, confirm ordinary Field paste
   still succeeds through `text/plain`.
4. Deny clipboard permission and confirm failure does not corrupt selection or document
   state.

There is currently no automated browser regression for this flow. Browser/file-manager
support claims should not be added here without a named tested version and platform.
