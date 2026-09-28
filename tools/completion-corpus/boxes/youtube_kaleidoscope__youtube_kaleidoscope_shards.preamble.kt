import afield.four.Renderer
import afield.four.util.Window
var Box.browserControl by Inheritance<afield.four.browser.PuppetBrowser>() /**/
val Once.browserControl by Onceing<afield.four.browser.PuppetBrowser>() /**/
var Box.window by Inheritance<Window>() /*from:youtube_kaleidoscope_renderer*/ /**/
val Once.window by Onceing<Window>() /*from:youtube_kaleidoscope_renderer*/ /**/
var Box.renderer by Inheritance<Renderer>() /*from:youtube_kaleidoscope_renderer*/ /**/
val Once.renderer by Onceing<Renderer>() /*from:youtube_kaleidoscope_renderer*/ /**/
