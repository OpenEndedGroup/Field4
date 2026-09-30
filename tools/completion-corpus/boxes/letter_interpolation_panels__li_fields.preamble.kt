import afield.four.Renderer
import afield.four.util.Window
var Box.fieldA by Inheritance<afield.stdlib.FlowMatchExport>() /**/
val Once.fieldA by Onceing<afield.stdlib.FlowMatchExport>() /**/
val Why.fieldA by Whying() /**/
var Box.fieldB by Inheritance<afield.stdlib.FlowMatchExport>() /**/
val Once.fieldB by Onceing<afield.stdlib.FlowMatchExport>() /**/
val Why.fieldB by Whying() /**/
var Box.window by Inheritance<Window>() /*from:li_renderer*/ /**/
val Once.window by Onceing<Window>() /*from:li_renderer*/ /**/
val Why.window by Whying() /*from:li_renderer*/ /**/
var Box.renderer by Inheritance<Renderer>() /*from:li_renderer*/ /**/
val Once.renderer by Onceing<Renderer>() /*from:li_renderer*/ /**/
val Why.renderer by Whying() /*from:li_renderer*/ /**/
