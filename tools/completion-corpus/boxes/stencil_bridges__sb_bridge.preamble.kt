import afield.graphics.text.TextLayout
import afield.geometry.solid.manifold.Section
var Box.needsBridge by Inheritance<List<Int>>() /*from:sb_judge*/ /**/
val Once.needsBridge by Onceing<List<Int>>() /*from:sb_judge*/ /**/
val Why.needsBridge by Whying() /*from:sb_judge*/ /**/
var Box.stencilWord by Inheritance<TextLayout>() /*from:sb_cut*/ /**/
val Once.stencilWord by Onceing<TextLayout>() /*from:sb_cut*/ /**/
val Why.stencilWord by Whying() /*from:sb_cut*/ /**/
var Box.stencilBlank by Inheritance<Section>() /*from:sb_cut*/ /**/
val Once.stencilBlank by Onceing<Section>() /*from:sb_cut*/ /**/
val Why.stencilBlank by Whying() /*from:sb_cut*/ /**/
var Box.stencilPieces by Inheritance<List<List<Any>>>() /*from:sb_cut*/ /**/
val Once.stencilPieces by Onceing<List<List<Any>>>() /*from:sb_cut*/ /**/
val Why.stencilPieces by Whying() /*from:sb_cut*/ /**/
var Box.stencilGlyphs by Inheritance<List<List<Any>>>() /*from:sb_cut*/ /**/
val Once.stencilGlyphs by Onceing<List<List<Any>>>() /*from:sb_cut*/ /**/
val Why.stencilGlyphs by Whying() /*from:sb_cut*/ /**/
