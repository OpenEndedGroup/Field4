package afield.runtime.kotlin

import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.KtDiagnosticFactoryToRendererMap
import org.jetbrains.kotlin.diagnostics.KtDiagnosticsContainer
import org.jetbrains.kotlin.diagnostics.rendering.BaseDiagnosticRendererFactory
import org.jetbrains.kotlin.diagnostics.rendering.CommonRenderers
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.diagnostics.warning2
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.expression.ExpressionCheckers
import org.jetbrains.kotlin.fir.analysis.checkers.expression.FirQualifiedAccessExpressionChecker
import org.jetbrains.kotlin.fir.analysis.extensions.FirAdditionalCheckersExtension
import org.jetbrains.kotlin.fir.declarations.DirectDeclarationsAccess
import org.jetbrains.kotlin.fir.declarations.FirCallableDeclaration
import org.jetbrains.kotlin.fir.expressions.FirQualifiedAccessExpression
import org.jetbrains.kotlin.fir.expressions.FirThisReceiverExpression
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrar
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrarAdapter
import org.jetbrains.kotlin.fir.references.toResolvedCallableSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirAnonymousFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirReplSnippetSymbol
import org.jetbrains.kotlin.fir.symbols.SymbolInternals
import org.jetbrains.kotlin.name.Name

/**
 * A compiler warning for box code: a name the box declares at its top level that, inside a lambda,
 * resolves to a member of the lambda's receiver instead.
 *
 * ```
 * val h = 40
 * wfc { repeat(h) { ... } }   // h is the receiver's h here, not 40
 * ```
 *
 * Kotlin resolves an unqualified name to an implicit receiver's member before a declaration of the
 * enclosing script, so `h`, `sky` or `error` inside `wfc { }`, `frames { }` or `apply { }` quietly
 * mean something else. This checker reports each such use (`FIELD_SHADOWED_BY_RECEIVER`) naming both;
 * the editor shows it as a warning on the line. It only looks at unqualified uses that went through
 * an implicit receiver, so `it.h`, `other.h` and the box's own top-level uses are not reported.
 *
 * Registered as a K2 compiler plugin through [ShadowedSnippetNameCheckerPlugin], passed with
 * `-Xplugin` like Wither (see `KotlinInterface2.compilerPluginArgs`).
 */
object ShadowedSnippetNameDiagnostics : KtDiagnosticsContainer() {
    val FIELD_SHADOWED_BY_RECEIVER by warning2<PsiElement, String, String>()

    /** Text every rendered message of this checker carries, which is how a compile result's reports are filtered for it. */
    const val MESSAGE_MARK = "inside a lambda, the receiver's member wins"

    override fun getRendererFactory(): BaseDiagnosticRendererFactory = Messages

    private object Messages : BaseDiagnosticRendererFactory() {
        override val MAP: KtDiagnosticFactoryToRendererMap by KtDiagnosticFactoryToRendererMap("Field") { map ->
            map.put(
                FIELD_SHADOWED_BY_RECEIVER,
                "''{0}'' here is {1}, not the ''{0}'' this box declares: inside a lambda, the receiver''s member wins. Rename the box''s ''{0}''.",
                CommonRenderers.STRING,
                CommonRenderers.STRING,
            )
        }
    }
}

private object ShadowedSnippetNameCallChecker : FirQualifiedAccessExpressionChecker(MppCheckerKind.Common) {
    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(expression: FirQualifiedAccessExpression) {
        if (expression.explicitReceiver != null) return
        val receiver = (expression.dispatchReceiver as? FirThisReceiverExpression)
            ?: (expression.extensionReceiver as? FirThisReceiverExpression)
            ?: return
        if (!receiver.isImplicit) return
        val snippet = context.containingDeclarations.firstNotNullOfOrNull { it as? FirReplSnippetSymbol } ?: return
        // Only inside a lambda: at the box's top level its own declarations are what Kotlin picks.
        if (context.containingDeclarations.none { it is FirAnonymousFunctionSymbol }) return
        val target = expression.calleeReference.toResolvedCallableSymbol() ?: return
        val snippetClass = snippet.snippetClassSymbol
        if (target.callableId?.classId == snippetClass.classId) return
        val name = target.callableId?.callableName ?: return
        if (name !in declaredNames(snippet)) return
        val owner = (receiver.calleeReference.boundSymbol as? FirClassSymbol<*>)?.classId?.shortClassName?.asString()
            ?: target.callableId?.classId?.shortClassName?.asString()
            ?: "the lambda's receiver"
        reporter.reportOn(expression.source, ShadowedSnippetNameDiagnostics.FIELD_SHADOWED_BY_RECEIVER, name.asString(), "$owner.${name.asString()}")
    }

    @OptIn(SymbolInternals::class, DirectDeclarationsAccess::class)
    private fun declaredNames(snippet: FirReplSnippetSymbol): Set<Name> =
        snippet.snippetClassSymbol.fir.declarations.mapNotNullTo(HashSet()) { (it as? FirCallableDeclaration)?.symbol?.callableId?.callableName }
}

private class ShadowedSnippetNameCheckers(session: FirSession) : FirAdditionalCheckersExtension(session) {
    override val expressionCheckers: ExpressionCheckers = object : ExpressionCheckers() {
        override val qualifiedAccessExpressionCheckers: Set<FirQualifiedAccessExpressionChecker> = setOf(ShadowedSnippetNameCallChecker)
    }
}

private class ShadowedSnippetNameFirRegistrar : FirExtensionRegistrar() {
    override fun ExtensionRegistrarContext.configurePlugin() {
        +::ShadowedSnippetNameCheckers
        registerDiagnosticContainers(ShadowedSnippetNameDiagnostics)
    }
}

/** The plugin entry point the compiler finds through the services jar [ShadowedSnippetNameCheckerPlugin.jar] writes. */
@OptIn(ExperimentalCompilerApi::class)
class ShadowedSnippetNameCheckerPlugin : CompilerPluginRegistrar() {
    override val pluginId: String get() = "afield.shadowed-snippet-name"
    override val supportsK2: Boolean get() = true

    override fun ExtensionStorage.registerExtensions(configuration: CompilerConfiguration) {
        FirExtensionRegistrarAdapter.registerExtension(ShadowedSnippetNameFirRegistrar())
    }

    companion object {
        /**
         * A jar holding only the service entry naming this class. The classes themselves come from
         * Field's classpath, as Wither's do, so the plugin never needs building separately.
         */
        val jar: java.io.File by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
            val file = java.io.File(System.getProperty("java.io.tmpdir"), "afield-shadowed-name-plugin-${ProcessHandle.current().pid()}.jar")
            java.util.jar.JarOutputStream(file.outputStream()).use { out ->
                out.putNextEntry(java.util.jar.JarEntry("META-INF/services/org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar"))
                out.write((ShadowedSnippetNameCheckerPlugin::class.java.name + "\n").toByteArray())
                out.closeEntry()
            }
            file.deleteOnExit()
            file
        }
    }
}
