package afield

import afield.ai.glslSpecForProperty
import afield.four.modules.Introspection
import afield.four.modules.Introspected
import afield.four.modules.PrimitiveType
import afield.four.modules.ShaderKind
import afield.four.util.listGlobalShaderTemplateNames
import afield.stdlib._computeShader
import afield.stdlib._meshShader
import afield.stdlib._rayTracingShader
import afield.stdlib._shader

private data class GlslBounds(val start: Int, val end: Int, val text: String)

private data class GlslMemberInfo(
    val name: String,
    val type: String,
    val tail: String = "",
    val documentation: String = ""
)

private data class GlslSymbolInfo(
    val name: String,
    val type: String,
    val kind: String,
    val tail: String,
    val documentation: String = "",
    val members: List<GlslMemberInfo> = emptyList()
)

private data class GlslCompletionInventory(
    val symbols: List<GlslSymbolInfo>,
    val structures: Map<String, List<GlslMemberInfo>>,
    val introspectionCount: Int
)

private data class GlslReceiverAccess(
    val receiver: String,
    val prefix: String,
    val bounds: GlslBounds
)

object GlslCompletionSupport {
    fun isGlslProperty(property: String): Boolean =
        glslSpecForProperty(property) != null

    fun complete(box: Box, request: CompletionRequest): Completion {
        val code = request.allCode
        val position = request.position.coerceIn(0, code.length)
        val templateContext = templateContextAt(code, position)
        val tokenBounds = findIdentifierBoundsAt(code, position) ?: GlslBounds(position, position, "")
        val inventory = buildInventory(box, request.property, code)
        val activePrefix = findReceiverAccess(code, tokenBounds)?.prefix ?: tokenBounds.text

        val candidates = when (templateContext) {
            GlslTemplateContext.TAG -> templateTagCompletions()
            GlslTemplateContext.EXPRESSION -> {
                val receiver = findReceiverAccess(code, tokenBounds)
                if (receiver?.receiver == "shadertoy") templateShadertoyMemberCompletions()
                else templateExpressionCompletions(inventory)
            }
            null -> {
                val receiver = findReceiverAccess(code, tokenBounds)
                if (receiver != null) {
                    memberCompletions(receiver, inventory)
                } else {
                    rootCompletions(request.property, inventory)
                }
            }
        }

        val metadata = filterCompletionCandidates(candidates, activePrefix)
            .distinctBy { it.text }
            .sortedWith(compareBy<Metadata>({ completionSortGroup(it) }, { it.displayText.lowercase() }))
            .take(360)

        val bounds = findReceiverAccess(code, tokenBounds)?.bounds ?: tokenBounds
        return Completion(
            boundsStart = bounds.start,
            boundsEnd = bounds.end,
            metadata = metadata
        )
    }

    fun describeContext(box: Box, request: SymbolContextRequest): SymbolContextResponse {
        val code = request.allCode
        val position = request.position.coerceIn(0, code.length)
        val inventory = buildInventory(box, request.property, code)
        val templateContext = templateContextAt(code, position)

        if (templateContext != null) {
            val tag = if (templateContext == GlslTemplateContext.TAG) "{% ... %}" else "{{ ... }}"
            val detail = if (templateContext == GlslTemplateContext.TAG) {
                "Field shader template tag: if / else / endif, include, copyPC"
            } else {
                "Field shader interpolation: resolves against shaderTemplate and chained box context"
            }
            return SymbolContextResponse(
                symbol = tag,
                html = glslContextHtml(
                    heading = "Field GLSL template",
                    signature = tag,
                    detail = detail
                )
            )
        }

        val bounds = findIdentifierBoundsAt(code, position)
        if (bounds == null || bounds.text.isBlank()) {
            return stageSummaryResponse(request.property, inventory)
        }

        findReceiverAccess(code, bounds)?.let { receiver ->
            findMember(receiver, inventory)?.let { member ->
                return SymbolContextResponse(
                    symbol = member.name,
                    html = glslContextHtml(
                        heading = "GLSL member",
                        signature = "${member.type} ${receiver.receiver}.${member.name}",
                        detail = member.tail.ifBlank { member.documentation }
                    )
                )
            }
        }

        val symbol = inventory.symbols.firstOrNull { it.name == bounds.text }
            ?: builtinSymbolsForProperty(request.property).firstOrNull { it.name == bounds.text }

        if (symbol != null) {
            return SymbolContextResponse(
                symbol = symbol.name,
                html = glslContextHtml(
                    heading = symbol.kind,
                    signature = "${symbol.type} ${symbol.name}".trim(),
                    detail = symbol.tail.ifBlank { symbol.documentation },
                    members = symbol.members
                )
            )
        }

        return stageSummaryResponse(request.property, inventory)
    }

    private fun completionSortGroup(item: Metadata): Int = when {
        item.tail.startsWith("runtime") || item.tail.contains("push constant") || item.tail.contains("binding") -> 0
        item.tail.startsWith("local") -> 1
        item.tail.startsWith("Field") -> 2
        item.icon == "keyword" -> 4
        else -> 3
    }
}

private fun filterCompletionCandidates(candidates: List<Metadata>, prefix: String): List<Metadata> {
    val normalized = prefix.trim()
    if (normalized.isBlank()) return candidates

    val prefixMatches = candidates.filter {
        it.text.startsWith(normalized, ignoreCase = true) ||
            it.displayText.startsWith(normalized, ignoreCase = true)
    }
    if (prefixMatches.isNotEmpty()) return prefixMatches

    val containsMatches = candidates.filter {
        it.text.contains(normalized, ignoreCase = true) ||
            it.displayText.contains(normalized, ignoreCase = true)
    }
    return containsMatches.ifEmpty { candidates }
}

private enum class GlslTemplateContext {
    EXPRESSION,
    TAG
}

private fun templateContextAt(code: String, position: Int): GlslTemplateContext? {
    val safe = position.coerceIn(0, code.length)
    val exprOpen = code.lastIndexOf("{{", safe)
    val exprClose = code.lastIndexOf("}}", safe)
    if (exprOpen >= 0 && exprOpen > exprClose) return GlslTemplateContext.EXPRESSION

    val tagOpen = code.lastIndexOf("{%", safe)
    val tagClose = code.lastIndexOf("%}", safe)
    if (tagOpen >= 0 && tagOpen > tagClose) return GlslTemplateContext.TAG

    return null
}

private fun findIdentifierBoundsAt(code: String, position: Int): GlslBounds? {
    if (code.isEmpty()) return null
    val safe = position.coerceIn(0, code.length)
    fun isIdent(ch: Char) = ch == '_' || ch.isLetterOrDigit()

    val probe = when {
        safe < code.length && isIdent(code[safe]) -> safe
        safe > 0 && isIdent(code[safe - 1]) -> safe - 1
        else -> return null
    }

    var start = probe
    while (start > 0 && isIdent(code[start - 1])) start--

    var end = probe + 1
    while (end < code.length && isIdent(code[end])) end++

    return GlslBounds(start, end, code.substring(start, end))
}

private fun findReceiverAccess(code: String, tokenBounds: GlslBounds): GlslReceiverAccess? {
    var dot = tokenBounds.start - 1
    while (dot >= 0 && code[dot].isWhitespace()) dot--
    if (dot < 0 || code[dot] != '.') return null

    var receiverEnd = dot
    while (receiverEnd > 0 && code[receiverEnd - 1].isWhitespace()) receiverEnd--
    var receiverStart = receiverEnd
    while (receiverStart > 0) {
        val ch = code[receiverStart - 1]
        if (ch != '_' && !ch.isLetterOrDigit()) break
        receiverStart--
    }
    if (receiverStart >= receiverEnd) return null

    return GlslReceiverAccess(
        receiver = code.substring(receiverStart, receiverEnd),
        prefix = tokenBounds.text,
        bounds = tokenBounds
    )
}

private fun buildInventory(box: Box, property: String, code: String): GlslCompletionInventory {
    val symbols = linkedMapOf<String, GlslSymbolInfo>()
    val structures = linkedMapOf<String, List<GlslMemberInfo>>()
    var introspectionCount = 0

    fun addSymbol(symbol: GlslSymbolInfo) {
        symbols.putIfAbsent(symbol.name, symbol)
        if (symbol.members.isNotEmpty()) {
            structures.putIfAbsent(symbol.name, symbol.members)
            structures.putIfAbsent(symbol.type, symbol.members)
        }
    }

    for ((stage, introspection) in currentIntrospections(box, property)) {
        if (!introspection.valid) continue
        introspectionCount++
        addIntrospectionSymbols(stage, introspection, ::addSymbol, structures)
    }

    for (symbol in parseLocalGlslSymbols(code)) {
        addSymbol(symbol)
    }

    return GlslCompletionInventory(
        symbols = symbols.values.toList(),
        structures = structures,
        introspectionCount = introspectionCount
    )
}

private fun currentIntrospections(box: Box, property: String): List<Pair<String, Introspection>> {
    val out = mutableListOf<Pair<String, Introspection>>()
    when (property.lowercase()) {
        "vertex" -> box._shader?.current?.vi?.let { out += "vertex" to it }
        "fragment" -> {
            box._shader?.current?.fi?.let { out += "fragment" to it }
            box._meshShader?.current?.fi?.let { out += "fragment" to it }
        }
        "compute" -> box._computeShader?.current?.ci?.let { out += "compute" to it }
        "task" -> box._meshShader?.current?.ti?.let { out += "task" to it }
        "mesh" -> box._meshShader?.current?.mi?.let { out += "mesh" to it }
        "raygen" -> addRayStage(box, ShaderKind.RAYGEN_SHADER, out)
        "miss" -> addRayStage(box, ShaderKind.MISS_SHADER, out)
        "closesthit" -> addRayStage(box, ShaderKind.CLOSEST_HIT_SHADER, out)
        "anyhit" -> addRayStage(box, ShaderKind.ANY_HIT_SHADER, out)
        "intersection" -> addRayStage(box, ShaderKind.INTERSECTION_SHADER, out)
        "callable" -> addRayStage(box, ShaderKind.CALLABLE_SHADER, out)
    }
    return out.distinctBy { System.identityHashCode(it.second) }
}

private fun addRayStage(box: Box, kind: ShaderKind, out: MutableList<Pair<String, Introspection>>) {
    box._rayTracingShader?.current?.stageRecords
        ?.filter { it.stage.kind == kind }
        ?.forEach { out += it.stage.name to it.introspection }
}

private fun addIntrospectionSymbols(
    stage: String,
    introspection: Introspection,
    addSymbol: (GlslSymbolInfo) -> Unit,
    structures: MutableMap<String, List<GlslMemberInfo>>
) {
    introspection.inputs.forEach { input ->
        addSymbol(
            GlslSymbolInfo(
                name = input.name,
                type = glslTypeName(input.type),
                kind = "GLSL input",
                tail = "runtime $stage in location ${input.location}",
                documentation = "Input reflected from the latest successful shader introspection."
            )
        )
    }

    introspection.outputs.forEach { output ->
        addSymbol(
            GlslSymbolInfo(
                name = output.name,
                type = glslTypeName(output.type),
                kind = "GLSL output",
                tail = "runtime $stage out location ${output.location}",
                documentation = "Output reflected from the latest successful shader introspection."
            )
        )
    }

    introspection.pushConstants.forEach { push ->
        val members = push.members.toGlslMembers("push constant")
        addSymbol(
            GlslSymbolInfo(
                name = push.name,
                type = "push_constant",
                kind = "GLSL push constant",
                tail = "runtime $stage push constant ${push.size} bytes",
                members = members
            )
        )
        members.forEach { member ->
            addSymbol(
                GlslSymbolInfo(
                    name = member.name,
                    type = member.type,
                    kind = "GLSL push constant member",
                    tail = "runtime $stage push constant",
                    documentation = member.documentation
                )
            )
        }
    }

    introspection.ubos.forEach { ubo ->
        val members = ubo.members.toGlslMembers("uniform block")
        addSymbol(
            GlslSymbolInfo(
                name = ubo.name,
                type = "uniform",
                kind = "GLSL uniform block",
                tail = "runtime set ${ubo.set} binding ${ubo.binding}, ${ubo.size} bytes",
                members = members
            )
        )
    }

    introspection.ssbo.forEach { ssbo ->
        val members = ssbo.members.toGlslMembers("storage block")
        addSymbol(
            GlslSymbolInfo(
                name = ssbo.name,
                type = "buffer",
                kind = "GLSL storage block",
                tail = "runtime set ${ssbo.set} binding ${ssbo.binding}, ${ssbo.size} bytes",
                members = members
            )
        )
    }

    introspection.textures.forEach { texture ->
        addSymbol(
            GlslSymbolInfo(
                name = texture.name,
                type = texture.type,
                kind = "GLSL texture",
                tail = "runtime set ${texture.set} binding ${texture.binding}" +
                    if (texture.arrayCount > 0) ", array ${texture.arrayCount}" else ""
            )
        )
    }

    introspection.images.forEach { image ->
        addSymbol(
            GlslSymbolInfo(
                name = image.name,
                type = image.type,
                kind = "GLSL image",
                tail = "runtime set ${image.set} binding ${image.binding}"
            )
        )
    }

    introspection.accelerationStructures.forEach { accel ->
        addSymbol(
            GlslSymbolInfo(
                name = accel.name,
                type = accel.type,
                kind = "GLSL acceleration structure",
                tail = "runtime set ${accel.set} binding ${accel.binding}"
            )
        )
    }

    introspection.structureTypes.forEach { structure ->
        val members = structure.members.toGlslMembers("struct")
        structures.putIfAbsent(structure.name, members)
        addSymbol(
            GlslSymbolInfo(
                name = structure.name,
                type = if (structure.bufferReference) "buffer_reference" else "struct",
                kind = "GLSL structure",
                tail = "runtime ${structure.size} bytes",
                members = members
            )
        )
    }
}

private fun List<Introspected.Member>.toGlslMembers(source: String): List<GlslMemberInfo> =
    map { member ->
        GlslMemberInfo(
            name = member.name,
            type = glslTypeName(member.type),
            tail = source,
            documentation = buildString {
                append(source)
                append(", offset ")
                append(member.offset)
                if (member.arrayStride > 0) append(", array stride ").append(member.arrayStride)
            }
        )
    }

private fun glslTypeName(type: arrow.core.Either<PrimitiveType, List<Introspected.Member>>): String =
    type.fold(::primitiveTypeName) { "struct" }

private fun primitiveTypeName(type: PrimitiveType): String = when (type) {
    PrimitiveType.float -> "float"
    PrimitiveType.vec2 -> "vec2"
    PrimitiveType.vec3 -> "vec3"
    PrimitiveType.vec4 -> "vec4"
    PrimitiveType.int -> "int"
    PrimitiveType.uint -> "uint"
    PrimitiveType.mat2 -> "mat2"
    PrimitiveType.mat3 -> "mat3"
    PrimitiveType.mat4 -> "mat4"
    PrimitiveType.float16_t -> "float16_t"
    PrimitiveType.f16vec2 -> "f16vec2"
    PrimitiveType.f16vec3 -> "f16vec3"
    PrimitiveType.f16vec4 -> "f16vec4"
    PrimitiveType.deviceptr -> "uint64_t"
}

private val glslBlockCommentRegex = Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL)
private val glslLineCommentRegex = Regex("""//.*?$""", setOf(RegexOption.MULTILINE))
private val fieldTemplateIslandRegex = Regex("""\{\{.*?}}|\{%.*?%}""", RegexOption.DOT_MATCHES_ALL)
private val localStructRegex = Regex(
    """\bstruct\s+([A-Za-z_][A-Za-z0-9_]*)\s*\{(.*?)\}\s*([A-Za-z_][A-Za-z0-9_]*)?\s*;""",
    setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.MULTILINE)
)
private val localBlockRegex = Regex(
    """(?:layout\s*\([^)]*\)\s*)?\b(uniform|buffer)\s+([A-Za-z_][A-Za-z0-9_]*)\s*\{(.*?)\}\s*([A-Za-z_][A-Za-z0-9_]*)?\s*;""",
    setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.MULTILINE)
)
private val localFieldRegex = Regex(
    """(?:layout\s*\([^)]*\)\s*)?(?:(?:readonly|writeonly|coherent|volatile|restrict|const|in|out|inout|flat|smooth|centroid|sample|patch|highp|mediump|lowp)\s+)*([A-Za-z_][A-Za-z0-9_]*(?:\s*<[^;>]+>)?)\s+([A-Za-z_][A-Za-z0-9_]*)(?:\s*\[[^]]*])?\s*$"""
)
private val localVariableRegex = Regex(
    """(?m)(?:^|[;{(,])\s*(?:layout\s*\([^)]*\)\s*)?(?:(?:const|in|out|inout|uniform|buffer|shared|flat|smooth|centroid|sample|patch|readonly|writeonly|coherent|volatile|restrict|highp|mediump|lowp)\s+)*([A-Za-z_][A-Za-z0-9_]*(?:\s*<[^;>()]+>)?)\s+([A-Za-z_][A-Za-z0-9_]*)(?:\s*\[[^]]*])?\s*(?=[=;,)])"""
)
private val localFunctionRegex = Regex(
    """(?m)(?:^|[;}])\s*(?:(?:const|precise|highp|mediump|lowp)\s+)*([A-Za-z_][A-Za-z0-9_]*(?:\s*<[^;>()]+>)?)\s+([A-Za-z_][A-Za-z0-9_]*)\s*\(([^;{}()]*)\)\s*(?=[{;])"""
)

private fun parseLocalGlslSymbols(code: String): List<GlslSymbolInfo> {
    val clean = code
        .replace(glslBlockCommentRegex, "")
        .replace(glslLineCommentRegex, "")
        .replace(fieldTemplateIslandRegex, " ")

    val symbols = linkedMapOf<String, GlslSymbolInfo>()
    val structures = linkedMapOf<String, List<GlslMemberInfo>>()

    fun parseMembers(body: String, source: String): List<GlslMemberInfo> =
        body.split(";").mapNotNull { raw ->
            val line = raw.trim().takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val match = localFieldRegex.matchEntire(line) ?: return@mapNotNull null
            GlslMemberInfo(
                name = match.groupValues[2],
                type = match.groupValues[1].trim(),
                tail = source,
                documentation = source
            )
        }

    localStructRegex.findAll(clean).forEach { match ->
        val typeName = match.groupValues[1]
        val members = parseMembers(match.groupValues[2], "local struct")
        structures[typeName] = members
        symbols[typeName] = GlslSymbolInfo(
            name = typeName,
            type = "struct",
            kind = "GLSL structure",
            tail = "local struct",
            members = members
        )
        val instance = match.groupValues.getOrNull(3).orEmpty()
        if (instance.isNotBlank()) {
            symbols[instance] = GlslSymbolInfo(
                name = instance,
                type = typeName,
                kind = "GLSL variable",
                tail = "local struct instance",
                members = members
            )
        }
    }

    localBlockRegex.findAll(clean).forEach { match ->
        val blockKind = match.groupValues[1]
        val blockName = match.groupValues[2]
        val members = parseMembers(match.groupValues[3], "local $blockKind block")
        val instance = match.groupValues.getOrNull(4).orEmpty().ifBlank { blockName }
        structures[blockName] = members
        symbols[instance] = GlslSymbolInfo(
            name = instance,
            type = blockName,
            kind = "GLSL $blockKind block",
            tail = "local $blockKind block",
            members = members
        )
    }

    localFunctionRegex.findAll(clean).forEach { match ->
        val returnType = compactGlslWhitespace(match.groupValues[1])
        val name = match.groupValues[2].trim()
        val parameters = compactGlslWhitespace(match.groupValues[3])
        if (returnType in glslControlWords || name in glslControlWords || name == "main") return@forEach

        symbols.putIfAbsent(
            name,
            GlslSymbolInfo(
                name = name,
                type = returnType,
                kind = "GLSL function",
                tail = "local function(${glslParameterTypeSummary(parameters)})",
                documentation = "$returnType $name($parameters)"
            )
        )
    }

    localVariableRegex.findAll(clean).forEach { match ->
        val type = match.groupValues[1].trim()
        val name = match.groupValues[2].trim()
        if (name in glslControlWords || name == "main") return@forEach
        symbols.putIfAbsent(
            name,
            GlslSymbolInfo(
                name = name,
                type = type,
                kind = "GLSL variable",
                tail = "local $type",
                members = structures[type].orEmpty()
            )
        )
    }

    return symbols.values.toList()
}

private fun compactGlslWhitespace(value: String): String =
    value.trim().replace(Regex("""\s+"""), " ")

private fun glslParameterTypeSummary(parameters: String): String {
    if (parameters.isBlank()) return ""
    val qualifiers = setOf(
        "const", "in", "out", "inout", "highp", "mediump", "lowp", "precise",
        "readonly", "writeonly", "coherent", "volatile", "restrict"
    )
    return parameters
        .split(",")
        .map { parameter ->
            parameter
                .replace(Regex("""layout\s*\([^)]*\)"""), " ")
                .replace(Regex("""\[[^]]*]"""), "[]")
                .trim()
                .split(Regex("""\s+"""))
                .filter { it.isNotBlank() && it !in qualifiers }
                .let { words ->
                    when {
                        words.isEmpty() -> ""
                        words.size == 1 -> words.single()
                        else -> words.dropLast(1).joinToString(" ")
                    }
                }
        }
        .filter { it.isNotBlank() }
        .joinToString(", ")
}

private fun rootCompletions(property: String, inventory: GlslCompletionInventory): List<Metadata> =
    inventory.symbols.map { it.toMetadata() } +
        builtinSymbolsForProperty(property).map { it.toMetadata() } +
        glslSnippetCompletions(property)

private fun memberCompletions(receiver: GlslReceiverAccess, inventory: GlslCompletionInventory): List<Metadata> {
    val members = membersForReceiver(receiver.receiver, inventory)
    if (members.isEmpty() && vectorLike(receiver.receiver)) return swizzleCompletions(receiver.receiver)
    return members.map {
        Metadata(
            icon = "property",
            tail = it.tail,
            text = it.name,
            displayText = it.name,
            documentation = glslDocumentation("GLSL member", "${it.type} ${it.name}", it.documentation.ifBlank { it.tail })
        )
    } + swizzleCompletions(receiver.receiver).takeIf { receiver.receiver in setOf("vec2", "vec3", "vec4") }.orEmpty()
}

private fun findMember(receiver: GlslReceiverAccess, inventory: GlslCompletionInventory): GlslMemberInfo? =
    membersForReceiver(receiver.receiver, inventory).firstOrNull { it.name == receiver.prefix }

private fun membersForReceiver(receiver: String, inventory: GlslCompletionInventory): List<GlslMemberInfo> {
    val symbol = inventory.symbols.firstOrNull { it.name == receiver }
    if (symbol != null) {
        if (symbol.members.isNotEmpty()) return symbol.members
        inventory.structures[symbol.type]?.let { return it }
    }
    inventory.structures[receiver]?.let { return it }
    val type = symbol?.type ?: receiver
    return when (type) {
        "vec2", "ivec2", "uvec2", "bvec2" -> vectorMembers(2)
        "vec3", "ivec3", "uvec3", "bvec3" -> vectorMembers(3)
        "vec4", "ivec4", "uvec4", "bvec4" -> vectorMembers(4)
        else -> emptyList()
    }
}

private fun vectorLike(receiver: String): Boolean =
    receiver in setOf("vec2", "vec3", "vec4", "ivec2", "ivec3", "ivec4", "uvec2", "uvec3", "uvec4")

private fun vectorMembers(size: Int): List<GlslMemberInfo> {
    val names = listOf("x", "y", "z", "w").take(size) + listOf("r", "g", "b", "a").take(size)
    return names.map { GlslMemberInfo(it, "float", "vector component", "Vector component alias.") }
}

private fun swizzleCompletions(receiver: String): List<Metadata> {
    val size = when {
        receiver.endsWith("2") -> 2
        receiver.endsWith("3") -> 3
        else -> 4
    }
    val axes = listOf("x", "y", "z", "w").take(size)
    val common = axes + listOf(
        axes.take(2).joinToString(""),
        axes.take(3).joinToString(""),
        axes.take(4).joinToString(""),
        axes.reversed().take(size.coerceAtMost(3)).joinToString("")
    ).filter { it.isNotBlank() }
    return common.distinct().map {
        Metadata(
            icon = "property",
            tail = "swizzle",
            text = it,
            displayText = it,
            documentation = glslDocumentation("GLSL swizzle", it, "Vector swizzle.")
        )
    }
}

private fun GlslSymbolInfo.toMetadata(): Metadata =
    Metadata(
        icon = when {
            kind.contains("function", ignoreCase = true) -> "method"
            kind.contains("structure", ignoreCase = true) -> "class"
            kind.contains("keyword", ignoreCase = true) -> "keyword"
            kind.contains("type", ignoreCase = true) -> "class"
            else -> "property"
        },
        tail = tail,
        text = name,
        displayText = name,
        documentation = glslDocumentation(kind, "${type} ${name}".trim(), documentation.ifBlank { tail }, members)
    )

private val glslControlWords = setOf(
    "if", "else", "for", "while", "do", "switch", "case", "default", "break", "continue", "return", "discard"
)

private val glslKeywordSymbols = (
    glslControlWords +
        setOf(
            "layout", "uniform", "buffer", "push_constant", "in", "out", "inout", "const", "shared",
            "flat", "smooth", "centroid", "sample", "patch", "readonly", "writeonly", "coherent",
            "volatile", "restrict", "precision", "highp", "mediump", "lowp", "struct", "precise",
            "nonuniformEXT", "pervertexEXT", "perprimitiveEXT", "taskPayloadSharedEXT", "rayPayloadEXT",
            "rayPayloadInEXT", "hitAttributeEXT", "callableDataEXT", "callableDataInEXT"
        )
    ).map {
    GlslSymbolInfo(it, "", "GLSL keyword", "keyword")
}

private val glslTypeSymbols = listOf(
    "void", "bool", "int", "uint", "float", "double",
    "vec2", "vec3", "vec4", "ivec2", "ivec3", "ivec4", "uvec2", "uvec3", "uvec4", "bvec2", "bvec3", "bvec4",
    "dvec2", "dvec3", "dvec4",
    "mat2", "mat3", "mat4", "mat2x2", "mat2x3", "mat2x4", "mat3x2", "mat3x3", "mat3x4",
    "mat4x2", "mat4x3", "mat4x4", "dmat2", "dmat3", "dmat4", "dmat2x2", "dmat2x3", "dmat2x4",
    "dmat3x2", "dmat3x3", "dmat3x4", "dmat4x2", "dmat4x3", "dmat4x4",
    "float16_t", "f16vec2", "f16vec3", "f16vec4", "int8_t", "i8vec2", "i8vec3", "i8vec4",
    "uint8_t", "u8vec2", "u8vec3", "u8vec4", "int16_t", "i16vec2", "i16vec3", "i16vec4",
    "uint16_t", "u16vec2", "u16vec3", "u16vec4", "int64_t", "i64vec2", "i64vec3", "i64vec4",
    "uint64_t", "u64vec2", "u64vec3", "u64vec4",
    "sampler1D", "sampler1DArray", "sampler1DArrayShadow", "sampler1DShadow",
    "sampler2D", "sampler2DArray", "sampler2DArrayShadow", "sampler2DMS", "sampler2DMSArray",
    "sampler2DRect", "sampler2DRectShadow", "sampler2DShadow", "sampler3D", "samplerBuffer",
    "samplerCube", "samplerCubeArray", "samplerCubeArrayShadow", "samplerCubeShadow",
    "isampler1D", "isampler1DArray", "isampler2D", "isampler2DArray", "isampler2DMS",
    "isampler2DMSArray", "isampler2DRect", "isampler3D", "isamplerBuffer", "isamplerCube",
    "isamplerCubeArray", "usampler1D", "usampler1DArray", "usampler2D", "usampler2DArray",
    "usampler2DMS", "usampler2DMSArray", "usampler2DRect", "usampler3D", "usamplerBuffer",
    "usamplerCube", "usamplerCubeArray",
    "texture1D", "texture1DArray", "texture2D", "texture2DArray", "texture2DMS", "texture2DMSArray",
    "texture2DRect", "texture3D", "textureBuffer", "textureCube", "textureCubeArray",
    "itexture1D", "itexture1DArray", "itexture2D", "itexture2DArray", "itexture2DMS",
    "itexture2DMSArray", "itexture2DRect", "itexture3D", "itextureBuffer", "itextureCube",
    "itextureCubeArray", "utexture1D", "utexture1DArray", "utexture2D", "utexture2DArray",
    "utexture2DMS", "utexture2DMSArray", "utexture2DRect", "utexture3D", "utextureBuffer",
    "utextureCube", "utextureCubeArray",
    "image1D", "image1DArray", "image2D", "image2DArray", "image2DMS", "image2DMSArray",
    "image2DRect", "image3D", "imageBuffer", "imageCube", "imageCubeArray",
    "iimage1D", "iimage1DArray", "iimage2D", "iimage2DArray", "iimage2DMS", "iimage2DMSArray",
    "iimage2DRect", "iimage3D", "iimageBuffer", "iimageCube", "iimageCubeArray",
    "uimage1D", "uimage1DArray", "uimage2D", "uimage2DArray", "uimage2DMS", "uimage2DMSArray",
    "uimage2DRect", "uimage3D", "uimageBuffer", "uimageCube", "uimageCubeArray",
    "subpassInput", "subpassInputMS", "isubpassInput", "isubpassInputMS", "usubpassInput", "usubpassInputMS",
    "sampler", "samplerShadow", "atomic_uint", "accelerationStructureEXT", "rayQueryEXT"
).map {
    GlslSymbolInfo(it, "type", "GLSL type", "type")
}

private val glslFunctionSymbols = listOf(
    "abs", "sign", "floor", "ceil", "fract", "mod", "min", "max", "clamp", "mix", "step", "smoothstep",
    "sin", "cos", "tan", "asin", "acos", "atan", "pow", "exp", "log", "sqrt", "inversesqrt",
    "length", "distance", "dot", "cross", "normalize", "reflect", "refract", "faceforward",
    "radians", "degrees", "sinh", "cosh", "tanh", "asinh", "acosh", "atanh",
    "isnan", "isinf", "fma", "frexp", "ldexp",
    "matrixCompMult", "outerProduct", "transpose", "determinant", "inverse",
    "lessThan", "lessThanEqual", "greaterThan", "greaterThanEqual", "equal", "notEqual", "any", "all", "not",
    "uaddCarry", "usubBorrow", "umulExtended", "imulExtended", "bitfieldExtract", "bitfieldInsert",
    "bitfieldReverse", "bitCount", "findLSB", "findMSB", "floatBitsToInt", "floatBitsToUint",
    "intBitsToFloat", "uintBitsToFloat", "packSnorm2x16", "unpackSnorm2x16", "packUnorm2x16",
    "unpackUnorm2x16", "packHalf2x16", "unpackHalf2x16", "packDouble2x32", "unpackDouble2x32",
    "texture", "textureProj", "textureLod", "textureOffset", "texelFetch", "texelFetchOffset",
    "textureProjOffset", "textureLodOffset", "textureProjLod", "textureProjLodOffset",
    "textureGrad", "textureGradOffset", "textureProjGrad", "textureProjGradOffset",
    "textureGather", "textureGatherOffset", "textureGatherOffsets", "textureSize",
    "textureQueryLod", "textureQueryLevels", "textureSamples", "textureQuerySamples",
    "dFdx", "dFdy", "fwidth", "dFdxFine", "dFdyFine", "fwidthFine", "dFdxCoarse", "dFdyCoarse", "fwidthCoarse",
    "imageLoad", "imageStore", "imageSize", "imageSamples", "imageAtomicAdd", "imageAtomicMin",
    "imageAtomicMax", "imageAtomicAnd", "imageAtomicOr", "imageAtomicXor", "imageAtomicExchange",
    "imageAtomicCompSwap",
    "atomicAdd", "atomicMin", "atomicMax", "atomicAnd", "atomicOr", "atomicXor", "atomicExchange",
    "atomicCompSwap", "atomicCounter", "atomicCounterIncrement", "atomicCounterDecrement",
    "barrier", "memoryBarrier", "memoryBarrierAtomicCounter", "memoryBarrierBuffer", "memoryBarrierImage",
    "memoryBarrierShared", "groupMemoryBarrier", "subgroupBarrier", "subgroupMemoryBarrier",
    "subgroupMemoryBarrierBuffer", "subgroupMemoryBarrierShared", "subgroupMemoryBarrierImage",
    "subgroupElect", "subgroupAll", "subgroupAny", "subgroupAllEqual", "subgroupBroadcast",
    "subgroupBroadcastFirst", "subgroupBallot", "subgroupInverseBallot", "subgroupBallotBitExtract",
    "subgroupBallotBitCount", "subgroupBallotInclusiveBitCount", "subgroupBallotExclusiveBitCount",
    "subgroupBallotFindLSB", "subgroupBallotFindMSB", "subgroupShuffle", "subgroupShuffleXor",
    "subgroupShuffleUp", "subgroupShuffleDown", "subgroupAdd", "subgroupMul", "subgroupMin",
    "subgroupMax", "subgroupAnd", "subgroupOr", "subgroupXor", "subgroupInclusiveAdd",
    "subgroupInclusiveMul", "subgroupInclusiveMin", "subgroupInclusiveMax", "subgroupInclusiveAnd",
    "subgroupInclusiveOr", "subgroupInclusiveXor", "subgroupExclusiveAdd", "subgroupExclusiveMul",
    "subgroupExclusiveMin", "subgroupExclusiveMax", "subgroupExclusiveAnd", "subgroupExclusiveOr",
    "subgroupExclusiveXor",
    "EmitMeshTasksEXT", "SetMeshOutputsEXT", "traceRayEXT", "reportIntersectionEXT",
    "ignoreIntersectionEXT", "terminateRayEXT", "executeCallableEXT", "rayQueryInitializeEXT",
    "rayQueryProceedEXT", "rayQueryTerminateEXT", "rayQueryGenerateIntersectionEXT",
    "rayQueryConfirmIntersectionEXT", "rayQueryGetIntersectionTypeEXT", "rayQueryGetRayTMinEXT",
    "rayQueryGetRayFlagsEXT", "rayQueryGetWorldRayOriginEXT", "rayQueryGetWorldRayDirectionEXT",
    "rayQueryGetIntersectionTEXT", "rayQueryGetIntersectionInstanceCustomIndexEXT",
    "rayQueryGetIntersectionInstanceIdEXT", "rayQueryGetIntersectionInstanceShaderBindingTableRecordOffsetEXT",
    "rayQueryGetIntersectionGeometryIndexEXT", "rayQueryGetIntersectionPrimitiveIndexEXT",
    "rayQueryGetIntersectionBarycentricsEXT", "rayQueryGetIntersectionFrontFaceEXT",
    "rayQueryGetIntersectionCandidateAABBOpaqueEXT", "rayQueryGetIntersectionObjectRayOriginEXT",
    "rayQueryGetIntersectionObjectRayDirectionEXT", "rayQueryGetIntersectionObjectToWorldEXT",
    "rayQueryGetIntersectionWorldToObjectEXT"
).map {
    GlslSymbolInfo(it, "fn", "GLSL function", "builtin function")
}

private fun builtinSymbolsForProperty(property: String): List<GlslSymbolInfo> {
    val stageBuiltins = when (property.lowercase()) {
        "vertex" -> listOf(
            GlslSymbolInfo("gl_Position", "vec4", "GLSL vertex builtin", "builtin output"),
            GlslSymbolInfo("gl_PointSize", "float", "GLSL vertex builtin", "builtin output"),
            GlslSymbolInfo("gl_VertexIndex", "int", "GLSL vertex builtin", "builtin input"),
            GlslSymbolInfo("gl_InstanceIndex", "int", "GLSL vertex builtin", "builtin input"),
            GlslSymbolInfo("gl_DrawID", "int", "GLSL vertex builtin", "builtin input"),
            GlslSymbolInfo("gl_BaseVertex", "int", "GLSL vertex builtin", "builtin input"),
            GlslSymbolInfo("gl_BaseInstance", "int", "GLSL vertex builtin", "builtin input")
        )
        "fragment" -> listOf(
            GlslSymbolInfo("gl_FragCoord", "vec4", "GLSL fragment builtin", "builtin input"),
            GlslSymbolInfo("gl_FrontFacing", "bool", "GLSL fragment builtin", "builtin input"),
            GlslSymbolInfo("gl_PointCoord", "vec2", "GLSL fragment builtin", "builtin input"),
            GlslSymbolInfo("gl_FragDepth", "float", "GLSL fragment builtin", "builtin output"),
            GlslSymbolInfo("gl_HelperInvocation", "bool", "GLSL fragment builtin", "builtin input"),
            GlslSymbolInfo("gl_SampleID", "int", "GLSL fragment builtin", "builtin input"),
            GlslSymbolInfo("gl_SamplePosition", "vec2", "GLSL fragment builtin", "builtin input"),
            GlslSymbolInfo("gl_SampleMask", "array", "GLSL fragment builtin", "builtin output"),
            GlslSymbolInfo("gl_SampleMaskIn", "array", "GLSL fragment builtin", "builtin input"),
            GlslSymbolInfo("gl_PrimitiveID", "int", "GLSL fragment builtin", "builtin input")
        )
        "compute" -> listOf(
            GlslSymbolInfo("gl_GlobalInvocationID", "uvec3", "GLSL compute builtin", "builtin input"),
            GlslSymbolInfo("gl_LocalInvocationID", "uvec3", "GLSL compute builtin", "builtin input"),
            GlslSymbolInfo("gl_WorkGroupID", "uvec3", "GLSL compute builtin", "builtin input"),
            GlslSymbolInfo("gl_NumWorkGroups", "uvec3", "GLSL compute builtin", "builtin input"),
            GlslSymbolInfo("gl_WorkGroupSize", "uvec3", "GLSL compute builtin", "builtin input"),
            GlslSymbolInfo("gl_LocalInvocationIndex", "uint", "GLSL compute builtin", "builtin input")
        )
        "mesh" -> listOf(
            GlslSymbolInfo("gl_WorkGroupID", "uvec3", "GLSL mesh builtin", "builtin input"),
            GlslSymbolInfo("gl_LocalInvocationID", "uvec3", "GLSL mesh builtin", "builtin input"),
            GlslSymbolInfo("gl_LocalInvocationIndex", "uint", "GLSL mesh builtin", "builtin input"),
            GlslSymbolInfo("gl_MeshVerticesEXT", "array", "GLSL mesh builtin", "builtin output"),
            GlslSymbolInfo("gl_PrimitiveTriangleIndicesEXT", "array", "GLSL mesh builtin", "builtin output"),
            GlslSymbolInfo("gl_PrimitiveLineIndicesEXT", "array", "GLSL mesh builtin", "builtin output"),
            GlslSymbolInfo("gl_PrimitivePointIndicesEXT", "array", "GLSL mesh builtin", "builtin output"),
            GlslSymbolInfo("gl_PrimitiveCountEXT", "uint", "GLSL mesh builtin", "builtin output"),
            GlslSymbolInfo("SetMeshOutputsEXT", "fn", "GLSL mesh function", "builtin function")
        )
        "task" -> listOf(
            GlslSymbolInfo("EmitMeshTasksEXT", "fn", "GLSL task function", "builtin function"),
            GlslSymbolInfo("gl_WorkGroupID", "uvec3", "GLSL task builtin", "builtin input"),
            GlslSymbolInfo("gl_LocalInvocationID", "uvec3", "GLSL task builtin", "builtin input"),
            GlslSymbolInfo("gl_LocalInvocationIndex", "uint", "GLSL task builtin", "builtin input")
        )
        "raygen", "miss", "closesthit", "anyhit", "intersection", "callable" -> listOf(
            GlslSymbolInfo("gl_LaunchIDEXT", "uvec3", "GLSL ray tracing builtin", "builtin input"),
            GlslSymbolInfo("gl_LaunchSizeEXT", "uvec3", "GLSL ray tracing builtin", "builtin input"),
            GlslSymbolInfo("gl_WorldRayOriginEXT", "vec3", "GLSL ray tracing builtin", "builtin input"),
            GlslSymbolInfo("gl_WorldRayDirectionEXT", "vec3", "GLSL ray tracing builtin", "builtin input"),
            GlslSymbolInfo("gl_ObjectRayOriginEXT", "vec3", "GLSL ray tracing builtin", "builtin input"),
            GlslSymbolInfo("gl_ObjectRayDirectionEXT", "vec3", "GLSL ray tracing builtin", "builtin input"),
            GlslSymbolInfo("gl_RayTminEXT", "float", "GLSL ray tracing builtin", "builtin input"),
            GlslSymbolInfo("gl_RayTmaxEXT", "float", "GLSL ray tracing builtin", "builtin input"),
            GlslSymbolInfo("gl_InstanceCustomIndexEXT", "int", "GLSL ray tracing builtin", "builtin input"),
            GlslSymbolInfo("gl_InstanceID", "int", "GLSL ray tracing builtin", "builtin input"),
            GlslSymbolInfo("gl_PrimitiveID", "int", "GLSL ray tracing builtin", "builtin input"),
            GlslSymbolInfo("gl_GeometryIndexEXT", "int", "GLSL ray tracing builtin", "builtin input"),
            GlslSymbolInfo("gl_HitKindEXT", "int", "GLSL ray tracing builtin", "builtin input"),
            GlslSymbolInfo("gl_ObjectToWorldEXT", "mat4x3", "GLSL ray tracing builtin", "builtin input"),
            GlslSymbolInfo("gl_WorldToObjectEXT", "mat4x3", "GLSL ray tracing builtin", "builtin input"),
            GlslSymbolInfo("traceRayEXT", "fn", "GLSL ray tracing function", "builtin function")
        )
        else -> emptyList()
    }
    return stageBuiltins + glslKeywordSymbols + glslTypeSymbols + glslFunctionSymbols
}

private fun glslSnippetCompletions(property: String): List<Metadata> {
    val stage = property.lowercase()
    val snippets = mutableListOf(
        Metadata(
            icon = "keyword",
            tail = "Field template",
            text = "{% copyPC %}",
            displayText = "{% copyPC %}",
            documentation = "Copies the previous stage push-constant declaration into this shader."
        ),
        Metadata(
            icon = "keyword",
            tail = "Field template",
            text = "{{ shadertoy.${if (stage == "vertex") "vertexPreamble" else "fragmentPreamble"} }}",
            displayText = "shadertoy preamble",
            documentation = "Field shader-template interpolation for the Shadertoy helper preamble."
        ),
        Metadata(
            icon = "keyword",
            tail = "layout",
            text = "layout(location = 0) in vec3 position;",
            displayText = "layout(location) in",
            documentation = "Common GLSL interface input declaration."
        ),
        Metadata(
            icon = "keyword",
            tail = "layout",
            text = "layout(set = 0, binding = 0) uniform sampler2D sourceTex;",
            displayText = "layout(set,binding) sampler2D",
            documentation = "Common Field/Vulkan texture binding declaration."
        )
    )
    if (stage == "compute") {
        snippets += Metadata(
            icon = "keyword",
            tail = "compute",
            text = "layout(local_size_x = 8, local_size_y = 8, local_size_z = 1) in;",
            displayText = "layout(local_size) in",
            documentation = "Compute workgroup-size declaration."
        )
    }
    return snippets
}

private fun templateTagCompletions(): List<Metadata> =
    listOf(
        "if " to "Field template conditional",
        "else" to "Field template conditional branch",
        "endif" to "Field template conditional end",
        "include \"\"" to "Field template include",
        "copyPC" to "Copy previous stage push constants"
    ).map { (text, doc) ->
        Metadata(
            icon = "keyword",
            tail = "Field template",
            text = text,
            displayText = text,
            documentation = doc
        )
    }

private fun templateExpressionCompletions(inventory: GlslCompletionInventory): List<Metadata> =
    listOf(
        Metadata("property", "Field template", "shadertoy.vertexPreamble", "shadertoy.vertexPreamble", "Shadertoy vertex helper preamble."),
        Metadata("property", "Field template", "shadertoy.fragmentPreamble", "shadertoy.fragmentPreamble", "Shadertoy fragment helper preamble.")
    ) + listGlobalShaderTemplateNames().map {
        Metadata("property", "Field template", it, it, "Global shader template.")
    } + inventory.symbols.map { it.toMetadata() }

private fun templateShadertoyMemberCompletions(): List<Metadata> =
    listOf(
        Metadata("property", "Field template", "vertexPreamble", "vertexPreamble", "Shadertoy vertex helper preamble."),
        Metadata("property", "Field template", "fragmentPreamble", "fragmentPreamble", "Shadertoy fragment helper preamble.")
    )

private fun stageSummaryResponse(property: String, inventory: GlslCompletionInventory): SymbolContextResponse {
    if (inventory.symbols.isEmpty()) return SymbolContextResponse()
    val runtime = inventory.symbols.count { it.tail.startsWith("runtime") }
    val locals = inventory.symbols.count { it.tail.startsWith("local") }
    val detail = buildString {
        append(runtime)
        append(" reflected")
        if (locals > 0) append(", ").append(locals).append(" local")
        if (inventory.introspectionCount == 0) append(" · no compiled introspection yet")
    }
    return SymbolContextResponse(
        symbol = property,
        html = glslContextHtml(
            heading = "GLSL ${property.lowercase()}",
            signature = "${inventory.symbols.size} known symbols",
            detail = detail
        )
    )
}

private fun glslContextHtml(
    heading: String,
    signature: String,
    detail: String = "",
    members: List<GlslMemberInfo> = emptyList()
): String {
    val memberHtml = if (members.isEmpty()) "" else {
        val rows = members.take(24).joinToString("") {
            "<div><code>${escapeGlslHtml(it.type)} ${escapeGlslHtml(it.name)}</code> <span style='color:#777;'>${escapeGlslHtml(it.tail)}</span></div>"
        }
        "<details class='afield-symbol-context-disclosure' style='margin-top:2px;'>" +
            "<summary tabindex='-1' onfocus='this.blur()' style='color:#777; cursor:pointer; user-select:none; outline:none; box-shadow:none;'>" +
            "${members.size} member${if (members.size == 1) "" else "s"}</summary>" +
            "<div class='afield-symbol-context-disclosure-body' style='margin-top:4px; overflow:auto;'>$rows</div>" +
            "</details>"
    }
    return buildString {
        append("<div class='afield-glsl-context' style='display:inline-block; max-width: 620px;'>")
        append("<strong>")
        append(escapeGlslHtml(heading))
        append("</strong> <code>")
        append(escapeGlslHtml(signature))
        append("</code>")
        if (detail.isNotBlank()) {
            append("<div style='color:#777;'>")
            append(escapeGlslHtml(detail))
            append("</div>")
        }
        append(memberHtml)
        append("</div>")
    }
}

private fun glslDocumentation(
    heading: String,
    signature: String,
    detail: String = "",
    members: List<GlslMemberInfo> = emptyList()
): String = buildString {
    append("**")
    append(heading)
    append("**\n\n")
    if (signature.isNotBlank()) {
        append("```glsl\n")
        append(signature)
        append("\n```\n")
    }
    if (detail.isNotBlank()) {
        append("\n")
        append(detail)
        append("\n")
    }
    if (members.isNotEmpty()) {
        append("\n**Members**\n")
        members.take(24).forEach {
            append("- `")
            append(it.type)
            append(" ")
            append(it.name)
            append("`")
            if (it.tail.isNotBlank()) append(" - ").append(it.tail)
            append("\n")
        }
    }
}

private fun escapeGlslHtml(value: String): String =
    value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")
