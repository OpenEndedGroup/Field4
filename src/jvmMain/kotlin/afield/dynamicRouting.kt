package afield

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
