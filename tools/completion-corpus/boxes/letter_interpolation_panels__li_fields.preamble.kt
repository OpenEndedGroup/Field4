import afield.four.Renderer
import afield.four.util.Window
var Box.fieldA by Inheritance<afield.stdlib.FlowMatchExport>() /**/
val Once.fieldA by Onceing<afield.stdlib.FlowMatchExport>() /**/
var Box.fieldB by Inheritance<afield.stdlib.FlowMatchExport>() /**/
val Once.fieldB by Onceing<afield.stdlib.FlowMatchExport>() /**/
var Box.window by Inheritance<Window>() /*from:li_renderer*/ /**/
val Once.window by Onceing<Window>() /*from:li_renderer*/ /**/
var Box.renderer by Inheritance<Renderer>() /*from:li_renderer*/ /**/
val Once.renderer by Onceing<Renderer>() /*from:li_renderer*/ /**/
