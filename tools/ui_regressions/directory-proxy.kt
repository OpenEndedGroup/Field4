import java.io.File
import java.util.Base64

// The runner owns this document. Never use the user's workspace or a pretend path.
val previousRoot = thisBox.getPropertyRaw("directory_proxy_fixture_root") as? String
val fixtureRoot = if (previousRoot == null) {
    File(inside.filename, "ReferenceContext").also { root ->
        root.mkdirs()
        File(root, "docs").mkdirs()
        File(root, "src").mkdirs()
        File(root, "generated").mkdirs()
        File(root, "build").mkdirs()
        File(root, ".gitignore").writeText("ignored.txt\n")
        File(root, "src/Example.kt").writeText("// DirectoryProxyNeedle source\nval example = 4\n")
        File(root, "ignored.txt").writeText("DirectoryProxyNeedle ignored secret\n")
        File(root, "generated/Generated.kt").writeText("// DirectoryProxyNeedle generated secret\n")
        File(root, "build/output.txt").writeText("DirectoryProxyNeedle build secret\n")
        File(root, ".hidden.txt").writeText("DirectoryProxyNeedle hidden secret\n")
        thisBox.setPropertyRaw("directory_proxy_fixture_root", root.absolutePath)
    }
} else File(previousRoot)
File(fixtureRoot, "docs/readme.md").writeText(
    if (previousRoot == null) "# DirectoryProxyNeedle initial text\n"
    else "# DirectoryProxyNeedle live updated text\n"
)
val encodedRoot = Base64.getEncoder().encodeToString(fixtureRoot.absolutePath.toByteArray(Charsets.UTF_8))
display("fixture", "<div id='directory-proxy-fixture' data-root-base64='$encodedRoot'>Real directory fixture ready</div>")
