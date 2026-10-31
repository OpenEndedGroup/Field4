// A macro is YAML. This one is what the recorder writes when you make a box with code,
// make another, and connect them with `g`.
val stamp =
    """
    name: stamp
    operations:
      - create:
          box: stamp_source
          name: Source
          frame: { x: 520.0, y: 80.0, w: 300.0, h: 100.0 }
          properties:
            code: |
              println("source")
      - create:
          box: stamp_sink
          name: Sink
          frame: { x: 880.0, y: 80.0, w: 300.0, h: 100.0 }
      - connect:
          parent: stamp_source
          child: stamp_sink
    """
        .trimIndent()
val saved = OperationTranscript.writeMacro(thisBox.inside.filename, "stamp", stamp)
println("${saved.message} ${saved.operationCount} operations")
