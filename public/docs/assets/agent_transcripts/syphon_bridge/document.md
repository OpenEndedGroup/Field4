# Field Document: syphon_bridge

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 3 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_session" "fileManagerAccessHistory"]}
```

## 1. Syphon — receive a server's frames

<!-- field-box id="syphon_receive" index="0" -->

```edn [field-box id="syphon_receive" role="properties"]
{"id" "syphon_receive" "index" 0 "name" "Syphon — receive a server's frames" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "Syphon — receive a server's frames" "_tombstone" false "frame" #afield/rect [80.0 960.0 340.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "syphon_receive_code_.kt" "lines" 12 "characters" 316 "sha256" "ebf047738986060e7f1c0f52a4c92a725563adf3978c691a6064ea54c8ad099b"}]}
```

```kotlin [field-property box="syphon_receive" name="code" path="syphon_receive_code_.kt"]
import afield.syphon.*

// without the library this stops here and says how to build it
check(Syphon.isAvailable) { Syphon.describe() }

// a server's name, app name or uuid; "" takes the first one announced
val inp = syphonClient("Field pattern")

loop {
    inp.png()?.let { display("main", it) }
    delay(250)
}
```

## 2. Syphon — publish an animated picture

<!-- field-box id="syphon_publish" index="1" -->

```edn [field-box id="syphon_publish" role="properties"]
{"id" "syphon_publish" "index" 1 "name" "Syphon — publish an animated picture" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "Syphon — publish an animated picture" "_tombstone" false "frame" #afield/rect [80.0 520.0 340.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "syphon_publish_code_.kt" "lines" 33 "characters" 1126 "sha256" "20e9fcad2f24f8c60e651d993d108dfee2c4355948f9ef632f2ab405189219a2"}]}
```

```kotlin [field-property box="syphon_publish" name="code" path="syphon_publish_code_.kt"]
import afield.syphon.*
import java.awt.BasicStroke
import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage

// without the library this stops here and says how to build it
check(Syphon.isAvailable) { Syphon.describe() }

// other applications list this as "java — Field pattern" (or Field's app name)
val out = syphonServer("Field pattern")
val img = BufferedImage(640, 360, BufferedImage.TYPE_INT_ARGB)
var t = 0.0

loop {
    t += 1.0 / 60
    val g = img.createGraphics()
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    g.color = Color(20, 24, 40)
    g.fillRect(0, 0, 640, 360)
    g.stroke = BasicStroke(6f)
    for (i in 0 until 12) {
        val a = t + i * Math.PI / 6
        g.color = Color.getHSBColor((i / 12.0 + t * 0.05).toFloat() % 1f, 0.7f, 1f)
        val x = 320 + (cos(a) * 140).toInt()
        val y = 180 + (sin(a * 2) * 110).toInt()
        g.drawOval(x - 30, y - 30, 60, 60)
    }
    g.color = Color.WHITE
    g.drawString("Field over Syphon  frame ${out.framesPublished}", 16, 340)
    g.dispose()
    out.publish(img)
}
```

## 3. Syphon — servers on this machine

<!-- field-box id="syphon_servers" index="2" -->

```edn [field-box id="syphon_servers" role="properties"]
{"id" "syphon_servers" "index" 2 "name" "Syphon — servers on this machine" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "Syphon — servers on this machine" "_tombstone" false "frame" #afield/rect [80.0 80.0 340.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "syphon_servers_code_.kt" "lines" 12 "characters" 297 "sha256" "f91617e943c580f7e90c2f1f4c798efa3fad8071fc9f051e2f45786760b796fa"}]}
```

```kotlin [field-property box="syphon_servers" name="code" path="syphon_servers_code_.kt"]
import afield.syphon.*

run {
if (!Syphon.isAvailable) {
    markdown(Syphon.describe())
    return@run
}

val servers = Syphon.servers(waitMs = 1000)
markdown(if (servers.isEmpty()) "No Syphon servers are running."
         else servers.joinToString("\n") { "- **${it.appName}** ${it.name}" })
}
```

