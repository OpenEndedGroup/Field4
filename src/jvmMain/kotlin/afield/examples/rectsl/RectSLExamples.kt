package afield.examples.rectsl

import afield.rectsl.RectAnimationOracle

/*
FIELD_TEST
id: rectsl.examples.smoke
runner: jvm-main
entrypoint: afield.examples.rectsl.RectSLExamples
tags: [smoke, examples, rectsl]
timeoutSec: 30
expect:
  exitCode: 0
  stdoutContains:
    - "rectsl examples passed"
*/
object RectSLExamples {
    val minimalMirror: String = """
        #rectsl
        wall 30 4 meters
        duration 10
        fps 60

        "left" rect
          -5 0 xy
          1 square
          0xff3300 solid fill
          '( 0 -5   5 0   10 5 ) x
        ;

        dup "right" rename
          mirror-x
          0x3366ff solid fill
        ;
    """.trimIndent()

    val layeredTracks: String = """
        #rectsl
        wall 16 9 meters
        duration 6
        fps 30

        linear-track "rise" '( 0 -3   3 0   6 3 )
        hold-track "pulse" '( 0 0.2   2 1.0   4 0.45 )

        "bar-template" rect
          0 -2 xy
          0.5 1.4 wh
          "rise" track-ref y
          "pulse" track-ref smooth alpha
          0xeeeeee solid fill
        ;

        8 '(
          "bar-template" select
            clone
            i layer
            i 0.1 * z
            i 3.5 - 0.8 * -2 xy
        ) repeat
    """.trimIndent()

    val groupsAndStackSLMaterial: String = """
        #rectsl
        wall 10 10 meters
        duration 8
        fps 30

        material petals-tint '(
          uv 4.0 * sin abs
        )

        "orbit" group
          "cell" rect
            0 0 xy
            0.75 square
            "petal" tag
            '( 0 0   4 3.14159   8 6.28318 ) rot
            0xffffff solid fill
          ;

          0 6 '(
            "cell" select
              clone
              i 2 * 5 - 0 xy
          ) range
        end-group

        "orbit" select descendants
          "petal" tagged
          "petals" group-selected
          selected descendants rects '(
            0.85 alpha
            petals-tint material
          ) each
        ;
    """.trimIndent()

    @JvmStatic
    fun main(args: Array<String>) {
        listOf(
            "minimalMirror" to minimalMirror,
            "layeredTracks" to layeredTracks,
            "groupsAndStackSLMaterial" to groupsAndStackSLMaterial,
        ).forEach { (name, source) ->
            val result = RectAnimationOracle.compile(source)
            check(result.ok && result.program != null) {
                "$name failed:\n" + result.diagnostics.joinToString("\n")
            }
        }
        println("rectsl examples passed")
    }
}
