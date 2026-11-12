import afield.graphics.volume.CloudScene
import afield.graphics.volume.NeuralCloud
import afield.graphics.volume.AmbientFit
import afield.graphics.vulkan.util.Window
import afield.graphics.vulkan.Renderer
var Box.cloudScene by Inheritance<CloudScene>() /**/
val Once.cloudScene by Onceing<CloudScene>() /**/
val Why.cloudScene by Whying() /**/
var Box.cloudNet by Inheritance<NeuralCloud>() /**/
val Once.cloudNet by Onceing<NeuralCloud>() /**/
val Why.cloudNet by Whying() /**/
var Box.cloudAmbient by Inheritance<AmbientFit>() /**/
val Once.cloudAmbient by Onceing<AmbientFit>() /**/
val Why.cloudAmbient by Whying() /**/
var Box.window by Inheritance<Window>() /*from:cloud_renderer*/ /**/
val Once.window by Onceing<Window>() /*from:cloud_renderer*/ /**/
val Why.window by Whying() /*from:cloud_renderer*/ /**/
var Box.renderer by Inheritance<Renderer>() /*from:cloud_renderer*/ /**/
val Once.renderer by Onceing<Renderer>() /*from:cloud_renderer*/ /**/
val Why.renderer by Whying() /*from:cloud_renderer*/ /**/
