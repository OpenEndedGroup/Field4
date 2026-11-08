import afield.graphics.scene.reading.StereoDiagramRenderer
import afield.graphics.scene.reading.ReadingExample
import afield.graphics.scene.reading.ReadingGallery
import afield.graphics.vulkan.buffers.*
import afield.document.context.*
import afield.graphics.render.*
import afield.graphics.scene.*
import afield.graphics.shader.*
import arrow.core.left
import arrow.core.right
import java.nio.*
import afield.graphics.vulkan.util.Window
import afield.graphics.vulkan.Renderer
import afield.graphics.vulkan.targets.MultiviewStereoTarget
var Box.drawing by Inheritance<StereoDiagramRenderer>() /**/
val Once.drawing by Onceing<StereoDiagramRenderer>() /**/
val Why.drawing by Whying() /**/
var Box.exampleOne by Inheritance<ReadingExample>() /*from:text1_poem*/ /**/
val Once.exampleOne by Onceing<ReadingExample>() /*from:text1_poem*/ /**/
val Why.exampleOne by Whying() /*from:text1_poem*/ /**/
var Box.poem by Inheritance<ReadingGallery>() /*from:text1_score*/ /**/
val Once.poem by Onceing<ReadingGallery>() /*from:text1_score*/ /**/
val Why.poem by Whying() /*from:text1_score*/ /**/
var Box.strokeModuleFactory by Inheritance<afield.graphics.scene.reading.DiagramModuleFactory>() /*from:text1_stroke*/ /**/
val Once.strokeModuleFactory by Onceing<afield.graphics.scene.reading.DiagramModuleFactory>() /*from:text1_stroke*/ /**/
val Why.strokeModuleFactory by Whying() /*from:text1_stroke*/ /**/
var Box.pointModuleFactory by Inheritance<afield.graphics.scene.reading.DiagramModuleFactory>() /*from:text1_point*/ /**/
val Once.pointModuleFactory by Onceing<afield.graphics.scene.reading.DiagramModuleFactory>() /*from:text1_point*/ /**/
val Why.pointModuleFactory by Whying() /*from:text1_point*/ /**/
var Box.textModuleFactory by Inheritance<afield.graphics.scene.reading.DiagramModuleFactory>() /*from:text1_text*/ /**/
val Once.textModuleFactory by Onceing<afield.graphics.scene.reading.DiagramModuleFactory>() /*from:text1_text*/ /**/
val Why.textModuleFactory by Whying() /*from:text1_text*/ /**/
var Box.mediumHorizontalFactory by Inheritance<afield.graphics.scene.reading.DiagramModuleFactory>() /*from:text1_blur*/ /**/
val Once.mediumHorizontalFactory by Onceing<afield.graphics.scene.reading.DiagramModuleFactory>() /*from:text1_blur*/ /**/
val Why.mediumHorizontalFactory by Whying() /*from:text1_blur*/ /**/
var Box.mediumVerticalFactory by Inheritance<afield.graphics.scene.reading.DiagramModuleFactory>() /*from:text1_blur_medium_v*/ /**/
val Once.mediumVerticalFactory by Onceing<afield.graphics.scene.reading.DiagramModuleFactory>() /*from:text1_blur_medium_v*/ /**/
val Why.mediumVerticalFactory by Whying() /*from:text1_blur_medium_v*/ /**/
var Box.broadHorizontalFactory by Inheritance<afield.graphics.scene.reading.DiagramModuleFactory>() /*from:text1_blur_broad_h*/ /**/
val Once.broadHorizontalFactory by Onceing<afield.graphics.scene.reading.DiagramModuleFactory>() /*from:text1_blur_broad_h*/ /**/
val Why.broadHorizontalFactory by Whying() /*from:text1_blur_broad_h*/ /**/
var Box.broadVerticalFactory by Inheritance<afield.graphics.scene.reading.DiagramModuleFactory>() /*from:text1_blur_broad_v*/ /**/
val Once.broadVerticalFactory by Onceing<afield.graphics.scene.reading.DiagramModuleFactory>() /*from:text1_blur_broad_v*/ /**/
val Why.broadVerticalFactory by Whying() /*from:text1_blur_broad_v*/ /**/
var Box.compositeModuleFactory by Inheritance<afield.graphics.scene.reading.DiagramModuleFactory>() /*from:text1_composite*/ /**/
val Once.compositeModuleFactory by Onceing<afield.graphics.scene.reading.DiagramModuleFactory>() /*from:text1_composite*/ /**/
val Why.compositeModuleFactory by Whying() /*from:text1_composite*/ /**/
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
var Box.exampleTwo by Inheritance<ReadingExample>() /*from:text1_example2*/ /**/
val Once.exampleTwo by Onceing<ReadingExample>() /*from:text1_example2*/ /**/
val Why.exampleTwo by Whying() /*from:text1_example2*/ /**/
var Box.exampleThree by Inheritance<ReadingExample>() /*from:text1_example3*/ /**/
val Once.exampleThree by Onceing<ReadingExample>() /*from:text1_example3*/ /**/
val Why.exampleThree by Whying() /*from:text1_example3*/ /**/
var Box.exampleFour by Inheritance<ReadingExample>() /*from:text1_example4*/ /**/
val Once.exampleFour by Onceing<ReadingExample>() /*from:text1_example4*/ /**/
val Why.exampleFour by Whying() /*from:text1_example4*/ /**/
var Box.exampleFive by Inheritance<ReadingExample>() /*from:text1_typography*/ /**/
val Once.exampleFive by Onceing<ReadingExample>() /*from:text1_typography*/ /**/
val Why.exampleFive by Whying() /*from:text1_typography*/ /**/
var Box.exampleSix by Inheritance<ReadingExample>() /*from:text1_word_blur*/ /**/
val Once.exampleSix by Onceing<ReadingExample>() /*from:text1_word_blur*/ /**/
val Why.exampleSix by Whying() /*from:text1_word_blur*/ /**/
