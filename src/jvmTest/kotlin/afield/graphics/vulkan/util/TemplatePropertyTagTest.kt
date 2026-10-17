package afield.graphics.vulkan.util

import kotlin.test.Test
import kotlin.test.assertEquals

/** `{% property "name" %}` is the spelling; `{% include "name" %}`, which saved documents use, expands the same. */
class TemplatePropertyTagTest {
    private val includes = IncludeResolver { path, _, _ -> "// from $path\nfloat shared = 1.0;" }

    @Test fun bothSpellingsExpandIdentically() {
        val property = runTemplate("void a();\n{% property \"helpers\" %}\nvoid b();", Resolver { null }, includes)
        val include = runTemplate("void a();\n{% include \"helpers\" %}\nvoid b();", Resolver { null }, includes)
        assertEquals(property, include)
        assertEquals(true, "float shared = 1.0;" in property, property)
    }
}
