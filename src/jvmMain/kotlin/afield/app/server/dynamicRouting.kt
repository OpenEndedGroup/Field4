package afield.app.server

import afield.data.table.div
import afield.data.table.get
import afield.data.table.minus
import afield.data.table.plus
import afield.data.table.rem
import afield.data.table.times
import afield.runtime.execution.plus
import afield.runtime.lifecycle.plus
import afield.runtime.lifecycle.rem

import afield.audio.synth.*
import afield.graphics.vulkan.*
import afield.media.stream.*
import afield.media.video.*
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * A registry that maps URI prefixes to directories, so the server can serve files from folders chosen at runtime.
 *
 * The `/local` route in `Server.kt` looks up a request path in [dynamicRoutes], longest matching
 * prefix first, and serves the file from the directory registered for that prefix.
 */

/** Prefix-to-directory table consulted by the server's `/local` route. */
class DynamicDirectoryRoutes {
    private val routes = ConcurrentHashMap<String, File>()

    // Add a mapping from a URI prefix (e.g. "/files/") to a directory
    fun addRoute(prefix: String, directory: File) {
        routes[prefix] = directory
    }

    // Get a copy of current mappings
    fun getRoutes(): Map<String, File> = routes.toMap()
}

val dynamicRoutes = DynamicDirectoryRoutes()
