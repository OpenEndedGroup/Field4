import afield.graphics.text.TextLayout
import afield.geometry.solid.manifold.Section
var Box.stencilWord by Inheritance<TextLayout>() /**/
val Once.stencilWord by Onceing<TextLayout>() /**/
val Why.stencilWord by Whying() /**/
var Box.stencilBlank by Inheritance<Section>() /**/
val Once.stencilBlank by Onceing<Section>() /**/
val Why.stencilBlank by Whying() /**/
var Box.stencilPieces by Inheritance<List<List<Any>>>() /**/
val Once.stencilPieces by Onceing<List<List<Any>>>() /**/
val Why.stencilPieces by Whying() /**/
var Box.stencilGlyphs by Inheritance<List<List<Any>>>() /**/
val Once.stencilGlyphs by Onceing<List<List<Any>>>() /**/
val Why.stencilGlyphs by Whying() /**/
