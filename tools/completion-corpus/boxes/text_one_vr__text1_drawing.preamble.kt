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
var Box.textCacheWidth by Inheritance<Int>(2048) /*from:root*/ /**/
val Once.textCacheWidth by Onceing<Int>() /*from:root*/ /**/
val Why.textCacheWidth by Whying() /*from:root*/ /**/
var Box.textCacheHeight by Inheritance<Int>(1024) /*from:root*/ /**/
val Once.textCacheHeight by Onceing<Int>() /*from:root*/ /**/
val Why.textCacheHeight by Whying() /*from:root*/ /**/
var Box.textCacheMinimum by Inheritance<Int>(512) /*from:root*/ /**/
val Once.textCacheMinimum by Onceing<Int>() /*from:root*/ /**/
val Why.textCacheMinimum by Whying() /*from:root*/ /**/
var Box.textCacheHalving by Inheritance<Int>(2) /*from:root*/ /**/
val Once.textCacheHalving by Onceing<Int>() /*from:root*/ /**/
val Why.textCacheHalving by Whying() /*from:root*/ /**/
var Box.textCacheTaps by Inheritance<Int>(513) /*from:root*/ /**/
val Once.textCacheTaps by Onceing<Int>() /*from:root*/ /**/
val Why.textCacheTaps by Whying() /*from:root*/ /**/
var Box.textCacheCutoff by Inheritance<Float>(4f) /*from:root*/ /**/
val Once.textCacheCutoff by Onceing<Float>() /*from:root*/ /**/
val Why.textCacheCutoff by Whying() /*from:root*/ /**/
var Box.showSkeleton by Inheritance<Boolean>(false) /*from:root*/ /**/
val Once.showSkeleton by Onceing<Boolean>() /*from:root*/ /**/
val Why.showSkeleton by Whying() /*from:root*/ /**/
var Box.showPhysicsNodes by Inheritance<Boolean>(true) /*from:root*/ /**/
val Once.showPhysicsNodes by Onceing<Boolean>() /*from:root*/ /**/
val Why.showPhysicsNodes by Whying() /*from:root*/ /**/
var Box.showBodyForces by Inheritance<Boolean>(true) /*from:root*/ /**/
val Once.showBodyForces by Onceing<Boolean>() /*from:root*/ /**/
val Why.showBodyForces by Whying() /*from:root*/ /**/
var Box.frostBlackLevel by Inheritance<Float>(.012f) /*from:root*/ /**/
val Once.frostBlackLevel by Onceing<Float>() /*from:root*/ /**/
val Why.frostBlackLevel by Whying() /*from:root*/ /**/
var Box.frostBlurGamma by Inheritance<Float>(1f) /*from:root*/ /**/
val Once.frostBlurGamma by Onceing<Float>() /*from:root*/ /**/
val Why.frostBlurGamma by Whying() /*from:root*/ /**/
var Box.textBlurGain by Inheritance<Float>(1f) /*from:root*/ /**/
val Once.textBlurGain by Onceing<Float>() /*from:root*/ /**/
val Why.textBlurGain by Whying() /*from:root*/ /**/
var Box.textBlurContrast by Inheritance<Float>(1f) /*from:root*/ /**/
val Once.textBlurContrast by Onceing<Float>() /*from:root*/ /**/
val Why.textBlurContrast by Whying() /*from:root*/ /**/
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
var Box.exampleSeven by Inheritance<ReadingExample>() /*from:text1_elastic*/ /**/
val Once.exampleSeven by Onceing<ReadingExample>() /*from:text1_elastic*/ /**/
val Why.exampleSeven by Whying() /*from:text1_elastic*/ /**/
var Box.exampleEight by Inheritance<ReadingExample>() /*from:text1_drop_caps*/ /**/
val Once.exampleEight by Onceing<ReadingExample>() /*from:text1_drop_caps*/ /**/
val Why.exampleEight by Whying() /*from:text1_drop_caps*/ /**/
var Box.exampleNine by Inheritance<ReadingExample>() /*from:text1_two_readings*/ /**/
val Once.exampleNine by Onceing<ReadingExample>() /*from:text1_two_readings*/ /**/
val Why.exampleNine by Whying() /*from:text1_two_readings*/ /**/
var Box.exampleTen by Inheritance<ReadingExample>() /*from:text1_overstroke_rectangle*/ /**/
val Once.exampleTen by Onceing<ReadingExample>() /*from:text1_overstroke_rectangle*/ /**/
val Why.exampleTen by Whying() /*from:text1_overstroke_rectangle*/ /**/
var Box.exampleEleven by Inheritance<ReadingExample>() /*from:text1_overstroke_union*/ /**/
val Once.exampleEleven by Onceing<ReadingExample>() /*from:text1_overstroke_union*/ /**/
val Why.exampleEleven by Whying() /*from:text1_overstroke_union*/ /**/
var Box.exampleTwelve by Inheritance<ReadingExample>() /*from:text1_frustum_readings*/ /**/
val Once.exampleTwelve by Onceing<ReadingExample>() /*from:text1_frustum_readings*/ /**/
val Why.exampleTwelve by Whying() /*from:text1_frustum_readings*/ /**/
var Box.exampleThirteen by Inheritance<ReadingExample>() /*from:text1_frustum_two_faces*/ /**/
val Once.exampleThirteen by Onceing<ReadingExample>() /*from:text1_frustum_two_faces*/ /**/
val Why.exampleThirteen by Whying() /*from:text1_frustum_two_faces*/ /**/
