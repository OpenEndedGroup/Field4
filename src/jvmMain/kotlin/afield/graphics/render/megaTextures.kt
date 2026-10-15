@file:afield.runtime.completion.CompletionCategory("Graphics")

/**
 * A directory of images as one texture, however many of them there are.
 *
 * [Box.megaTextureHere] is the whole box-facing surface: point it at a folder and it scans the
 * folder, builds the atlas, and registers the GLSL your shaders interpolate with `{{ mega.all }}`.
 * The machinery behind it lives in `afield.graphics.texture.mega`, and the reason it is worth reaching
 * for rather than a pile of `LoadedTexture`s is that it is *GPU-driven*: the fragment shader
 * measures its own on-screen footprint and asks for the resolution it needs, so nothing here has to
 * be told where anything is or how big it looks.
 *
 * `dev_docs/agents/transcripts/mega_texture_wall.yaml` is the worked example — five lines of Kotlin
 * and two shaders you can edit live.
 */

package afield.graphics.render

import afield.data.collections.get
import afield.data.collections.invoke
import afield.data.collections.m
import afield.data.collections.s
import afield.data.collections.times
import afield.data.graph.fragment
import afield.document.graph.minusAssign
import afield.document.graph.named
import afield.document.graph.plusAssign
import afield.document.properties.compareTo
import afield.document.workspace.file
import afield.document.workspace.files
import afield.graphics.shader.fragment
import afield.graphics.shader.moduleHere
import afield.graphics.shader.preprocess
import afield.math.autodiff.compareTo
import afield.math.autodiff.div
import afield.math.autodiff.dot
import afield.math.autodiff.minus
import afield.math.autodiff.plus
import afield.math.autodiff.times
import afield.math.linalg.invoke
import afield.math.linalg.remAssign
import afield.math.linalg.set
import afield.math.linalg.times
import afield.math.units.div
import afield.math.units.minus
import afield.math.units.plus
import afield.math.units.times
import afield.media.image.resize
import afield.media.image.split
import afield.media.video.image
import afield.runtime.lifecycle.cached

import afield.audio.synth.*
import afield.document.workspace.path
import afield.graphics.shader.shaderTemplate
import afield.graphics.vulkan.buffers.add
import afield.media.image.close
import afield.document.graph.Box
import afield.graphics.vulkan.Renderer
import afield.document.context.fromContext
import afield.graphics.texture.mega.MegaTexture
import afield.graphics.texture.mega.MegaTextureCatalog
import afield.graphics.texture.mega.MegaTextureConfig
import afield.app.diagnostics.logging.lprintln
import java.nio.file.Path

/**
 * What to show and how, for [Box.megaTextureHere].
 *
 * The only thing you have to say is where the pictures are. Everything in [config] has a default
 * that works for a directory of a few thousand; see [MegaTextureConfig] for the ones that matter
 * when there are a million.
 */
class MegaTextureSpec {
    /** Directories to take images from, in the order given. */
    val folders = mutableListOf<Path>()

    /** Walk subdirectories too. */
    var recursive: Boolean = true

    /** Lower-case file extensions to take, without the dot. Anything else in the folder is ignored. */
    var extensions: Set<String> = MegaTextureCatalog.DEFAULT_EXTENSIONS

    /** Stop after this many images — useful for trying a huge directory at a small size first. */
    var maxImages: Int = Int.MAX_VALUE

    /** The `shaderTemplate` key the GLSL slots are registered under: `{{ mega.all }}` by default. */
    var templateName: String = "mega"

    /**
     * Queue level-0 pages for everything up front, so the wall fills in without being looked at.
     *
     * On by default because the alternative — waiting for feedback to name each image — means a
     * wall of grey squares until the camera has pointed at each one, and because the same pass
     * populates the disk cache for every other small level at no extra cost.
     */
    var warmBaseLevel: Boolean = true

    /** Page sizes, residency budget, decode threads, bindings — see [MegaTextureConfig]. */
    val config = MegaTextureConfig()

    /** Add a directory of images, by path. */
    fun folder(path: String) = folder(Path.of(path))

    /** Add a directory of images. */
    fun folder(path: Path): MegaTextureSpec {
        folders.add(path)
        return this
    }

    /**
     * A string that changes exactly when the system would have to be rebuilt.
     *
     * A re-run of a box must not rebuild a hundreds-of-megabytes atlas and re-scan a million
     * files, so the instance is cached on the box and reused while this is unchanged. Sampling
     * *everything* that shapes the atlas is what makes the reuse safe, so this is derived from the
     * source spec plus the geometry fields rather than hand-maintained.
     */
    internal fun identity(): String = listOf(
        folders.joinToString("|") { it.toAbsolutePath().normalize().toString() },
        recursive.toString(),
        extensions.sorted().joinToString(","),
        maxImages.toString(),
        config.minPageSize.toString(),
        config.levels.toString(),
        config.atlasEdge.toString(),
        config.layersPerLevel.toString(),
        config.maxBaseLevelLayers.toString(),
        config.descriptorSet.toString(),
        config.atlasBinding.toString(),
        config.pageTableBinding.toString(),
        config.feedbackBinding.toString(),
        config.atlasName,
        config.pageTableName,
        config.feedbackName
    ).joinToString(";")
}

/**
 * Build (or reuse) this box's [MegaTexture] and register its GLSL under `{{ <templateName>.… }}`.
 *
 * Five lines get a wall of pictures with real virtual texturing behind it:
 *
 * ```
 * val mega = megaTextureHere { folder("/Users/marc/Movies/blade/scenes/allBlade") }
 * //%-
 * var module = moduleHere { mega.bind(this) }
 * var m = Mesh()
 * module += m named "wall"
 * renderer += module
 * mega.attach(module)
 * m.resize(6).also { it.instanceCount = mega.imageCount }
 * ```
 *
 * The `//%-` matters and is not decoration. `moduleHere` compiles this box's shaders when the
 * *macro expands*, which happens before any of this fragment's code runs — so the template has to
 * be registered in an earlier fragment or `{{ mega.all }}` is an unresolved slot and the shader
 * fails to preprocess. Lesson `5b. some templating` in `canonical_graphics` has the same split for
 * the same reason.
 *
 * The [Renderer] comes from graph context, so this box must be a descendant of the one that owns
 * the window.
 */
fun Box.megaTextureHere(build: MegaTextureSpec.() -> Unit): MegaTexture {
    val spec = MegaTextureSpec().apply(build)
    require(spec.folders.isNotEmpty()) {
        "megaTextureHere needs at least one folder(...) to take images from"
    }

    val instanceKey = "_megaTexture_${spec.templateName}"
    val identityKey = "_megaTextureIdentity_${spec.templateName}"
    val existing = getPropertyRaw(instanceKey) as? MegaTexture
    val identity = spec.identity()

    if (existing != null && getPropertyRaw(identityKey) == identity) {
        shaderTemplate[spec.templateName] = existing.template
        return existing
    }
    if (existing != null) {
        lprintln("info,megatexture", "megaTextureHere: rebuilding '${spec.templateName}', its shape changed")
        runCatching { existing.close() }
    }

    val renderer: Renderer = fromContext()
    val catalog = MegaTextureCatalog.scan(
        roots = spec.folders,
        extensions = spec.extensions,
        recursive = spec.recursive,
        cacheRoot = spec.config.cacheRoot ?: MegaTextureCatalog.defaultCacheRoot(),
        maxImages = spec.maxImages
    )
    val mega = MegaTexture(renderer, catalog, spec.config)
    if (spec.warmBaseLevel) mega.warmBaseLevel()

    setPropertyRaw(instanceKey, mega)
    setPropertyRaw(identityKey, identity)
    shaderTemplate[spec.templateName] = mega.template
    return mega
}

/** This box's [MegaTexture] under [templateName], if [megaTextureHere] has built one. */
fun Box.megaTexture(templateName: String = "mega"): MegaTexture? =
    getPropertyRaw("_megaTexture_$templateName") as? MegaTexture
