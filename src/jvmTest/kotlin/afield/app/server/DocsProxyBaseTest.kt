package afield.app.server

import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A proxied page's `<base href>` stays under `/field-docs/`: an upstream base made fonts and the
 * search index cross-origin requests to a site that sends no CORS headers.
 */
class DocsProxyBaseTest {
    @Test
    fun proxiedPagesResolveAssetsThroughTheProxy() {
        assertEquals("/field-docs/", proxiedDocsPageBase(URI("https://openendedgroup.com/field4/")))
        assertEquals("/field-docs/", proxiedDocsPageBase(URI("https://openendedgroup.com/field4/index.html")))
        assertEquals("/field-docs/docs_map/", proxiedDocsPageBase(URI("https://openendedgroup.com/field4/docs_map/?node=page%3Aindex.md")))
        assertEquals("/field-docs/reference/geometry/", proxiedDocsPageBase(URI("https://openendedgroup.com/field4/reference/geometry/index.html")))
    }
}
