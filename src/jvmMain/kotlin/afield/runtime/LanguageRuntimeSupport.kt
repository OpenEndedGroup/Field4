package afield.runtime

import afield.Box
import afield.BoxExecution
import afield.KotlinExecution
import afield.remotejs.code_language
import afield.logger.lblock
import afield.inMain
import afield.notify
import afield.offline
import afield.sessions
import kotlinx.coroutines.delay
import kotlinx.coroutines.yield
import org.jetbrains.kotlin.util.capitalizeDecapitalize.capitalizeAsciiOnly
import java.util.WeakHashMap
import kotlin.reflect.KClass

class TopologyWatcher(
    private val label: String,
    private val intervalMs: Long = 5L,
    private val onTopologyChanged: (Collection<Box>) -> Unit
) {
    private val lastGeneration = WeakHashMap<Any, Int>()

    fun start() = inMain {
        while (true) {
            sessions.entries.forEach { entry ->
                val current = entry.value.topologyGeneration
                val prev = lastGeneration.put(entry.key, current)
                if (prev == null || prev != current) {
                    onTopologyChanged(entry.value.boxes.values)
                }
            }
            if (intervalMs <= 0) {
                yield()
            } else {
                delay(intervalMs)
            }
        }
    }
}

fun applyRuntimeToggle(
    boxes: Collection<Box>,
    isRoot: (Box) -> Boolean?,
    runtimeClass: KClass<out BoxExecution>,
    newExecution: (Box) -> BoxExecution,
    language: String,
    customization: String,
    notifyCategory: String,
    onAttach: (Box) -> Unit = {},
    onDetach: (Box) -> Unit = {}
) {
    boxes.forEach { root ->
        val targetLanguage = language.capitalizeAsciiOnly()
        if (isRoot(root) == true) {
            root.children.forEach { child ->
                if (runtimeClass.isInstance(child.ke)) {
                    child.offline = false
                    onAttach(child)
                    return@forEach
                }
                child.ke = newExecution(child)
                child.customization = languageLabelCustomization(language)
                child.code_language = language
                child.offline = false
                onAttach(child)
                notify("'${child.name}' becomes $targetLanguage", notifyCategory)
            }
        } else {
            root.children.forEach { child ->
                if (runtimeClass.isInstance(child.ke)) {
                    onDetach(child)
                    child.ke = KotlinExecution()
                    child.customization = languageLabelCustomization(null)
                    child.code_language = "Kotlin"
                    notify("'${child.name}' no longer $targetLanguage", notifyCategory)
                }
            }
        }
    }
}

private fun languageLabelCustomization(language: String?): String {
    val trimmed = language?.trim().orEmpty()
    if (trimmed.isBlank() || trimmed.equals("kotlin", ignoreCase = true)) {
        return """
            |const existing = this.querySelector('[data-afield-language-label="true"]');
            |if (existing) existing.remove();
            |this.style.border = "";
            |this.style.margin = "";
        """.trimMargin()
    }

    return """
        |const title = this.querySelector('.box-title-stack') || this;
        |let label = this.querySelector('[data-afield-language-label="true"]');
        |if (!label) {
        |    label = document.createElement('div');
        |    label.setAttribute('data-afield-language-label', 'true');
        |    title.appendChild(label);
        |}
        |label.textContent = ${jsString(trimmed)};
        |label.style.fontSize = "9px";
        |label.style.lineHeight = "1.1";
        |label.style.opacity = "0.58";
        |label.style.fontWeight = "500";
        |label.style.letterSpacing = "0";
        |label.style.textTransform = "none";
        |label.style.pointerEvents = "none";
        |label.style.userSelect = "none";
        |label.style.marginTop = "1px";
        |this.style.border = "";
        |this.style.margin = "";
    """.trimMargin()
}

private fun jsString(value: String): String =
    buildString {
        append('"')
        value.forEach { c ->
            append(
                when (c) {
                    '\\' -> "\\\\"
                    '"' -> "\\\""
                    '\n' -> "\\n"
                    '\r' -> "\\r"
                    '\t' -> "\\t"
                    else -> c
                },
            )
        }
        append('"')
    }
