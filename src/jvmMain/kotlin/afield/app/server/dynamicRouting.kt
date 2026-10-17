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
