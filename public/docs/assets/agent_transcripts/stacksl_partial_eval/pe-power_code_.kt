import afield.lang.stacksl.cpu.*

// n is known, x is not: the loop runs while specializing and only the multiplies remain.
val exponent = 5
val shader =
    """
    #version 450
    layout(location = 0) in vec2 fragUV;
    layout(location = 0) out vec4 outColor;
    float power(float x, int n) {
        float r = 1.0;
        for (int i = 0; i < n; i++) r *= x;
        return r;
    }
    void main() { outColor = vec4(power(fragUV.x, $exponent), 0.0, 0.0, 1.0); }""".trimIndent()

val residual = GlslPartialEvaluator.specialize(shader)
markdown("residual", "```glsl\n" + residual.toGlsl() + "```")
