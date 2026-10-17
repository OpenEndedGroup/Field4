/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

@file:OptIn(
    org.jetbrains.kotlin.config.CompilerConfiguration.Internals::class,
    org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi::class
)

package afield.runtime.kotlin

import afield.data.table.div
import afield.data.table.get
import afield.data.table.minus
import afield.data.table.plus
import afield.data.table.rem
import afield.data.table.times
import afield.document.graph.box
import afield.runtime.execution.plus
import afield.runtime.lifecycle.plus
import afield.runtime.lifecycle.rem
import afield.ui.notation.with
import afield.ui.output.tree

import afield.audio.synth.*
import afield.graphics.vulkan.*
import afield.media.stream.*
import afield.media.video.*
import org.jetbrains.kotlin.cli.extensionsStorage
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.com.intellij.openapi.Disposable
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.com.intellij.openapi.util.text.StringUtil
import org.jetbrains.kotlin.com.intellij.psi.PsiErrorElement
import org.jetbrains.kotlin.com.intellij.psi.PsiManager
import org.jetbrains.kotlin.com.intellij.testFramework.LightVirtualFile
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.idea.KotlinFileType
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType
import org.jetbrains.kotlin.psi.psiUtil.startOffset
import org.jetbrains.ktfmt.format.ParseError

/**
 * Kotlin source to a parse tree, without compiling it.
 *
 * Several things in Field need to look at the shape of box code rather than run it: reformat it,
 * find which imports it names, rewrite a call, translate it to JavaScript. Compiling is the
 * expensive way to get that, and it fails on code that does not yet make sense, which is most code
 * while it is being typed. This file holds the one parser they share: a `KotlinCoreEnvironment`
 * built once and reused, and a `parse` that returns the tree or throws on the first syntax error.
 *
 * It is derived from ktfmt's own parser, and the Apache 2.0 notice above is ktfmt's.
 */

/**
 * Parses Kotlin source as a script (which box code is) and returns its parse tree, for Field code
 * that wants PSI without compiling: the comment pass in `formatter.kt`, the import analysis in
 * `boxImports.kt`, the labelled-argument macro, the RemoteJS instrumenter, and the pattern-rewrite
 * smoke test.
 *
 * Until 2026-09-30 this was a copy of ktfmt's `Parser` under ktfmt's own class name, meant to
 * replace it on the classpath. It did not: ktfmt's jar won, and the copy only worked because
 * ktfmt 0.64's `Parser` had the same `parse(String)`. It is Field's own class now; ktfmt keeps its
 * parser. Derived from ktfmt's `Parser` (Apache 2.0, notice above).
 */
object FieldKtParser {

    /**
     * The environment used to open a KtFile.
     *
     * Field runs ktfmt inside the same JVM as its customized Kotlin compiler. That compiler
     * expects extension storage to be present before project extensions are configured.
     */
    val env: KotlinCoreEnvironment

    init {
        // To hide annoying warning on Windows.
        System.setProperty("idea.use.native.fs.for.win", "false")
        val disposable = Disposer.newDisposable()
        val configuration = CompilerConfiguration()
        configuration.extensionsStorage = CompilerPluginRegistrar.ExtensionStorage()
        env = createEnvironment(disposable, configuration)
    }

    fun parse(code: String): KtFile {
        val virtualFile = LightVirtualFile("temp.kts", KotlinFileType.INSTANCE, code)
        val ktFile = PsiManager.getInstance(env.project).findFile(virtualFile) as KtFile
        val descendants = ktFile.collectDescendantsOfType<PsiErrorElement>()
        if (descendants.isNotEmpty()) throwParseError(code, descendants[0])
        return ktFile
    }

    private fun throwParseError(fileContents: String, error: PsiErrorElement): Nothing {
        throw ParseError(
            error.errorDescription,
            StringUtil.offsetToLineColumn(fileContents, error.startOffset)
        )
    }

    private fun createEnvironment(
        disposable: Disposable,
        configuration: CompilerConfiguration
    ): KotlinCoreEnvironment {
        val companion = KotlinCoreEnvironment.Companion
        val method =
            companion::class.java.getMethod(
                "createForProduction",
                Disposable::class.java,
                CompilerConfiguration::class.java,
                EnvironmentConfigFiles::class.java
            )
        return method.invoke(companion, disposable, configuration, EnvironmentConfigFiles.JVM_CONFIG_FILES)
            as KotlinCoreEnvironment
    }
}
