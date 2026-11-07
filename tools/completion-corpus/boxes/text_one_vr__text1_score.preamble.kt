import afield.graphics.scene.reading.ReadingGallery
import afield.graphics.scene.reading.ReadingExample
import afield.graphics.vulkan.util.Window
import afield.graphics.vulkan.Renderer
import afield.graphics.vulkan.targets.MultiviewStereoTarget
var Box.poem by Inheritance<ReadingGallery>() /**/
val Once.poem by Onceing<ReadingGallery>() /**/
val Why.poem by Whying() /**/
var Box.exampleOne by Inheritance<ReadingExample>() /*from:text1_poem*/ /**/
val Once.exampleOne by Onceing<ReadingExample>() /*from:text1_poem*/ /**/
val Why.exampleOne by Whying() /*from:text1_poem*/ /**/
var Box.exampleTwo by Inheritance<ReadingExample>() /*from:text1_example2*/ /**/
val Once.exampleTwo by Onceing<ReadingExample>() /*from:text1_example2*/ /**/
val Why.exampleTwo by Whying() /*from:text1_example2*/ /**/
var Box.exampleThree by Inheritance<ReadingExample>() /*from:text1_example3*/ /**/
val Once.exampleThree by Onceing<ReadingExample>() /*from:text1_example3*/ /**/
val Why.exampleThree by Whying() /*from:text1_example3*/ /**/
var Box.exampleFour by Inheritance<ReadingExample>() /*from:text1_example4*/ /**/
val Once.exampleFour by Onceing<ReadingExample>() /*from:text1_example4*/ /**/
val Why.exampleFour by Whying() /*from:text1_example4*/ /**/
var Box.window by Inheritance<Window>() /*from:text1_context*/ /**/
val Once.window by Onceing<Window>() /*from:text1_context*/ /**/
val Why.window by Whying() /*from:text1_context*/ /**/
var Box.renderer by Inheritance<Renderer>() /*from:text1_context*/ /**/
val Once.renderer by Onceing<Renderer>() /*from:text1_context*/ /**/
val Why.renderer by Whying() /*from:text1_context*/ /**/
var Box.stereo by Inheritance<MultiviewStereoTarget>() /*from:text1_context*/ /**/
val Once.stereo by Onceing<MultiviewStereoTarget>() /*from:text1_context*/ /**/
val Why.stereo by Whying() /*from:text1_context*/ /**/
var Box.showStereoPair by Inheritance<Boolean>() /*from:text1_context*/ /**/
val Once.showStereoPair by Onceing<Boolean>() /*from:text1_context*/ /**/
val Why.showStereoPair by Whying() /*from:text1_context*/ /**/
