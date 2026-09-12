import java.io.File

afield.examples.DocumentGuideWorkout.prepare()

val guideRoot = File(inside.filename)
val guideUid = inside.boxes.values.first { it.name == "GuideTarget" }.id
File(guideRoot, "README.md").writeText("""
# A document with a guide

[Movement recipe](field://box/$guideUid?property=code&line=3)

[Read the details](docs/Details.md#turning)

[Run the demonstration](field://macro/guide_link_demo?scope=document)

## Try an edit

Open the source and change the pace. The guide stays beside the box.

```kotlin
val pace = 2.0
println(pace)
```

![An actual local image](guide-dot.svg)
""".trimIndent())
File(guideRoot, "docs").mkdirs()
File(guideRoot, "docs/Details.md").writeText("""
# Movement details

[Back to introduction](../README.md)

## Turning

[Open the movement box](field://box/$guideUid?property=code&line=3)

### Repeating

Suspend between phases so other boxes can continue.
""".trimIndent())
File(guideRoot, "guide-dot.svg").writeText("""<svg xmlns="http://www.w3.org/2000/svg" width="80" height="24"><circle cx="12" cy="12" r="8" fill="#48c9ff"/></svg>""")
File(guideRoot, "AGENTS.md").writeText("# Agent instructions — excluded from the guide catalogue")
File(guideRoot, "fixture_notes_.md").writeText("# Property sidecar — excluded from the guide catalogue")
val target = inside.boxes.values.first { it.id == guideUid }
target.setPropertyRaw("fragment", "// Link target\n// Another editable property\n// Focus this line\nvoid main() {}")
thisBox.setPropertyRaw("notes", """
# Links in box notes

[Open the fragment](field://box/$guideUid?property=fragment&line=3)

[Run from notes](field://macro/guide_link_demo?scope=document)
""".trimIndent())
display("ai-chat", "<<ai>>\n[Open the fragment from chat](field://box/$guideUid?property=fragment&line=3)\n\n[Run from chat](field://macro/guide_link_demo?scope=document)")
for ((macroName, scope) in listOf("guide_link_demo" to "document", "guide_link_scope_demo" to "workspace")) {
    check(afield.OperationTranscript.writeMacro(inside.filename, macroName, """
name: $macroName
operations:
  - execute:
      box: $guideUid
      property: code
      type: fragment
      code: |
        val count = (thisBox.getPropertyRaw("markdown_link_count") as? Number)?.toInt() ?: 0
        thisBox.setPropertyRaw("markdown_link_count", count + 1)
        thisBox.setPropertyRaw("notes", "$scope:" + (count + 1))
""".trimIndent(), scope = scope).success)
}
println("GUIDE_FIXTURE_READY")
